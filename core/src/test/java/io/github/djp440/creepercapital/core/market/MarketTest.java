package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketTest {
    private static final String DIAMOND = "DIAMONDCRP";
    private static final String GUNPOWDER = "GUNPOWDERCRP";

    private static Market market(long seed) {
        return new Market(seed, ContractSpec.defaults());
    }

    @Test
    @DisplayName("新市场停在第 0 个行情步，两个合约各在自己的起始指数价格：DIAMONDCRP 1000，GUNPOWDERCRP 100")
    void freshMarketStartsAtStepZeroWithInitialIndexPrices() {
        Market market = market(42L);

        assertEquals(List.of(DIAMOND, GUNPOWDER), market.symbols());
        MarketSnapshot diamond = market.snapshot(DIAMOND);
        assertEquals(0L, diamond.step());
        assertEquals(DIAMOND, diamond.symbol());
        assertEquals(1000.0, diamond.indexPrice(), 1e-9);
        assertEquals(100.0, market.snapshot(GUNPOWDER).indexPrice(), 1e-9);
        assertEquals(market.snapshots(), List.of(diamond, market.snapshot(GUNPOWDER)));
    }

    @Test
    @DisplayName("每 20 个服务器 tick 推进一个行情步：前 19 个 tick 不推进，第 20 个同时推进所有合约到第 1 步")
    void advancesOneMarketStepEveryTwentyTicks() {
        Market market = market(42L);

        for (int tick = 1; tick <= 19; tick++) {
            assertTrue(market.tick().isEmpty(), "第 " + tick + " 个 tick 不应推进行情步");
        }
        List<MarketSnapshot> stepped = market.tick();

        assertEquals(List.of(DIAMOND, GUNPOWDER), stepped.stream().map(MarketSnapshot::symbol).toList());
        assertTrue(stepped.stream().allMatch(snapshot -> snapshot.step() == 1L), "所有合约共用同一个行情步");
        assertEquals(1L, market.snapshot(DIAMOND).step());
        assertEquals(1L, market.snapshot(GUNPOWDER).step());
    }

    @Test
    @DisplayName("指数价格每个行情步都在随机游走，同一种子走出同一条路径，两个合约的路径互不相同")
    void indexPriceWalksEveryStepAndIsReproducibleBySeed() {
        List<Double> path = indexPricePath(market(7L), DIAMOND, 100);

        assertEquals(path, indexPricePath(market(7L), DIAMOND, 100));
        assertNotEquals(path, indexPricePath(market(8L), DIAMOND, 100));
        assertEquals(100, new HashSet<>(path).size(), "每个行情步的指数价格都应变化");
        assertNotEquals(
                indexPricePath(market(7L), DIAMOND, 100).stream().map(price -> price / 1000.0).toList(),
                indexPricePath(market(7L), GUNPOWDER, 100).stream().map(price -> price / 100.0).toList(),
                "两个合约用各自的随机数流，不是同一条路径的缩放");
    }

    @Test
    @DisplayName("一个市场日（14400 个行情步）内两个合约的指数价格始终为正")
    void indexPriceStaysPositiveForAMarketDay() {
        Market market = market(42L);
        for (int i = 0; i < 14_400; i++) {
            for (MarketSnapshot snapshot : stepAll(market)) {
                assertTrue(snapshot.indexPrice() > 0, snapshot.symbol() + " 指数价格不应小于等于 0");
            }
        }
    }

    @Test
    @DisplayName("走过 N 个行情步后，市场时间正好前进 6N 市场秒")
    void marketTimeAdvancesSixSecondsPerStep() {
        Market market = market(42L);
        assertEquals(0L, market.snapshot(DIAMOND).time().totalSeconds());

        for (int steps = 1; steps <= 250; steps++) {
            stepOnce(market);
            assertEquals(6L * steps, market.snapshot(DIAMOND).time().totalSeconds());
        }
    }

    @Test
    @DisplayName("行情步之间的 tick 不让市场时间前进")
    void marketTimeDoesNotMoveBetweenSteps() {
        Market market = market(42L);
        for (int tick = 1; tick <= 19; tick++) {
            market.tick();
        }

        assertEquals(0L, market.snapshot(DIAMOND).time().totalSeconds());
    }

    @Test
    @DisplayName("暂停期间行情步、价格和市场时间都不动（所有合约一起）；恢复后接着走")
    void pausedMarketIsFrozenUntilResumed() {
        Market market = market(42L);
        for (int i = 0; i < 3; i++) {
            stepOnce(market);
        }
        List<MarketSnapshot> before = market.snapshots();

        market.pause();
        for (int tick = 0; tick < 100; tick++) {
            assertTrue(market.tick().isEmpty(), "暂停时不应产生行情步");
        }
        assertTrue(market.isPaused());
        assertEquals(before, market.snapshots());

        market.resume();
        assertFalse(market.isPaused());
        assertEquals(4L, stepOnce(market).step());
    }

    @Test
    @DisplayName("暂停时 tick 计数也冻结：恢复后仍要攒满 20 个 tick 才走下一步")
    void pausedTicksDoNotAccumulate() {
        Market market = market(42L);
        for (int tick = 0; tick < 10; tick++) {
            market.tick();
        }
        market.pause();
        for (int tick = 0; tick < 100; tick++) {
            market.tick();
        }
        market.resume();

        for (int tick = 1; tick <= 9; tick++) {
            assertTrue(market.tick().isEmpty(), "恢复后第 " + tick + " 个 tick 不应推进");
        }
        assertEquals(1L, market.tick().get(0).step());
    }

    @Test
    @DisplayName("保存的状态恢复后，市场时间和每个合约的价格与保存时一致")
    void restoredMarketMatchesSavedSnapshot() {
        Market original = market(42L);
        for (int i = 0; i < 123; i++) {
            stepOnce(original);
        }

        Market restored = Market.restore(original.state(), ContractSpec.defaults());

        assertEquals(original.snapshots(), restored.snapshots());
    }

    @Test
    @DisplayName("不补算：恢复后的第一个行情步紧接保存前的最后一个行情步，之后与未中断的市场完全一致（两个合约）")
    void restoredMarketContinuesWithoutCatchUp() {
        Market uninterrupted = market(42L);
        Market interrupted = market(42L);
        for (int i = 0; i < 50; i++) {
            stepAll(uninterrupted);
            stepAll(interrupted);
        }

        Market restored = Market.restore(interrupted.state(), ContractSpec.defaults());

        List<MarketSnapshot> first = stepAll(restored);
        assertEquals(51L, first.get(0).step());
        assertEquals(6L * 51, first.get(0).time().totalSeconds());
        assertEquals(stepAll(uninterrupted), first);
        for (int i = 0; i < 200; i++) {
            assertEquals(stepAll(uninterrupted), stepAll(restored));
        }
    }

    @Test
    @DisplayName("模型的全部状态随存档持久化：跑了很久（GARCH、市场状态、基本面都已偏离初值）再恢复，行情逐步复现")
    void everyPieceOfModelStateSurvivesSaveAndRestore() {
        Market uninterrupted = market(2026L);
        Market interrupted = market(2026L);
        for (int i = 0; i < 40_000; i++) {
            stepAll(uninterrupted);
            stepAll(interrupted);
        }
        MarketState saved = interrupted.state();
        IndexState index = saved.contracts().get(1).index();
        assertNotEquals(IndexState.startingAt(100.0), index, "跑了这么久，模型状态应已离开初值");
        assertNotEquals(0.0, index.slowDeviation());
        assertNotEquals(1.0, index.volatilityRatio());

        Market restored = Market.restore(saved, ContractSpec.defaults());

        assertEquals(saved, restored.state());
        for (int i = 0; i < 5_000; i++) {
            assertEquals(stepAll(uninterrupted), stepAll(restored), "第 " + i + " 步");
        }
        assertEquals(uninterrupted.state(), restored.state());
    }

    @Test
    @DisplayName("在一个行情步中途保存，恢复后继续攒 tick，不重置也不补算")
    void restoredMarketKeepsPartialStepProgress() {
        Market market = market(42L);
        for (int tick = 0; tick < 7; tick++) {
            market.tick();
        }

        Market restored = Market.restore(market.state(), ContractSpec.defaults());

        for (int tick = 1; tick <= 12; tick++) {
            assertTrue(restored.tick().isEmpty(), "第 " + tick + " 个 tick 不应推进");
        }
        assertEquals(1L, restored.tick().get(0).step());
    }

    @Test
    @DisplayName("暂停状态随保存的状态一起恢复：暂停时保存，恢复后仍然冻结，resume 后接着走")
    void pausedStateSurvivesSaveAndRestore() {
        Market market = market(42L);
        for (int i = 0; i < 5; i++) {
            stepOnce(market);
        }
        market.pause();
        List<MarketSnapshot> frozen = market.snapshots();

        Market restored = Market.restore(market.state(), ContractSpec.defaults());

        assertTrue(restored.isPaused());
        for (int tick = 0; tick < 100; tick++) {
            assertTrue(restored.tick().isEmpty(), "恢复后仍应处于暂停");
        }
        assertEquals(frozen, restored.snapshots());
        restored.resume();
        assertEquals(6L, stepOnce(restored).step());
    }

    @Test
    @DisplayName("未暂停的市场保存、恢复后仍在运行")
    void runningMarketStaysRunningAfterRestore() {
        Market restored = Market.restore(market(42L).state(), ContractSpec.defaults());

        assertFalse(restored.isPaused());
        assertEquals(1L, stepOnce(restored).step());
    }

    @Test
    @DisplayName("种子和步数相同时，价格序列可以复现")
    void sameSeedAndStepsGiveSamePriceSequence() {
        assertEquals(indexPricePath(market(2026L), GUNPOWDER, 500), indexPricePath(market(2026L), GUNPOWDER, 500));
    }

    @Test
    @DisplayName("两边行情互不影响：去掉别的合约、调换顺序、改别的合约的波动率倾向，这个合约的走势都分毫不差")
    void contractsDoNotInfluenceEachOther() {
        ContractSpec diamond = ContractSpec.defaults().get(0);
        ContractSpec gunpowder = ContractSpec.defaults().get(1);
        ContractSpec wildDiamond = new ContractSpec(DIAMOND, diamond.underlying(), diamond.initialPrice(),
                diamond.baseVolume(), new VolatilityProfile(0.09, 1, 0.03, 0.08, 0.004));

        List<Double> together = indexPricePath(new Market(9L, List.of(diamond, gunpowder)), GUNPOWDER, 3_000);
        List<Double> alone = indexPricePath(new Market(9L, List.of(gunpowder)), GUNPOWDER, 3_000);
        List<Double> reversed = indexPricePath(new Market(9L, List.of(gunpowder, diamond)), GUNPOWDER, 3_000);
        List<Double> neighbourChanged = indexPricePath(new Market(9L, List.of(wildDiamond, gunpowder)), GUNPOWDER, 3_000);

        assertEquals(together, alone);
        assertEquals(together, reversed);
        assertEquals(together, neighbourChanged);
    }

    @Test
    @DisplayName("新市场没有溢价：每个合约的最新价格 = 标记价格 = 指数价格，资金费率为利率 0.01%，正处在结算点")
    void freshMarketHasNoPremium() {
        for (MarketSnapshot snapshot : market(42L).snapshots()) {
            assertEquals(snapshot.indexPrice(), snapshot.lastPrice(), 1e-9, snapshot.symbol());
            assertEquals(snapshot.indexPrice(), snapshot.markPrice(), 1e-9, snapshot.symbol());
            assertEquals(0.0001, snapshot.fundingRate(), 1e-12, snapshot.symbol());
            assertEquals(0L, snapshot.secondsUntilFunding(), snapshot.symbol());
        }
    }

    @Test
    @DisplayName("每个行情步最新价格都偏离指数价格，偏离的溢价每步都在变")
    void lastPriceCarriesAChangingPremium() {
        Market market = market(7L);
        Set<Double> premiums = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            premiums.add(premium(stepOnce(market)));
        }

        assertEquals(100, premiums.size(), "每个行情步的溢价都应变化");
    }

    @Test
    @DisplayName("统计：30 个市场日内溢价始终在 ±1% 内，前后半程的均方根都不超过 0.5%，不会无限漂移（两个合约）")
    void premiumStaysBoundedInTheLongRun() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            Market market = market(2026L);
            int steps = 30 * 14_400;
            double[] premiums = new double[steps];
            for (int i = 0; i < steps; i++) {
                premiums[i] = premium(stepAll(market).get(market.symbols().indexOf(symbol)));
            }

            double maxAbs = Arrays.stream(premiums).map(Math::abs).max().orElseThrow();
            double firstHalf = rootMeanSquare(premiums, 0, steps / 2);
            double secondHalf = rootMeanSquare(premiums, steps / 2, steps);

            assertTrue(maxAbs < 0.01, symbol + " 溢价应始终在 ±1% 内，实际最大 " + maxAbs);
            // 溢价的均方根里含着市场状态带来的偏置（至多 0.3%），所以只能要求有界，不能要求前后相等
            assertTrue(firstHalf < 0.005 && secondHalf < 0.005,
                    symbol + " 溢价不应越走越散：前半程 " + firstHalf + "，后半程 " + secondHalf);
        }
    }

    @Test
    @DisplayName("资金费率倒计时按市场时间倒数：每步少 6 秒，第 4800 步（08:00:00）归零，下一步从 28794 秒重新开始")
    void fundingCountdownFollowsMarketTime() {
        Market market = market(42L);
        assertEquals(28_794L, stepOnce(market).secondsUntilFunding());
        assertEquals(28_788L, stepOnce(market).secondsUntilFunding());
        MarketSnapshot snapshot = null;
        for (int i = 3; i <= 4_800; i++) {
            snapshot = stepOnce(market);
        }

        assertEquals(0L, snapshot.secondsUntilFunding());
        assertEquals(28_794L, stepOnce(market).secondsUntilFunding());
    }

    @Test
    @DisplayName("结算后开始新一期：结算点后的第一步，资金费率只由这一步的溢价算出，不混入上一期的采样")
    void newFundingPeriodStartsWithFreshSamples() {
        Market market = market(5L);
        for (int i = 0; i < 4_800; i++) {
            stepOnce(market);
        }

        MarketSnapshot first = stepOnce(market);

        assertEquals(Funding.rate(premium(first)), first.fundingRate(), 1e-12);
    }

    @Test
    @DisplayName("跨过结算点前后保存、恢复，溢价、资金费率与标记价格都和没停过的市场一致")
    void restoredPricingContinuesAcrossSettlement() {
        Market uninterrupted = market(99L);
        Market interrupted = market(99L);
        for (int i = 0; i < 4_795; i++) {
            stepAll(uninterrupted);
            stepAll(interrupted);
        }

        Market restored = Market.restore(interrupted.state(), ContractSpec.defaults());

        assertEquals(interrupted.state(), restored.state());
        for (int i = 0; i < 40; i++) {
            assertEquals(stepAll(uninterrupted), stepAll(restored));
        }
    }

    @Test
    @DisplayName("配置里去掉的合约状态原样留在存档里，重新加回后接着走；新加的合约从起始价格开始")
    void contractsRemovedFromTheConfigKeepTheirStateAndNewOnesStartFresh() {
        List<ContractSpec> both = ContractSpec.defaults();
        Market market = new Market(11L, both);
        for (int i = 0; i < 3_000; i++) {
            stepAll(market);
        }
        MarketState saved = market.state();
        ContractState savedGunpowder = saved.contracts().get(1);

        Market onlyDiamond = Market.restore(saved, List.of(both.get(0)));
        for (int i = 0; i < 100; i++) {
            stepOnce(onlyDiamond);
        }

        assertEquals(List.of(DIAMOND), onlyDiamond.symbols());
        assertEquals(savedGunpowder, onlyDiamond.state().contracts().get(1), "去掉的合约状态不被推进、不被丢弃");

        Market readded = Market.restore(onlyDiamond.state(), both);
        assertEquals(List.of(DIAMOND, GUNPOWDER), readded.symbols());
        assertEquals(Math.exp(savedGunpowder.index().logFundamental() + savedGunpowder.index().slowDeviation()
                + savedGunpowder.index().jumpDeviation()), readded.snapshot(GUNPOWDER).indexPrice(), 1e-9,
                "重新加回的合约从它停下的价格接着走");
        assertEquals(3_100L, readded.snapshot(GUNPOWDER).step(), "市场时间是共用的，期间没有补算");

        ContractSpec blaze = new ContractSpec("BLAZERODCRP", "minecraft:blaze_rod", 250.0, 10.0,
                new VolatilityProfile(0.05, 0.5, 0.04, 0.12, 0.002));
        Market withNewcomer = Market.restore(saved, List.of(both.get(0), both.get(1), blaze));
        assertEquals(250.0, withNewcomer.snapshot("BLAZERODCRP").indexPrice(), 1e-9);
        assertEquals(3_000L, withNewcomer.snapshot("BLAZERODCRP").step());
        assertEquals(saved.contracts().get(0), withNewcomer.state().contracts().get(0), "老合约不受影响");
    }

    @Test
    @DisplayName("市场至少要有一个合约，交易代码不能重复")
    void marketNeedsDistinctContracts() {
        assertThrows(IllegalArgumentException.class, () -> new Market(1L, List.of()));
        ContractSpec diamond = ContractSpec.defaults().get(0);
        assertThrows(IllegalArgumentException.class, () -> new Market(1L, List.of(diamond, diamond)));
    }

    @Test
    @DisplayName("手动跳跃：排队后下一个行情步里该合约价格跳变约指定幅度，发出 MANUAL 事件；另一个合约完全不受影响")
    void queuedJumpMovesOnlyThatContractOnTheNextStep() {
        Market jumping = market(21L);
        Market control = market(21L);
        for (int i = 0; i < 30; i++) {
            stepAll(jumping);
            stepAll(control);
        }
        List<JumpEvent> events = new ArrayList<>();
        jumping.addJumpListener(events::add);
        double before = jumping.snapshot(GUNPOWDER).indexPrice();

        jumping.queueJump(GUNPOWDER, 0.10);
        assertEquals(before, jumping.snapshot(GUNPOWDER).indexPrice(), "排队时价格还没动");
        List<MarketSnapshot> after = stepAll(jumping);
        List<MarketSnapshot> controlled = stepAll(control);

        double moved = after.get(1).indexPrice() / before - 1;
        assertEquals(0.10, moved, 0.02, "跳跃约 +10%（另含这一步的日常波动）");
        assertEquals(controlled.get(0), after.get(0), "钻石完全不受影响");
        assertEquals(1, events.size());
        JumpEvent event = events.get(0);
        assertEquals(GUNPOWDER, event.symbol());
        assertEquals(31L, event.step());
        assertEquals(JumpEvent.Cause.MANUAL, event.cause());
        assertEquals(0.10, event.changeRatio(), 1e-9);
        assertTrue(event.isUp());
    }

    @Test
    @DisplayName("手动跳跃可以向下：-20% 之后价格下跌约 20%")
    void queuedJumpCanGoDown() {
        Market market = market(21L);
        double before = market.snapshot(DIAMOND).indexPrice();
        List<JumpEvent> events = new ArrayList<>();
        market.addJumpListener(events::add);

        market.queueJump(DIAMOND, -0.20);
        MarketSnapshot after = stepOnce(market);

        assertEquals(-0.20, after.indexPrice() / before - 1, 0.01);
        assertEquals(-0.20, events.get(0).changeRatio(), 1e-9);
        assertFalse(events.get(0).isUp());
    }

    @Test
    @DisplayName("排队的跳跃随存档保存：保存后恢复，下一步照样生效且只生效一次")
    void queuedJumpSurvivesSaveAndRestore() {
        Market market = market(21L);
        stepAll(market);
        market.queueJump(DIAMOND, 0.15);
        Market restored = Market.restore(market.state(), ContractSpec.defaults());
        List<JumpEvent> events = new ArrayList<>();
        restored.addJumpListener(events::add);

        stepAll(restored);
        stepAll(restored);

        List<JumpEvent> manual = events.stream().filter(e -> e.cause() == JumpEvent.Cause.MANUAL).toList();
        assertEquals(1, manual.size());
        assertEquals(0.15, manual.get(0).changeRatio(), 1e-9);
    }

    @Test
    @DisplayName("暂停时排队的跳跃按兵不动，恢复后才生效")
    void queuedJumpWaitsWhilePaused() {
        Market market = market(21L);
        List<JumpEvent> events = new ArrayList<>();
        market.addJumpListener(events::add);
        market.pause();

        market.queueJump(DIAMOND, 0.10);
        for (int tick = 0; tick < 100; tick++) {
            market.tick();
        }
        assertTrue(events.isEmpty());

        market.resume();
        stepOnce(market);
        assertEquals(1, events.stream().filter(e -> e.cause() == JumpEvent.Cause.MANUAL).count());
    }

    @Test
    @DisplayName("手动跳跃拒绝没有的合约和不合法的幅度（-100% 及以下、非数）")
    void queuedJumpRejectsNonsense() {
        Market market = market(21L);

        assertThrows(IllegalArgumentException.class, () -> market.queueJump("NOSUCHCRP", 0.1));
        assertThrows(IllegalArgumentException.class, () -> market.queueJump(DIAMOND, -1.0));
        assertThrows(IllegalArgumentException.class, () -> market.queueJump(DIAMOND, -3.0));
        assertThrows(IllegalArgumentException.class, () -> market.queueJump(DIAMOND, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> market.queueJump(DIAMOND, Double.POSITIVE_INFINITY));
    }

    private static double premium(MarketSnapshot snapshot) {
        return snapshot.lastPrice() / snapshot.indexPrice() - 1;
    }

    private static double rootMeanSquare(double[] values, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += values[i] * values[i];
        }
        return Math.sqrt(sum / (to - from));
    }

    /** 推进到下一个行情步，返回所有合约的快照。 */
    private static List<MarketSnapshot> stepAll(Market market) {
        for (int tick = 0; tick < Market.TICKS_PER_STEP; tick++) {
            List<MarketSnapshot> stepped = market.tick();
            if (!stepped.isEmpty()) {
                return stepped;
            }
        }
        throw new AssertionError("20 个 tick 内应当产生一个行情步");
    }

    /** 推进到下一个行情步，返回第一个合约（DIAMONDCRP）的快照。 */
    private static MarketSnapshot stepOnce(Market market) {
        return stepAll(market).get(0);
    }

    private static List<Double> indexPricePath(Market market, String symbol, int steps) {
        int index = market.symbols().indexOf(symbol);
        List<Double> prices = new ArrayList<>();
        while (prices.size() < steps) {
            List<MarketSnapshot> stepped = market.tick();
            if (!stepped.isEmpty()) {
                prices.add(stepped.get(index).indexPrice());
            }
        }
        return prices;
    }
}
