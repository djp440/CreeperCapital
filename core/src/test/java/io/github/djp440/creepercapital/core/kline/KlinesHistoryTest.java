package io.github.djp440.creepercapital.core.kline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 逐级聚合（1m → 1h → 1d）、保留策略、从存储装载历史、24h 统计。
 * 期望值都来自「直接对原始行情步按周期分组」的暴力算法，与被测的逐级聚合算法相互独立。
 */
class KlinesHistoryTest {
    private static final int STEP_SECONDS = 6;
    private static final int STEPS_PER_DAY = 14_400;

    private record Closed(CandlePeriod tier, Candle candle) {
    }

    @Test
    @DisplayName("小时与日在最后一个分钟收盘时逐级聚合出来，每层每根只通知一次")
    void hoursAndDaysAreCascadedFromMinutes() {
        List<Closed> closed = new ArrayList<>();
        Klines klines = new Klines(Retention.DEFAULT);
        klines.onClosed((tier, candle) -> closed.add(new Closed(tier, candle)));
        Random random = new Random(1);
        double[] prices = new double[2 * STEPS_PER_DAY + 1];
        double[] volumes = new double[prices.length];
        // 走到第 3 日 00:00:00 的那一步：它开出第 3 日第一分钟，第 1、2 日及其所有小时都已收盘
        for (int step = 1; step < prices.length; step++) {
            prices[step] = 100 + random.nextInt(50);
            volumes[step] = 1.0;
            klines.record(step * (long) STEP_SECONDS, prices[step], volumes[step]);
        }

        List<Candle> hours = closed.stream().filter(c -> c.tier() == CandlePeriod.H1).map(Closed::candle).toList();
        List<Candle> days = closed.stream().filter(c -> c.tier() == CandlePeriod.D1).map(Closed::candle).toList();

        List<Candle> expectedHours = bruteForce(CandlePeriod.H1, prices, volumes);
        List<Candle> expectedDays = bruteForce(CandlePeriod.D1, prices, volumes);
        // 暴力算法的最后一根是刚开出来的第 3 日第一小时/第 3 日，还没收盘
        assertEquals(48, hours.size());
        assertEquals(2, days.size());
        for (int i = 0; i < hours.size(); i++) {
            assertCandleEquals("第 " + i + " 根小时线", expectedHours.get(i), hours.get(i));
        }
        for (int i = 0; i < days.size(); i++) {
            assertCandleEquals("第 " + i + " 根日线", expectedDays.get(i), days.get(i));
        }
    }

    @Test
    @DisplayName("七个周期的聚合结果与暴力分组一致，K 线边界按市场时间对齐")
    void everyPeriodMatchesBruteForceGrouping() {
        Klines klines = new Klines(Retention.DEFAULT);
        Random random = new Random(42);
        int steps = 9 * STEPS_PER_DAY; // 9 个市场日，跨过一周的边界，也超出 1m 层保留的 7 个市场日
        double[] prices = new double[steps + 1];
        double[] volumes = new double[steps + 1];
        double price = 1_000;
        for (int step = 1; step <= steps; step++) {
            price *= 1 + random.nextGaussian() * 0.0005;
            prices[step] = price;
            volumes[step] = 1 + random.nextInt(8) * 0.25;
            klines.record(step * (long) STEP_SECONDS, prices[step], volumes[step]);
        }

        for (CandlePeriod period : CandlePeriod.values()) {
            List<Candle> actual = klines.candles(period, Integer.MAX_VALUE);
            List<Candle> expected = bruteForce(period, prices, volumes);
            assertTrue(!actual.isEmpty(), period + " 应有 K 线");
            // 1m 层只留 7 个市场日，所以 1m/5m/15m 只能回看到那里；其余周期的历史完整
            if (period.seconds() >= CandlePeriod.H1.seconds()) {
                assertEquals(expected.size(), actual.size(), period + " 应有完整历史");
            }
            List<Candle> expectedTail = expected.subList(expected.size() - actual.size(), expected.size());
            for (int i = 0; i < actual.size(); i++) {
                assertCandleEquals(period + " 第 " + i + " 根", expectedTail.get(i), actual.get(i));
            }
        }
    }

    @Test
    @DisplayName("1m 层超出保留根数后淘汰最旧的，但它们的聚合结果仍在 1h 层")
    void oldMinutesAreTrimmedButStayInHours() {
        Klines klines = new Klines(new Retention(1_440, 24 * 5, 5));
        for (long step = 1; step <= 3L * STEPS_PER_DAY; step++) { // 3 个市场日
            klines.record(step * STEP_SECONDS, 100.0 + step % 13, 1.0);
        }

        List<Candle> minutes = klines.candles(CandlePeriod.M1, Integer.MAX_VALUE);
        List<Candle> hours = klines.candles(CandlePeriod.H1, Integer.MAX_VALUE);

        assertEquals(1_441, minutes.size(), "1m 层只留 1440 根已收盘的，再加正在走的一根");
        assertEquals(0L, hours.get(0).start(), "最早的小时线仍在，虽然对应的 1m 早被淘汰");
        assertTrue(minutes.get(0).start() > 0);
    }

    @Test
    @DisplayName("从存储装载历史后接着记录，与一路没停过的结果完全相同")
    void loadedHistoryContinuesSeamlessly() {
        Klines uninterrupted = new Klines(Retention.DEFAULT);
        List<Closed> persisted = new ArrayList<>();
        Klines beforeRestart = new Klines(Retention.DEFAULT);
        beforeRestart.onClosed((tier, candle) -> persisted.add(new Closed(tier, candle)));
        Random random = new Random(7);
        int stopStep = 2 * STEPS_PER_DAY + 123; // 停在第 3 日中间的某个行情步
        double[] prices = new double[3 * STEPS_PER_DAY + 1];
        for (int step = 1; step < prices.length; step++) {
            prices[step] = 500 + random.nextInt(100);
            uninterrupted.record(step * (long) STEP_SECONDS, prices[step], 1.0);
        }
        for (int step = 1; step <= stopStep; step++) {
            beforeRestart.record(step * (long) STEP_SECONDS, prices[step], 1.0);
        }

        // 重启：已收盘的各层从文件读回，正在走的 1m 随市场状态一起保存
        Klines restarted = new Klines(Retention.DEFAULT, beforeRestart.liveMinute().orElseThrow());
        for (CandlePeriod tier : List.of(CandlePeriod.M1, CandlePeriod.H1, CandlePeriod.D1)) {
            restarted.loadHistory(tier, persisted.stream().filter(c -> c.tier() == tier).map(Closed::candle).toList());
        }
        for (int step = stopStep + 1; step < prices.length; step++) {
            restarted.record(step * (long) STEP_SECONDS, prices[step], 1.0);
        }

        for (CandlePeriod period : CandlePeriod.values()) {
            assertEquals(uninterrupted.candles(period, Integer.MAX_VALUE), restarted.candles(period, Integer.MAX_VALUE),
                    period + " 重启前后应一致");
        }
    }

    @Test
    @DisplayName("历史被淘汰到周期边界中间时，残缺的最旧一根不会冒充完整的 5m K 线")
    void incompleteOldestGroupIsDropped() {
        Klines klines = new Klines(Retention.DEFAULT);
        // 只有从第 2 分钟起的历史：5m 的 [0,5) 缺了前两根分钟线
        klines.loadHistory(CandlePeriod.M1, List.of(
                Candle.opening(120, 10.0, 1.0), Candle.opening(180, 11.0, 1.0), Candle.opening(240, 12.0, 1.0),
                Candle.opening(300, 13.0, 1.0)));

        List<Candle> fiveMinute = klines.candles(CandlePeriod.M5, 10);

        assertEquals(List.of(Candle.opening(300, 13.0, 1.0)), fiveMinute);
    }

    @Test
    @DisplayName("24h 统计取最近 1440 根 1m：开盘、最高、最低、成交量与原始行情步逐个核对")
    void stats24hCoverTheLastMarketDayOfMinutes() {
        Klines klines = new Klines(Retention.DEFAULT);
        Random random = new Random(3);
        int steps = 2 * STEPS_PER_DAY + 500; // 第 3 日 00:50:00
        double[] prices = new double[steps + 1];
        double[] volumes = new double[steps + 1];
        for (int step = 1; step <= steps; step++) {
            prices[step] = 1_000 + random.nextInt(2_000) / 10.0;
            volumes[step] = 1 + random.nextInt(4);
            klines.record(step * (long) STEP_SECONDS, prices[step], volumes[step]);
        }

        Stats24h stats = klines.stats24h().orElseThrow();

        // 当前分钟线的起点 = 第 3 日 00:50，窗口 = 此前 1439 个分钟线 + 当前这根，即从前一天 00:51 起
        long liveStart = CandlePeriod.M1.startOf(steps * (long) STEP_SECONDS);
        long windowStart = liveStart - (1_440 - 1) * 60L;
        double open = Double.NaN;
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        double volume = 0;
        for (int step = 1; step <= steps; step++) {
            if (step * (long) STEP_SECONDS >= windowStart) {
                open = Double.isNaN(open) ? prices[step] : open;
                high = Math.max(high, prices[step]);
                low = Math.min(low, prices[step]);
                volume += volumes[step];
            }
        }
        assertEquals(open, stats.open());
        assertEquals(high, stats.high());
        assertEquals(low, stats.low());
        assertEquals(prices[steps], stats.close());
        assertEquals(volume, stats.volume(), 1e-6);
        assertEquals((prices[steps] - open) / open, stats.changeRatio(), 1e-12);
    }

    @Test
    @DisplayName("窗口恰好是一个对齐的市场日时，24h 统计与那根日 K 完全一致")
    void stats24hEqualTheDayCandleWhenAligned() {
        Klines klines = new Klines(Retention.DEFAULT);
        Random random = new Random(5);
        // 走到第 1 日最后一个分钟里：窗口 = 第 1 日 00:00 起的 1440 个分钟 = 整个第 1 日
        for (long step = 1; step < STEPS_PER_DAY; step++) {
            klines.record(step * STEP_SECONDS, 100 + random.nextInt(900), 1.0 + random.nextInt(3));
        }

        Stats24h stats = klines.stats24h().orElseThrow();
        Candle day = klines.candles(CandlePeriod.D1, 1).get(0);

        assertEquals(day.open(), stats.open());
        assertEquals(day.high(), stats.high());
        assertEquals(day.low(), stats.low());
        assertEquals(day.close(), stats.close());
        assertEquals(day.volume(), stats.volume(), 1e-6);
    }

    @Test
    @DisplayName("还没有任何行情步时没有 24h 统计")
    void noStatsBeforeTheFirstStep() {
        assertTrue(new Klines(Retention.DEFAULT).stats24h().isEmpty());
    }

    @Test
    @DisplayName("客户端增量更新：行情步并入列表最后一根，跨过周期边界就开出新的一根")
    void advanceUpdatesTheLastCandleOrOpensANewOne() {
        List<Candle> candles = new ArrayList<>(List.of(new Candle(0, 10.0, 12.0, 9.0, 11.0, 4.0)));

        Klines.advance(candles, CandlePeriod.M1, 30, 13.0, 2.0);
        assertEquals(List.of(new Candle(0, 10.0, 13.0, 9.0, 13.0, 6.0)), candles);

        Klines.advance(candles, CandlePeriod.M1, 60, 8.0, 1.0);
        assertEquals(List.of(new Candle(0, 10.0, 13.0, 9.0, 13.0, 6.0), Candle.opening(60, 8.0, 1.0)), candles);
    }

    @Test
    @DisplayName("先拉取历史、之后逐步增量更新，得到的每个周期的 K 线与服务端一模一样")
    void incrementalUpdatesMatchTheServerForEveryPeriod() {
        Klines server = new Klines(Retention.DEFAULT);
        Random random = new Random(13);
        int historySteps = 3 * STEPS_PER_DAY + 4_321;
        for (int step = 1; step <= historySteps; step++) {
            server.record(step * (long) STEP_SECONDS, 100 + random.nextInt(50), 1 + random.nextInt(3));
        }
        // 玩家此刻打开界面，拉取每个周期的最近 200 根
        java.util.Map<CandlePeriod, List<Candle>> clientView = new java.util.EnumMap<>(CandlePeriod.class);
        for (CandlePeriod period : CandlePeriod.values()) {
            clientView.put(period, new ArrayList<>(server.candles(period, 200)));
        }

        // 之后每个行情步，服务端记录，客户端按同一个行情步增量更新自己的 K 线
        for (int step = historySteps + 1; step <= historySteps + 2 * STEPS_PER_DAY; step++) {
            double price = 100 + random.nextInt(50);
            double volume = 1 + random.nextInt(3);
            server.record(step * (long) STEP_SECONDS, price, volume);
            for (CandlePeriod period : CandlePeriod.values()) {
                Klines.advance(clientView.get(period), period, step * (long) STEP_SECONDS, price, volume);
            }
        }

        for (CandlePeriod period : CandlePeriod.values()) {
            List<Candle> client = clientView.get(period);
            int shown = Math.min(100, client.size());
            assertEquals(server.candles(period, shown), client.subList(client.size() - shown, client.size()),
                    period + " 的增量结果");
        }
    }

    /** 不经过任何逐级聚合，直接把每个行情步按周期边界分组。 */
    private static List<Candle> bruteForce(CandlePeriod period, double[] prices, double[] volumes) {
        List<Candle> candles = new ArrayList<>();
        for (int step = 1; step < prices.length; step++) {
            long start = period.startOf(step * (long) STEP_SECONDS);
            Candle last = candles.isEmpty() ? null : candles.get(candles.size() - 1);
            if (last != null && last.start() == start) {
                candles.set(candles.size() - 1, last.accept(prices[step], volumes[step]));
            } else {
                candles.add(Candle.opening(start, prices[step], volumes[step]));
            }
        }
        return candles;
    }

    private static void assertCandleEquals(String message, Candle expected, Candle actual) {
        assertEquals(expected.start(), actual.start(), message + " 起点");
        assertEquals(expected.open(), actual.open(), message + " 开盘");
        assertEquals(expected.high(), actual.high(), message + " 最高");
        assertEquals(expected.low(), actual.low(), message + " 最低");
        assertEquals(expected.close(), actual.close(), message + " 收盘");
        assertEquals(expected.volume(), actual.volume(), 1e-6, message + " 成交量");
    }
}
