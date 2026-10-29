package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.trading.LiquidityPool;

/** 模拟成交：期望值按内置合约手算（钻石点差 0.5、3 位精度；火药点差 0.05、0 位精度）。 */
class SimulatedTradesTest {
    private static final ContractSpec DIAMOND = ContractSpec.defaults().get(0);
    private static final ContractSpec GUNPOWDER = ContractSpec.defaults().get(1);
    private static final long SEED = 20260101L;

    private static MarketSnapshot snapshot(ContractSpec spec, long step, double last, double volume) {
        return new MarketSnapshot(step, spec.symbol(), last, last, last, 0.0001, volume);
    }

    @Test
    @DisplayName("一个行情步内所有模拟成交的数量之和精确等于该步的成交量：钻石 5.0 个 = 5000 手，火药 50.4 个 = 50 手")
    void lotsOfOneStepSumToTheStepVolume() {
        for (long step = 1; step <= 300; step++) {
            List<MarketTrade> diamond = SimulatedTrades.of(SEED, DIAMOND, snapshot(DIAMOND, step, 1000.0, 5.0), 1000.0);
            List<MarketTrade> gunpowder = SimulatedTrades.of(SEED, GUNPOWDER, snapshot(GUNPOWDER, step, 100.0, 50.4), 100.0);

            assertEquals(5000L, diamond.stream().mapToLong(MarketTrade::lots).sum(), "钻石第 " + step + " 步");
            assertEquals(50L, gunpowder.stream().mapToLong(MarketTrade::lots).sum(), "火药第 " + step + " 步");
        }
    }

    @Test
    @DisplayName("每笔至少 1 手，一步拆成 2 到 8 笔；成交量不足以拆时笔数随之减少，成交量为 0 时没有成交")
    void everyTradeHasLotsAndSmallVolumesSplitIntoFewerTrades() {
        for (long step = 1; step <= 300; step++) {
            List<MarketTrade> trades = SimulatedTrades.of(SEED, DIAMOND, snapshot(DIAMOND, step, 1000.0, 5.0), 1000.0);
            assertTrue(trades.size() >= 2 && trades.size() <= 8, "笔数 " + trades.size());
            assertTrue(trades.stream().allMatch(trade -> trade.lots() >= 1));

            // 0.0016 个 = 2 手：最多两笔，数量之和仍是 2
            List<MarketTrade> tiny = SimulatedTrades.of(SEED, DIAMOND, snapshot(DIAMOND, step, 1000.0, 0.0016), 1000.0);
            assertTrue(tiny.size() >= 1 && tiny.size() <= 2);
            assertEquals(2L, tiny.stream().mapToLong(MarketTrade::lots).sum());
        }
        assertTrue(SimulatedTrades.of(SEED, DIAMOND, snapshot(DIAMOND, 1, 1000.0, 0.0), 1000.0).isEmpty());
        assertTrue(SimulatedTrades.of(SEED, DIAMOND, snapshot(DIAMOND, 1, 1000.0, 0.0004), 1000.0).isEmpty());
    }

    @Test
    @DisplayName("成交价落在点差范围内：买单在 [最新价, 最新价 + 半个点差]，卖单在 [最新价 - 半个点差, 最新价]")
    void pricesStayInsideTheSpread() {
        for (long step = 1; step <= 300; step++) {
            for (MarketTrade trade : SimulatedTrades.of(SEED, DIAMOND, snapshot(DIAMOND, step, 1000.0, 5.0), 1000.0)) {
                if (trade.buy()) {
                    assertTrue(trade.price() >= 1000.0 && trade.price() <= 1000.25 + 1e-9, "买价 " + trade.price());
                } else {
                    assertTrue(trade.price() >= 999.75 - 1e-9 && trade.price() <= 1000.0, "卖价 " + trade.price());
                }
            }
            for (MarketTrade trade : SimulatedTrades.of(SEED, GUNPOWDER, snapshot(GUNPOWDER, step, 100.0, 50.0), 100.0)) {
                assertTrue(trade.price() >= 99.975 - 1e-9 && trade.price() <= 100.025 + 1e-9, "火药成交价 " + trade.price());
            }
        }
    }

    @Test
    @DisplayName("买卖方向与价格变动相关：涨的步买单占多数，跌的步卖单占多数，持平时各占约一半")
    void sideFollowsThePriceMove() {
        // 火药每步波动约 0.10/sqrt(14400)=0.083%；涨 0.1% 时买单概率约 79%，跌 0.1% 时约 21%
        double upBuyShare = buyShare(100.0, 100.1);
        double downBuyShare = buyShare(100.0, 99.9);
        double flatBuyShare = buyShare(100.0, 100.0);

        assertTrue(upBuyShare > 0.65, "上涨时买单比例 " + upBuyShare);
        assertTrue(downBuyShare < 0.35, "下跌时买单比例 " + downBuyShare);
        assertTrue(flatBuyShare > 0.42 && flatBuyShare < 0.58, "持平时买单比例 " + flatBuyShare);
    }

    /** 400 个行情步里买单的数量占总数量的比例。 */
    private static double buyShare(double previous, double last) {
        long buyLots = 0;
        long totalLots = 0;
        for (long step = 1; step <= 400; step++) {
            for (MarketTrade trade : SimulatedTrades.of(SEED, GUNPOWDER, snapshot(GUNPOWDER, step, last, 50.0), previous)) {
                totalLots += trade.lots();
                if (trade.buy()) {
                    buyLots += trade.lots();
                }
            }
        }
        return buyLots / (double) totalLots;
    }

    @Test
    @DisplayName("无状态确定：同一步永远拆出同一批；不同步、不同合约、不同种子拆出不同的批")
    void sameStepAlwaysSplitsTheSameWay() {
        MarketSnapshot step = snapshot(DIAMOND, 77, 1000.0, 5.0);

        List<MarketTrade> first = SimulatedTrades.of(SEED, DIAMOND, step, 999.9);
        assertEquals(first, SimulatedTrades.of(SEED, DIAMOND, step, 999.9));
        assertNotEquals(first, SimulatedTrades.of(SEED, DIAMOND, snapshot(DIAMOND, 78, 1000.0, 5.0), 999.9));
        assertNotEquals(first, SimulatedTrades.of(SEED + 1, DIAMOND, step, 999.9));
    }

    @Test
    @DisplayName("模拟成交带交易代码和该步的市场时间，没有玩家名")
    void tradesCarrySymbolAndMarketTimeButNoPlayer() {
        // 第 10 步 = 60 市场秒
        List<MarketTrade> trades = SimulatedTrades.of(SEED, GUNPOWDER, snapshot(GUNPOWDER, 10, 100.0, 50.0), 100.0);

        assertTrue(trades.stream().allMatch(trade -> trade.symbol().equals("GUNPOWDERCRP") && trade.marketSeconds() == 60L
                && trade.player().isEmpty() && !trade.isPlayer()));
    }

    @Test
    @DisplayName("上一步价格未知（NaN，例如刚启动或快进之后）：方向各占一半，不抛异常")
    void unknownPreviousPriceIsNeutral() {
        List<MarketTrade> trades = SimulatedTrades.of(SEED, GUNPOWDER, snapshot(GUNPOWDER, 5, 100.0, 50.0), Double.NaN);

        assertEquals(50L, trades.stream().mapToLong(MarketTrade::lots).sum());
    }

    @Test
    @DisplayName("不消耗行情自己的随机数流：生成模拟成交前后，同一种子的行情路径完全一样，存档状态也一样")
    void doesNotDisturbTheMarketRandomStream() {
        Market plain = new Market(SEED, ContractSpec.defaults());
        Market observed = new Market(SEED, ContractSpec.defaults());
        List<MarketSnapshot> previous = observed.snapshots();
        for (int step = 0; step < 200; step++) {
            plain.advanceSteps(1);
            observed.advanceSteps(1);
            for (int i = 0; i < previous.size(); i++) {
                MarketSnapshot now = observed.snapshots().get(i);
                observed.simulatedTrades(now, previous.get(i).lastPrice());
            }
            previous = observed.snapshots();
        }

        assertEquals(plain.state(), observed.state());
        assertEquals(plain.snapshots(), observed.snapshots());
    }

    @Test
    @DisplayName("模拟成交不进入任何账户、流动性池：账本与池子的余额在生成前后不变")
    void simulatedTradesLeaveAccountsAndPoolUntouched() {
        AccountBook accounts = new AccountBook();
        LiquidityPool pool = new LiquidityPool(Money.parse("1000000"));
        UUID alice = new UUID(0, 1);
        accounts.deposit(alice, Money.parse("1000"));
        Market market = new Market(SEED, ContractSpec.defaults());

        List<MarketTrade> all = new ArrayList<>();
        for (int step = 0; step < 100; step++) {
            double previous = market.snapshot("GUNPOWDERCRP").lastPrice();
            market.advanceSteps(1);
            all.addAll(market.simulatedTrades(market.snapshot("GUNPOWDERCRP"), previous));
        }

        assertTrue(all.size() >= 200);
        assertEquals(Money.parse("1000"), accounts.balance(alice));
        assertEquals(Money.parse("1000000"), pool.balance());
    }
}
