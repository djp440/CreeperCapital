package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.KlineStore;
import io.github.djp440.creepercapital.core.kline.Retention;

class MarketBackfillTest {
    private static final String DIAMOND = "DIAMONDCRP";
    private static final String GUNPOWDER = "GUNPOWDERCRP";
    private static final int STEPS_PER_DAY = IndexModel.STEPS_PER_DAY;

    @TempDir
    Path directory;

    private static Market market(long seed) {
        return new Market(seed, ContractSpec.defaults());
    }

    @Test
    @DisplayName("预生成 30 个市场日后，每个合约的 1d K 线恰好 30 根，1h 有 720 根，且都是连续的")
    void thirtyDaysOfHistoryGiveThirtyDailyCandles() {
        Market market = market(1L);

        market.backfill(30);

        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            List<Candle> days = market.klines(symbol).candles(CandlePeriod.D1, 1_000);
            assertEquals(30, days.size(), symbol + " 的 1d K 线");
            assertEquals(30 * 24, market.klines(symbol).candles(CandlePeriod.H1, 10_000).size(), symbol + " 的 1h K 线");
            assertContiguous(days, CandlePeriod.D1);
            assertContiguous(market.klines(symbol).candles(CandlePeriod.H1, 10_000), CandlePeriod.H1);
        }
    }

    @Test
    @DisplayName("预生成不触发跳跃事件（历史不发新闻），之后的实时跳跃照常报告")
    void backfillDoesNotFireJumpEvents() {
        Market market = market(2L);
        List<JumpEvent> events = new ArrayList<>();
        market.addJumpListener(events::add);

        market.backfill(30);
        assertTrue(events.isEmpty(), "历史里的跳跃不应通知监听者");

        market.queueJump(DIAMOND, 0.1);
        market.advanceSteps(1);
        assertEquals(1, events.stream().filter(e -> e.cause() == JumpEvent.Cause.MANUAL).count());
    }

    @Test
    @DisplayName("预生成与实时行情无缝衔接：预生成后接着走，与同一个市场一口气走完的结果逐根相同，衔接处没有跳空")
    void backfillContinuesSeamlesslyIntoLiveMarket() {
        Market continuous = market(3L);
        Market backfilled = market(3L);
        int backfillSteps = 30 * STEPS_PER_DAY - 1;
        int liveSteps = 3 * STEPS_PER_DAY + 777;
        continuous.advanceSteps(backfillSteps);
        MarketSnapshot lastHistory = continuous.snapshot(DIAMOND);
        continuous.advanceSteps(liveSteps);

        backfilled.backfill(30);
        assertEquals(lastHistory, backfilled.snapshot(DIAMOND), "预生成结束时的状态就是开市状态");
        MarketSnapshot firstLive = backfilled.advanceSteps(1).get(0);
        assertTrue(Math.abs(firstLive.lastPrice() / lastHistory.lastPrice() - 1) < 0.05,
                "衔接处第一个实时行情步与历史末价相差应在一个行情步的正常波动内");
        backfilled.advanceSteps(liveSteps - 1);

        assertEquals(continuous.state(), backfilled.state());
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            for (CandlePeriod period : CandlePeriod.values()) {
                assertEquals(continuous.klines(symbol).candles(period, Integer.MAX_VALUE),
                        backfilled.klines(symbol).candles(period, Integer.MAX_VALUE), symbol + " " + period);
            }
        }
    }

    @Test
    @DisplayName("预生成的 K 线收盘时追加进已绑定的存储，重新打开后历史完整，且新市场恢复后不会再预生成")
    void backfillIsPersistedThroughTheBoundStore() throws IOException {
        Market market = market(4L);
        try (KlineStore store = KlineStore.open(directory, DIAMOND, Retention.DEFAULT)) {
            store.bind(market.klines(DIAMOND), 0, MarketBackfillTest::failOnWrite);
            market.backfill(30);
        }
        MarketState saved = market.state();

        Market restarted = Market.restore(saved, ContractSpec.defaults());
        try (KlineStore store = KlineStore.open(directory, DIAMOND, Retention.DEFAULT)) {
            assertEquals(0, store.bind(restarted.klines(DIAMOND), restarted.snapshot(DIAMOND).time().totalSeconds(),
                    MarketBackfillTest::failOnWrite));
            assertEquals(market.klines(DIAMOND).candles(CandlePeriod.D1, 1_000),
                    restarted.klines(DIAMOND).candles(CandlePeriod.D1, 1_000));
            assertEquals(30, restarted.klines(DIAMOND).candles(CandlePeriod.D1, 1_000).size());
        }
    }

    @Test
    @DisplayName("只有还没走过任何行情步的新市场能预生成；已经走过的市场（已有世界）拒绝，避免重复预生成")
    void onlyAFreshMarketCanBeBackfilled() {
        Market market = market(5L);
        market.advanceSteps(1);
        assertThrows(IllegalStateException.class, () -> market.backfill(30));

        Market backfilled = market(5L);
        backfilled.backfill(30);
        assertThrows(IllegalStateException.class, () -> backfilled.backfill(30));
        assertThrows(IllegalStateException.class, () -> Market.restore(backfilled.state(), ContractSpec.defaults()).backfill(30));
    }

    @Test
    @DisplayName("预生成的天数必须为正")
    void daysMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> market(6L).backfill(0));
    }

    @Test
    @DisplayName("实测：预生成 30 个市场日（两个合约）的耗时，带 K 线文件写入")
    void backfillIsFastEnoughForWorldCreation() throws IOException {
        Market market = market(7L);
        long started = System.nanoTime();
        try (KlineStore diamond = KlineStore.open(directory, DIAMOND, Retention.DEFAULT);
                KlineStore gunpowder = KlineStore.open(directory, GUNPOWDER, Retention.DEFAULT)) {
            diamond.bind(market.klines(DIAMOND), 0, MarketBackfillTest::failOnWrite);
            gunpowder.bind(market.klines(GUNPOWDER), 0, MarketBackfillTest::failOnWrite);
            market.backfill(30);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("预生成 30 个市场日（2 个合约，含 K 线文件写入）耗时 " + millis + " ms");

        assertTrue(millis < 10_000, "预生成耗时 " + millis + " ms，玩家进世界会等太久");
    }

    private static void failOnWrite(java.io.IOException e) {
        throw new AssertionError(e);
    }

    private static void assertContiguous(List<Candle> candles, CandlePeriod period) {
        for (int i = 1; i < candles.size(); i++) {
            assertEquals(candles.get(i - 1).start() + period.seconds(), candles.get(i).start(),
                    period + " 第 " + i + " 根与前一根不连续");
        }
    }
}
