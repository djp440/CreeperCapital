package io.github.djp440.creepercapital.gametest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.trading.FundingRecord;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderStatus;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.server.ServerAccounts;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerTrading;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * 行情步内四个阶段顺序的端到端 GameTest：① 委托触发 → ② 资金费结算 → ③ 强平检查 → ④ 偿付检查（ADL）。
 * 各阶段单独的规则在各自的 GameTest 和 core 单元测试里，这里只验证 {@link ServerTrading#onMarketStep} 把它们按这个顺序串起来：
 * <ul>
 *   <li>玩家 X 持有止盈，同一步先被止盈平掉，所以收不到资金费（若资金费排在委托触发之前，X 会有一条记录）；</li>
 *   <li>玩家 Y 的仓位本身健康，是被 500% 的资金费抽干保证金后才在同一步被强平（强平排在资金费之前就不会触发）；</li>
 *   <li>玩家 Z 持有的巨额浮盈让池子权益转负，在同一步最后被自动减仓（成交编号是全局递增的，X 的止盈 < Y 的强平 < Z 的 ADL）。</li>
 * </ul>
 * 共用的活账本里池子权益是百万量级，Z 用「十分之一价格开仓的巨额多头」让浮盈远超池子；收尾时池子被 Z 的平仓支付掉的部分补回去，
 * 免得别的测试开不出仓。Z 放在 GUNPOWDERCRP 上，因为合成的资金费只针对 DIAMONDCRP，免得 500% 的费率抽干 Z。
 */
final class StageOrderGameTests {
    private static final String DIAMOND = "DIAMONDCRP";
    private static final String GUNPOWDER = "GUNPOWDERCRP";
    /** 够大的结算点：4800 的整数倍，远超活市场的行情步，不会和真实结算点撞上。 */
    private static final long SETTLEMENT_STEP = 4800L * 1_000_000;
    private static final ToDoubleFunction<String> LIVE_MARKS = symbol -> ServerMarket.symbols().contains(symbol)
            ? ServerMarket.snapshot(symbol).markPrice() : Double.NaN;

    private StageOrderGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("market_step_runs_order_trigger_then_funding_then_liquidation_then_adl",
                StageOrderGameTests::stagesRunInOrder);
    }

    private static ContractSpec diamond() {
        return ServerMarket.spec(DIAMOND);
    }

    private static long lots() {
        return Math.max(1, diamond().liquidityCurve(ServerMarket.snapshot(DIAMOND).lastPrice()).levelLots() / 2);
    }

    private static int leverage() {
        return Math.min(5, diamond().maxLeverage());
    }

    private static BigDecimal asDecimal(Money money) {
        return BigDecimal.valueOf(money.units()).movePointLeft(6);
    }

    /** 名义价值折成手数（向下取整，至少 1 手）。 */
    private static long lotsFor(ContractSpec spec, BigDecimal notional, double price) {
        BigDecimal lots = notional.divide(BigDecimal.valueOf(price), 12, RoundingMode.DOWN)
                .movePointRight(spec.quantityDecimals()).setScale(0, RoundingMode.DOWN);
        return Math.max(1, lots.longValueExact());
    }

    private static void stagesRunInOrder(GameTestHelper helper) {
        TradingBook book = ServerTrading.book();
        Money poolBefore = ServerTrading.poolBalance();
        ServerPlayer x = TradingGameTests.trader(helper, "StageTp1", "100000");
        ServerPlayer y = TradingGameTests.trader(helper, "StageFunding1", "100000");
        ServerPlayer z = TradingGameTests.trader(helper, "StageAdl1", "1");
        try {
            UUID xId = x.getUUID();
            UUID yId = y.getUUID();
            UUID zId = z.getUUID();
            MarketSnapshot live = ServerMarket.snapshot(DIAMOND);

            // X：市价开多，附带最新价之上的止盈；合成的这一步把最新价抬过它
            double takeProfit = Math.ceil(live.lastPrice() * 1.002);
            ServerTrading.handleOrder(x, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(), leverage(),
                    OrderPayload.Execution.MARKET, 0, takeProfit, 0, TriggerSource.LAST, false));
            helper.assertValueEqual(book.orders().open(xId).size(), 1, "X 带着一张止盈");

            // Y：普通的健康多头，没有任何止盈止损
            ServerTrading.handleOrder(y, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(), leverage()));
            Position yPosition = book.position(yId, DIAMOND).orElseThrow();
            helper.assertTrue(book.assets(yId, LIVE_MARKS).availableBalance().isPositive(), "Y 开仓后账户健康");

            // Z：GUNPOWDERCRP 上以 1/20 价格开的杠杆 1 巨额多头，现价相当于 20 倍，浮盈远超池子
            ContractSpec gunpowder = ServerMarket.spec(GUNPOWDER);
            MarketSnapshot gunpowderLive = ServerMarket.snapshot(GUNPOWDER);
            MarketSnapshot cheap = new MarketSnapshot(gunpowderLive.step(), GUNPOWDER, gunpowderLive.indexPrice() / 20,
                    gunpowderLive.lastPrice() / 20, gunpowderLive.markPrice() / 20, gunpowderLive.fundingRate(), 0);
            BigDecimal notional = asDecimal(gunpowder.marginTiers().maxNotionalAt(1)).multiply(new BigDecimal("0.7"))
                    .min(asDecimal(book.openInterestCap(gunpowder, LIVE_MARKS)).multiply(new BigDecimal("0.3")));
            ServerAccounts.deposit(helper.getLevel().getServer(), zId,
                    Money.parse(notional.multiply(new BigDecimal("1.3")).add(BigDecimal.TEN).setScale(6, RoundingMode.UP).toPlainString()));
            book.open(zId, gunpowder, cheap, Side.LONG, lotsFor(gunpowder, notional, cheap.lastPrice() * 1.05), 1, LIVE_MARKS);
            helper.assertTrue(book.position(zId, GUNPOWDER).isPresent(), "Z 的巨额多头开仓成功");
            helper.assertTrue(book.poolEquity(LIVE_MARKS).units() < 0, "Z 的浮盈让池子权益转负：" + book.poolEquity(LIVE_MARKS));

            // 合成的结算点那一步：最新价越过 X 的止盈，费率 500%（只针对 DIAMONDCRP）
            MarketSnapshot settlement = new MarketSnapshot(SETTLEMENT_STEP, DIAMOND, live.indexPrice(), takeProfit + 1,
                    live.markPrice(), 5.0, 0);
            helper.assertValueEqual(settlement.secondsUntilFunding(), 0L, "合成的是资金费结算点");
            ServerTrading.onMarketStep(helper.getLevel().getServer(), List.of(settlement));

            // ① X 的止盈先成交
            helper.assertTrue(book.position(xId, DIAMOND).isEmpty(), "X 被止盈平掉");
            Order takeProfitOrder = book.orders().history(xId).get(0);
            helper.assertValueEqual(takeProfitOrder.type(), OrderType.TAKE_PROFIT, "委托类型");
            helper.assertValueEqual(takeProfitOrder.status(), OrderStatus.FILLED, "止盈已成交");
            PlayerTrade xClose = TradingGameTests.last(book.trades(xId));
            helper.assertValueEqual(xClose.kind(), TradeKind.CLOSE, "止盈是一笔平仓");
            // ② 平掉之后才结算资金费，所以 X 没有记录；Y 被抽干保证金
            helper.assertTrue(book.fundingRecords(xId).isEmpty(), "X 在资金费结算前已平仓，没有资金费记录");
            List<FundingRecord> yFunding = book.fundingRecords(yId);
            helper.assertValueEqual(yFunding.size(), 1, "Y 结算了一次资金费");
            helper.assertValueEqual(yFunding.get(0).amount(), Money.ZERO.minus(yPosition.margin()), "资金费抽干了 Y 的保证金");
            // ③ 保证金被抽干的 Y 在同一步被强平（说明强平排在资金费之后：它原本是健康的仓位）
            helper.assertTrue(book.position(yId, DIAMOND).isEmpty(), "Y 在同一步被强平");
            PlayerTrade yLiquidation = TradingGameTests.last(book.trades(yId));
            helper.assertValueEqual(yLiquidation.kind(), TradeKind.LIQUIDATION, "Y 的最后一笔是强平");
            // ④ 强平之后偿付检查才减仓 Z
            PlayerTrade zAdl = TradingGameTests.last(book.trades(zId));
            helper.assertValueEqual(zAdl.kind(), TradeKind.ADL, "Z 的最后一笔是自动减仓");
            helper.assertTrue(book.poolEquity(LIVE_MARKS).units() >= 0, "减仓之后池子权益回到非负");

            // 编号是全局递增的，说明三件事确实按 ① → ③ → ④ 的顺序发生
            helper.assertTrue(xClose.id() < yLiquidation.id() && yLiquidation.id() < zAdl.id(),
                    "成交编号顺序：止盈 " + xClose.id() + " < 强平 " + yLiquidation.id() + " < ADL " + zAdl.id());
        } finally {
            TradingGameTests.finish(x);
            TradingGameTests.finish(y);
            TradingGameTests.finish(z);
            // Z 剩下的浮盈平仓时由池子支付；把池子补回测试前的数额，共用账本里别的测试才开得出仓
            Money missing = poolBefore.minus(ServerTrading.poolBalance());
            if (missing.isPositive()) {
                ServerTrading.fundPool(missing);
            }
        }
        helper.succeed();
    }
}
