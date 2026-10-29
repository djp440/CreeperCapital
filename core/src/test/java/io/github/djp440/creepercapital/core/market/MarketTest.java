package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketTest {

    @Test
    @DisplayName("新市场停在第 0 个行情步，DIAMONDCRP 指数价格为初始价 1000")
    void freshMarketStartsAtStepZeroWithInitialIndexPrice() {
        Market market = new Market(42L);

        MarketSnapshot snapshot = market.snapshot();

        assertEquals(0L, snapshot.step());
        assertEquals("DIAMONDCRP", snapshot.symbol());
        assertEquals(1000.0, snapshot.indexPrice());
    }

    @Test
    @DisplayName("每 20 个服务器 tick 推进一个行情步：前 19 个 tick 不推进，第 20 个推进到第 1 步")
    void advancesOneMarketStepEveryTwentyTicks() {
        Market market = new Market(42L);

        for (int tick = 1; tick <= 19; tick++) {
            assertTrue(market.tick().isEmpty(), "第 " + tick + " 个 tick 不应推进行情步");
        }
        Optional<MarketSnapshot> stepped = market.tick();

        assertEquals(1L, stepped.orElseThrow().step());
        assertEquals(1L, market.snapshot().step());
    }

    @Test
    @DisplayName("指数价格每个行情步都在随机游走，同一种子走出同一条路径")
    void indexPriceWalksEveryStepAndIsReproducibleBySeed() {
        List<Double> path = indexPricePath(new Market(7L), 100);

        assertEquals(path, indexPricePath(new Market(7L), 100));
        assertNotEquals(path, indexPricePath(new Market(8L), 100));
        assertEquals(100, new HashSet<>(path).size(), "每个行情步的指数价格都应变化");
    }

    @Test
    @DisplayName("一个市场日（14400 个行情步）内指数价格始终为正")
    void indexPriceStaysPositiveForAMarketDay() {
        List<Double> path = indexPricePath(new Market(42L), 14_400);

        assertTrue(path.stream().allMatch(price -> price > 0), "指数价格不应小于等于 0");
    }

    @Test
    @DisplayName("走过 N 个行情步后，市场时间正好前进 6N 市场秒")
    void marketTimeAdvancesSixSecondsPerStep() {
        Market market = new Market(42L);
        assertEquals(0L, market.snapshot().time().totalSeconds());

        for (int steps = 1; steps <= 250; steps++) {
            stepOnce(market);
            assertEquals(6L * steps, market.snapshot().time().totalSeconds());
        }
    }

    @Test
    @DisplayName("行情步之间的 tick 不让市场时间前进")
    void marketTimeDoesNotMoveBetweenSteps() {
        Market market = new Market(42L);
        for (int tick = 1; tick <= 19; tick++) {
            market.tick();
        }

        assertEquals(0L, market.snapshot().time().totalSeconds());
    }

    @Test
    @DisplayName("暂停期间行情步、价格和市场时间都不动；恢复后接着走")
    void pausedMarketIsFrozenUntilResumed() {
        Market market = new Market(42L);
        for (int i = 0; i < 3; i++) {
            stepOnce(market);
        }
        MarketSnapshot before = market.snapshot();

        market.pause();
        for (int tick = 0; tick < 100; tick++) {
            assertTrue(market.tick().isEmpty(), "暂停时不应产生行情步");
        }
        assertTrue(market.isPaused());
        assertEquals(before, market.snapshot());

        market.resume();
        assertFalse(market.isPaused());
        assertEquals(4L, stepOnce(market).step());
    }

    @Test
    @DisplayName("暂停时 tick 计数也冻结：恢复后仍要攒满 20 个 tick 才走下一步")
    void pausedTicksDoNotAccumulate() {
        Market market = new Market(42L);
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
        assertEquals(1L, market.tick().orElseThrow().step());
    }

    @Test
    @DisplayName("保存的状态恢复后，市场时间和价格与保存时一致")
    void restoredMarketMatchesSavedSnapshot() {
        Market original = new Market(42L);
        for (int i = 0; i < 123; i++) {
            stepOnce(original);
        }

        Market restored = Market.restore(original.state());

        assertEquals(original.snapshot(), restored.snapshot());
    }

    @Test
    @DisplayName("不补算：恢复后的第一个行情步紧接保存前的最后一个行情步，之后与未中断的市场完全一致")
    void restoredMarketContinuesWithoutCatchUp() {
        Market uninterrupted = new Market(42L);
        Market interrupted = new Market(42L);
        for (int i = 0; i < 50; i++) {
            stepOnce(uninterrupted);
            stepOnce(interrupted);
        }

        Market restored = Market.restore(interrupted.state());

        MarketSnapshot first = stepOnce(restored);
        assertEquals(51L, first.step());
        assertEquals(6L * 51, first.time().totalSeconds());
        assertEquals(stepOnce(uninterrupted), first);
        for (int i = 0; i < 200; i++) {
            assertEquals(stepOnce(uninterrupted), stepOnce(restored));
        }
    }

    @Test
    @DisplayName("在一个行情步中途保存，恢复后继续攒 tick，不重置也不补算")
    void restoredMarketKeepsPartialStepProgress() {
        Market market = new Market(42L);
        for (int tick = 0; tick < 7; tick++) {
            market.tick();
        }

        Market restored = Market.restore(market.state());

        for (int tick = 1; tick <= 12; tick++) {
            assertTrue(restored.tick().isEmpty(), "第 " + tick + " 个 tick 不应推进");
        }
        assertEquals(1L, restored.tick().orElseThrow().step());
    }

    @Test
    @DisplayName("暂停状态随保存的状态一起恢复：暂停时保存，恢复后仍然冻结，resume 后接着走")
    void pausedStateSurvivesSaveAndRestore() {
        Market market = new Market(42L);
        for (int i = 0; i < 5; i++) {
            stepOnce(market);
        }
        market.pause();
        MarketSnapshot frozen = market.snapshot();

        Market restored = Market.restore(market.state());

        assertTrue(restored.isPaused());
        for (int tick = 0; tick < 100; tick++) {
            assertTrue(restored.tick().isEmpty(), "恢复后仍应处于暂停");
        }
        assertEquals(frozen, restored.snapshot());
        restored.resume();
        assertEquals(6L, stepOnce(restored).step());
    }

    @Test
    @DisplayName("未暂停的市场保存、恢复后仍在运行")
    void runningMarketStaysRunningAfterRestore() {
        Market restored = Market.restore(new Market(42L).state());

        assertFalse(restored.isPaused());
        assertEquals(1L, stepOnce(restored).step());
    }

    @Test
    @DisplayName("种子和步数相同时，价格序列可以复现")
    void sameSeedAndStepsGiveSamePriceSequence() {
        assertEquals(indexPricePath(new Market(2026L), 500), indexPricePath(new Market(2026L), 500));
    }

    private static MarketSnapshot stepOnce(Market market) {
        for (int tick = 0; tick < Market.TICKS_PER_STEP; tick++) {
            Optional<MarketSnapshot> stepped = market.tick();
            if (stepped.isPresent()) {
                return stepped.get();
            }
        }
        throw new AssertionError("20 个 tick 内应当产生一个行情步");
    }

    private static List<Double> indexPricePath(Market market, int steps) {
        List<Double> prices = new ArrayList<>();
        while (prices.size() < steps) {
            market.tick().ifPresent(snapshot -> prices.add(snapshot.indexPrice()));
        }
        return prices;
    }
}
