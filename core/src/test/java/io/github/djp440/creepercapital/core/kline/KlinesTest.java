package io.github.djp440.creepercapital.core.kline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KlinesTest {
    private static final int STEP_SECONDS = 6;

    /** 记录下 K 线收盘事件，供测试断言哪一层收了哪一根。 */
    private record Closed(CandlePeriod tier, Candle candle) {
    }

    private static Klines klinesRecordingInto(List<Closed> closed) {
        Klines klines = new Klines(Retention.DEFAULT);
        klines.onClosed((tier, candle) -> closed.add(new Closed(tier, candle)));
        return klines;
    }

    @Test
    @DisplayName("同一市场分钟内的行情步合成一根还在更新的 1m K 线")
    void stepsInsideOneMinuteFormOneLiveCandle() {
        Klines klines = new Klines(Retention.DEFAULT);

        klines.record(6, 100.0, 1.0);
        klines.record(12, 103.0, 2.0);
        klines.record(18, 99.0, 1.0);

        assertEquals(List.of(new Candle(0, 100.0, 103.0, 99.0, 99.0, 4.0)), klines.candles(CandlePeriod.M1, 10));
    }

    @Test
    @DisplayName("恰在整分钟的行情步开出新 K 线，同时旧的一根收盘并通知存储")
    void stepOnTheMinuteBoundaryClosesTheOldCandle() {
        List<Closed> closed = new ArrayList<>();
        Klines klines = klinesRecordingInto(closed);

        for (int step = 1; step <= 9; step++) {
            klines.record(step * STEP_SECONDS, 100.0 + step, 1.0);
        }
        assertTrue(closed.isEmpty(), "第 9 步（00:00:54）还在第一分钟里，不应有收盘");
        klines.record(60, 200.0, 5.0);

        Candle first = new Candle(0, 101.0, 109.0, 101.0, 109.0, 9.0);
        assertEquals(List.of(new Closed(CandlePeriod.M1, first)), closed);
        assertEquals(List.of(first, Candle.opening(60, 200.0, 5.0)), klines.candles(CandlePeriod.M1, 10));
    }

    @Test
    @DisplayName("limit 只取最新的若干根，按时间从旧到新排列")
    void limitKeepsTheNewestCandlesOldestFirst() {
        Klines klines = new Klines(Retention.DEFAULT);
        for (int minute = 0; minute < 5; minute++) {
            klines.record(minute * 60L, 10.0 + minute, 1.0);
        }

        List<Candle> latestTwo = klines.candles(CandlePeriod.M1, 2);

        assertEquals(List.of(Candle.opening(180, 13.0, 1.0), Candle.opening(240, 14.0, 1.0)), latestTwo);
    }

    @Test
    @DisplayName("5m K 线由 1m K 线聚合：手算 7 个分钟 K 线得到 [0,5) 与 [5,7) 两根")
    void fiveMinuteCandlesGroupMinuteCandles() {
        Klines klines = new Klines(Retention.DEFAULT);
        double[] prices = {10, 12, 9, 11, 13, 20, 18};
        for (int minute = 0; minute < prices.length; minute++) {
            klines.record(minute * 60L, prices[minute], 1.0);
        }

        List<Candle> fiveMinute = klines.candles(CandlePeriod.M5, 10);

        assertEquals(List.of(
                new Candle(0, 10.0, 13.0, 9.0, 13.0, 5.0),
                new Candle(300, 20.0, 20.0, 18.0, 18.0, 2.0)), fiveMinute);
    }

    @Test
    @DisplayName("限制条数不会把还没走完的当前 K 线丢掉")
    void currentCandleIsAlwaysTheLastOne() {
        Klines klines = new Klines(Retention.DEFAULT);
        klines.record(0, 10.0, 1.0);
        klines.record(900, 11.0, 1.0);

        List<Candle> candles = klines.candles(CandlePeriod.M15, 1);

        assertEquals(List.of(Candle.opening(900, 11.0, 1.0)), candles);
    }

    @Test
    @DisplayName("保留策略必须容得下 24h 统计和逐级聚合：不足一天的分钟数或不足 24 根小时线都不允许")
    void retentionMustCoverTheAggregationWindows() {
        assertThrows(IllegalArgumentException.class, () -> new Retention(1_439, 24, 1));
        assertThrows(IllegalArgumentException.class, () -> new Retention(1_440, 23, 1));
        assertThrows(IllegalArgumentException.class, () -> new Retention(1_440, 24, 0));
    }
}
