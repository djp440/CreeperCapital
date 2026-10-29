package io.github.djp440.creepercapital.gametest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.trading.CancelReason;
import io.github.djp440.creepercapital.core.trading.LiquidationEvent;
import io.github.djp440.creepercapital.core.trading.MarginMath;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderStatus;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.network.AccountPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.network.TradingStatePayload;
import io.github.djp440.creepercapital.server.ServerAccounts;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerTrading;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * 强平与风险保障基金的 GameTest：服务端接线、通知、成交历史里的强平记录，以及离线玩家也会被强平。
 * 结算金额、穿仓分担、全仓 / 逐仓细节和资金守恒在 core 的 {@code LiquidationTest}。
 * <p>
 * 所有 GameTest 共用一个活账本，注入极端价格会误伤别的测试的仓位，所以两种办法：
 * 直接调 {@link ServerTrading#liquidate}（限定只检查本测试的玩家、同一 tick 内做完）；
 * 或用「假快照」以 2 倍的价格开仓，让活市场的真实标记价格（约一半）在下一个行情步自然触发强平。
 * 活市场的价格、数量精度、杠杆上限都是配置决定的，所以手数与杠杆按当前合约现算。
 */
final class LiquidationGameTests {
    private static final String DIAMOND = "DIAMONDCRP";

    private LiquidationGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("liquidation_at_the_displayed_price_goes_to_the_fund_and_cancels_orders",
                LiquidationGameTests::liquidationAtDisplayedPrice);
        function.accept("market_step_liquidates_online_players_and_notifies_them", LiquidationGameTests::marketStepLiquidatesOnlinePlayer);
        function.accept("market_step_liquidates_offline_cross_positions", LiquidationGameTests::marketStepLiquidatesOfflineCrossPosition);
    }

    private static ContractSpec spec() {
        return ServerMarket.spec(DIAMOND);
    }

    /** 半档的数量：名义价值不大，不会碰到分档的仓位上限。 */
    private static long lots() {
        return Math.max(1, spec().liquidityCurve(ServerMarket.snapshot(DIAMOND).lastPrice()).levelLots() / 2);
    }

    private static int leverage() {
        return Math.min(10, spec().maxLeverage());
    }

    private static Money systemTotal(UUID player) {
        TradingBook book = ServerTrading.book();
        Money total = book.pool().balance().plus(book.fund().balance()).plus(ServerAccounts.balance(player))
                .plus(book.orders().frozen(player));
        for (Position position : book.positions(player)) {
            total = total.plus(position.margin());
        }
        return total;
    }

    /** 以 2 倍于活市场的价格开仓：活市场的真实价格只有开仓价的一半，下一个行情步必然触发强平。 */
    private static Position openAtDoublePrice(GameTestHelper helper, UUID player, Side side, long lots, int leverage) {
        MarketSnapshot live = ServerMarket.snapshot(DIAMOND);
        MarketSnapshot fake = new MarketSnapshot(live.step(), DIAMOND, live.indexPrice() * 2, live.lastPrice() * 2,
                live.markPrice() * 2, live.fundingRate(), 0);
        ServerTrading.book().open(player, spec(), fake, side, lots, leverage);
        helper.assertTrue(ServerTrading.book().position(player, DIAMOND).isPresent(), "假快照开仓应成功");
        return ServerTrading.book().position(player, DIAMOND).orElseThrow();
    }

    /**
     * 标记价格刚越过仓位页展示的强平价：逐仓多头被强平，按这个标记价格成交（不走曲线、没有滑点），
     * 剩余保证金（含清算费）进风险保障基金，亏损归池子，玩家、池子、基金的总额不变；同合约的挂单随之撤销，
     * 冻结资金退回；玩家收到「已强平」和「挂单已撤销」的通知，成交历史里是一笔强平。
     */
    private static void liquidationAtDisplayedPrice(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "LiqTrader1", "100000");
        try {
            UUID id = player.getUUID();
            TradingBook book = ServerTrading.book();
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(), leverage()));
            helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(), "open_long",
                    "开仓成功");
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(), leverage(),
                    OrderPayload.Execution.LIMIT, Math.floor(ServerMarket.snapshot(DIAMOND).lastPrice() * 0.5)));
            helper.assertValueEqual(book.orders().open(id).size(), 1, "另挂一张限价买单");

            Position position = book.position(id, DIAMOND).orElseThrow();
            double displayed = MarginMath.assess(book.positions(id),
                    book.assets(id, symbol -> ServerMarket.snapshot(symbol).markPrice()).walletBalance(),
                    symbol -> ServerMarket.snapshot(symbol).markPrice(), symbol -> ServerMarket.spec(symbol).marginTiers())
                    .get(DIAMOND).liquidationPrice().orElseThrow();
            double safe = displayed * (1 + 1e-6);
            double hit = displayed * (1 - 1e-6);
            Money frozen = book.orders().frozen(id);
            Money freeBefore = ServerAccounts.balance(id);
            Money totalBefore = systemTotal(id);
            Money fundBefore = book.fund().balance();
            Money poolBefore = book.pool().balance();
            long seconds = ServerMarket.snapshot(DIAMOND).time().totalSeconds();
            Set<UUID> touched = new LinkedHashSet<>();

            helper.assertTrue(ServerTrading.liquidate(helper.getLevel().getServer(), seconds,
                    symbol -> DIAMOND.equals(symbol) ? safe : Double.NaN, other -> other.equals(id), touched).isEmpty(),
                    "标记价格在展示的强平价之内，不强平");
            List<LiquidationEvent> events = ServerTrading.liquidate(helper.getLevel().getServer(), seconds,
                    symbol -> DIAMOND.equals(symbol) ? hit : Double.NaN, other -> other.equals(id), touched);

            helper.assertValueEqual(events.size(), 1, "越过展示的强平价就强平");
            LiquidationEvent event = events.get(0);
            helper.assertValueEqual(event.mode(), MarginMode.ISOLATED, "逐仓");
            helper.assertValueEqual(event.positions().get(0).price(), hit, "按触发强平的标记价格成交");
            helper.assertTrue(book.position(id, DIAMOND).isEmpty(), "仓位已被强平");
            PlayerTrade trade = TradingGameTests.last(book.trades(id));
            helper.assertValueEqual(trade.kind(), TradeKind.LIQUIDATION, "成交历史里是强平记录");
            helper.assertValueEqual(trade.price(), hit, "强平成交价 = 标记价格");
            helper.assertValueEqual(trade.lots(), position.lots(), "强平全部数量");
            helper.assertTrue(trade.fee().isPositive(), "收清算费");
            helper.assertTrue(trade.fee().compareTo(event.toFund()) <= 0, "清算费包含在流入基金的金额里");
            helper.assertValueEqual(event.shortfall(), Money.ZERO, "还没亏穿，不穿仓");
            helper.assertTrue(event.toFund().isPositive(), "剩余保证金进基金");
            helper.assertValueEqual(book.fund().balance(), fundBefore.plus(event.toFund()), "风险保障基金收到剩余保证金（含清算费）");
            helper.assertValueEqual(book.pool().balance(), poolBefore.plus(position.margin().minus(event.toFund())),
                    "亏损归流动性池");
            helper.assertValueEqual(systemTotal(id), totalBefore, "玩家 + 池子 + 基金的总额不变");
            helper.assertValueEqual(ServerAccounts.balance(id), freeBefore.plus(frozen), "挂单撤销，冻结资金退回可用余额");

            helper.assertTrue(book.orders().open(id).isEmpty(), "同合约的挂单随强平撤销");
            Order cancelled = book.orders().history(id).get(0);
            helper.assertValueEqual(cancelled.status(), OrderStatus.CANCELLED, "挂单状态");
            helper.assertValueEqual(cancelled.cancelReason(), CancelReason.LIQUIDATED, "撤销原因是强平");
            helper.assertTrue(touched.contains(id), "强平的玩家记入需要刷新状态的集合");
            List<OrderResultPayload> notices = MockClient.received(player, OrderResultPayload.class);
            OrderResultPayload liquidated = notices.get(notices.size() - 2);
            helper.assertValueEqual(liquidated.code(), "liquidated", "玩家收到已强平通知");
            helper.assertFalse(liquidated.success(), "强平通知不是成功提示");
            helper.assertValueEqual(liquidated.args().get(0), DIAMOND, "通知里带着被强平的合约");
            helper.assertValueEqual(TradingGameTests.last(notices).code(), "order_cancelled_liquidated", "挂单随强平撤销的通知");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /**
     * 在线玩家用 2 倍价格开多（保证金率一开仓就越线），行情步的强平阶段自动强平：仓位清空，成交历史里是强平，
     * 玩家收到「已强平」通知、更新后的交易状态和资产；亏损超过保证金（穿仓）时玩家余额不会为负。
     */
    private static void marketStepLiquidatesOnlinePlayer(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "LiqTrader2", "100000");
        UUID id = player.getUUID();
        Position opened = openAtDoublePrice(helper, id, Side.LONG, lots(), leverage());
        Money freeAfterOpen = ServerAccounts.balance(id);

        helper.succeedWhen(() -> {
            helper.assertTrue(ServerTrading.book().position(id, DIAMOND).isEmpty(), "行情步应当强平越线的仓位");
            try {
                PlayerTrade trade = TradingGameTests.last(ServerTrading.book().trades(id));
                helper.assertValueEqual(trade.kind(), TradeKind.LIQUIDATION, "成交历史里是强平记录");
                helper.assertValueEqual(trade.side(), Side.LONG, "强平的是多头");
                helper.assertTrue(trade.price() < opened.entryPrice() * 0.75, "按活市场的标记价格成交，约为开仓价的一半");
                helper.assertValueEqual(trade.lots(), opened.lots(), "全部数量");
                helper.assertValueEqual(ServerAccounts.balance(id), freeAfterOpen, "逐仓强平不动可用余额，余额不会为负");

                List<OrderResultPayload> notices = MockClient.received(player, OrderResultPayload.class);
                helper.assertTrue(notices.stream().anyMatch(n -> n.code().equals("liquidated") && !n.success()), "玩家收到已强平通知");
                TradingStatePayload state = TradingGameTests.last(MockClient.received(player, TradingStatePayload.class));
                helper.assertTrue(state.positions().isEmpty(), "同步的交易状态里仓位已清空");
                helper.assertValueEqual(TradingGameTests.last(state.trades()).kind(), TradeKind.LIQUIDATION, "同步的成交历史含强平记录");
                AccountPayload account = TradingGameTests.last(MockClient.received(player, AccountPayload.class));
                helper.assertValueEqual(account.assets().walletBalance(), ServerAccounts.balance(id), "同步的资产里没有保证金了");
            } finally {
                TradingGameTests.finish(player);
            }
        });
    }

    /**
     * 离线玩家的全仓仓位同样被强平：存款只比保证金多一点，2 倍价格开多后全仓钱包为负（亏损远超余额），
     * 下一个行情步强平，全仓抵押（含全部可用余额）归零，亏损超出的部分是穿仓，玩家余额不会为负。
     */
    private static void marketStepLiquidatesOfflineCrossPosition(GameTestHelper helper) {
        ServerPlayer player = MockClient.join(helper, "LiqTrader3");
        UUID id = player.getUUID();
        long lots = lots();
        int leverage = leverage();
        double fakePrice = ServerMarket.snapshot(DIAMOND).lastPrice() * 2;
        BigDecimal notional = BigDecimal.valueOf(fakePrice).multiply(BigDecimal.valueOf(lots))
                .movePointLeft(spec().quantityDecimals());
        Money deposit = Money.parse(notional.divide(BigDecimal.valueOf(leverage), 6, RoundingMode.UP)
                .multiply(new BigDecimal("1.2")).add(BigDecimal.TEN).setScale(6, RoundingMode.UP).toPlainString());
        ServerAccounts.deposit(helper.getLevel().getServer(), id, deposit);
        ServerTrading.book().setMarginMode(id, DIAMOND, MarginMode.CROSS);
        Position opened = openAtDoublePrice(helper, id, Side.LONG, lots, leverage);
        helper.assertValueEqual(opened.mode(), MarginMode.CROSS, "全仓开仓");
        MockClient.leave(player);

        helper.succeedWhen(() -> {
            helper.assertTrue(ServerTrading.book().position(id, DIAMOND).isEmpty(), "离线玩家的全仓仓位也应被强平");
            PlayerTrade trade = TradingGameTests.last(ServerTrading.book().trades(id));
            helper.assertValueEqual(trade.kind(), TradeKind.LIQUIDATION, "成交历史里是强平记录");
            helper.assertValueEqual(ServerAccounts.balance(id), Money.ZERO, "全仓强平后全部抵押归零，余额不会为负");
            helper.assertTrue(ServerTrading.book().orders().open(id).isEmpty(), "没有残留挂单");
            ServerTrading.book().setMarginMode(id, DIAMOND, MarginMode.ISOLATED);
        });
    }
}
