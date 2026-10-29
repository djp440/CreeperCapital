package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class MarketKlinesTest {
    private static final String SYMBOL = "DIAMONDCRP";
    private static final int STEPS_PER_DAY = 14_400;
    private static final List<CandlePeriod> TIERS = List.of(CandlePeriod.M1, CandlePeriod.H1, CandlePeriod.D1);

    @TempDir
    Path directory;

    private static Market market(long seed) {
        return new Market(seed, ContractSpec.defaults());
    }

    /** 推进 n 个行情步（每步 20 个 tick），返回每一步的快照。 */
    private static List<MarketSnapshot> run(Market market, int steps) {
        List<MarketSnapshot> snapshots = new ArrayList<>();
        while (snapshots.size() < steps) {
            List<MarketSnapshot> stepped = market.tick();
            if (!stepped.isEmpty()) {
                snapshots.add(stepped.get(0));
            }
        }
        return snapshots;
    }

    @Test
    @DisplayName("每个行情步并入当前 K 线：收盘价是最新价格，成交量是各步成交量之和，开盘是这分钟第一步的价格")
    void everyMarketStepFeedsTheCurrentCandle() {
        Market market = market(42L);

        List<MarketSnapshot> steps = run(market, 25); // 150 市场秒：第 1 分钟 9 步（6..54 秒），第 2 分钟 10 步，第 3 分钟 6 步
        List<Candle> minutes = market.klines(SYMBOL).candles(CandlePeriod.M1, 10);

        assertEquals(3, minutes.size());
        assertEquals(0L, minutes.get(0).start());
        assertEquals(steps.get(0).lastPrice(), minutes.get(0).open());
        assertEquals(steps.get(9).lastPrice(), minutes.get(1).open(), "第 10 步（60 秒）开出第二根");
        assertEquals(steps.get(24).lastPrice(), minutes.get(2).close());
        double firstMinuteVolume = steps.subList(0, 9).stream().mapToDouble(MarketSnapshot::volume).sum();
        assertEquals(firstMinuteVolume, minutes.get(0).volume(), 1e-9);
    }

    @Test
    @DisplayName("K 线用最新价格：高低点覆盖这分钟内每一步的最新价格")
    void candlesTrackTheLastPrice() {
        Market market = market(9L);

        List<MarketSnapshot> steps = run(market, 9);
        Candle minute = market.klines(SYMBOL).candles(CandlePeriod.M1, 1).get(0);

        assertEquals(steps.stream().mapToDouble(MarketSnapshot::lastPrice).max().orElseThrow(), minute.high());
        assertEquals(steps.stream().mapToDouble(MarketSnapshot::lastPrice).min().orElseThrow(), minute.low());
    }

    @Test
    @DisplayName("占位成交量恒为正，且与价格波动正相关；同一种子给出同一条成交量序列")
    void placeholderVolumeIsPositiveAndTracksVolatility() {
        List<MarketSnapshot> steps = run(market(5L), 2_000);

        List<Double> volumes = steps.stream().map(MarketSnapshot::volume).toList();
        assertTrue(volumes.stream().allMatch(v -> v > 0), "成交量应为正");
        double[] moves = new double[steps.size()];
        double previous = 1000.0; // 第 0 步的指数价格
        for (int i = 0; i < steps.size(); i++) {
            moves[i] = Math.abs(Math.log(steps.get(i).indexPrice() / previous));
            previous = steps.get(i).indexPrice();
        }
        assertTrue(correlation(moves, volumes.stream().mapToDouble(Double::doubleValue).toArray()) > 0.8,
                "成交量应与指数价格的绝对收益率强正相关");
        assertEquals(volumes, run(market(5L), 2_000).stream().map(MarketSnapshot::volume).toList());
    }

    @Test
    @DisplayName("重启后历史 K 线完整：存档与文件一致（正常停服）时没有断档、没有重复，且之后与没停过的市场完全相同")
    void cleanRestartKeepsTheFullHistory() throws IOException {
        Market uninterrupted = market(7L);
        run(uninterrupted, 2 * STEPS_PER_DAY + 1_234);

        Market first = market(7L);
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            assertEquals(0, store.bind(first.klines(SYMBOL), 0, e -> {
                throw new AssertionError(e);
            }), "新市场没有缺口");
            run(first, STEPS_PER_DAY + 321);
        }
        MarketState saved = first.state();

        Market restarted = Market.restore(saved, ContractSpec.defaults());
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            assertEquals(0, store.bind(restarted.klines(SYMBOL), restarted.snapshot(SYMBOL).time().totalSeconds(), e -> {
                throw new AssertionError(e);
            }), "正常停服后文件与存档衔接，没有缺口");
            run(restarted, STEPS_PER_DAY + 913);

            assertSameHistory(uninterrupted, restarted);
            assertContiguousWithoutDuplicates(store, restarted);
        }
        try (KlineStore reopened = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            assertContiguousWithoutDuplicates(reopened, restarted);
        }
    }

    @Test
    @DisplayName("崩溃重启：存档比文件旧（文件里多出存档之后才收盘的 K 线），对账并重放后没有断档、没有重复")
    void crashRestartReplaysWithoutGapsOrDuplicates() throws IOException {
        Market uninterrupted = market(11L);
        run(uninterrupted, 2 * STEPS_PER_DAY + 1_000);

        // 市场一直在写文件，但存档停在较早的时刻（自动保存之后又走了一段，随后进程被杀）
        Market beforeCrash = market(11L);
        MarketState staleSave;
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            store.bind(beforeCrash.klines(SYMBOL), 0, e -> {
                throw new AssertionError(e);
            });
            run(beforeCrash, STEPS_PER_DAY / 2 + 77);
            staleSave = beforeCrash.state();
            run(beforeCrash, STEPS_PER_DAY + 500); // 存档之后又走了 1.04 个市场日，文件跟着长
        }

        Market restarted = Market.restore(staleSave, ContractSpec.defaults());
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            assertEquals(0, store.bind(restarted.klines(SYMBOL), restarted.snapshot(SYMBOL).time().totalSeconds(), e -> {
                throw new AssertionError(e);
            }), "崩溃重启丢弃多出的记录后与存档衔接，没有缺口");
            run(restarted, 2 * STEPS_PER_DAY + 1_000 - (STEPS_PER_DAY / 2 + 77));

            assertSameHistory(uninterrupted, restarted);
            assertContiguousWithoutDuplicates(store, restarted);
        }
    }

    @Test
    @DisplayName("文件比存档旧（例如断电时文件末尾没落盘）：重启时报告缺了多少根 1m，其余历史照常读回")
    void bindReportsTheGapWhenTheFileLagsBehindTheSave() throws IOException {
        Market market = market(13L);
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            store.bind(market.klines(SYMBOL), 0, e -> {
                throw new AssertionError(e);
            });
            run(market, 1_000); // 第 1000 步是第 6000 秒：第 0~99 分钟已收盘 100 根，正在走第 100 分钟
        }
        MarketState saved = market.state();
        long liveStart = saved.contracts().get(0).liveMinute().start();
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            store.discardAfter(liveStart - 3 * 60); // 模拟文件丢了最后 3 根
        }

        Market restarted = Market.restore(saved, ContractSpec.defaults());
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            long missing = store.bind(restarted.klines(SYMBOL), restarted.snapshot(SYMBOL).time().totalSeconds(), e -> {
                throw new AssertionError(e);
            });

            assertEquals(3, missing);
            assertEquals(97 + 1, restarted.klines(SYMBOL).candles(CandlePeriod.M1, 1_000).size(), "97 根已收盘 + 正在走的一根");
        }
    }

    @Test
    @DisplayName("K 线文件整个丢了而存档还在：缺口等于存档之前的全部分钟数")
    void bindReportsTheWholeHistoryWhenTheFilesAreGone() throws IOException {
        Market market = market(17L);
        run(market, 1_000);
        MarketState saved = market.state();

        Market restarted = Market.restore(saved, ContractSpec.defaults());
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            long missing = store.bind(restarted.klines(SYMBOL), restarted.snapshot(SYMBOL).time().totalSeconds(), e -> {
                throw new AssertionError(e);
            });

            assertEquals(saved.contracts().get(0).liveMinute().start() / 60, missing);
        }
    }

    @Test
    @DisplayName("没有 K 线字段的旧存档恢复后从空的当前 K 线开始，不影响行情")
    void stateWithoutALiveCandleRestores() {
        Market market = market(3L);
        run(market, 15);
        MarketState state = market.state();
        ContractState contract = state.contracts().get(0);
        ContractState withoutCandle = new ContractState(contract.symbol(), contract.index(), contract.randomState(),
                contract.pricing(), 0.0, null);
        MarketState legacy = new MarketState(state.seed(), state.step(), state.ticksIntoStep(), state.paused(),
                List.of(withoutCandle, state.contracts().get(1)));

        Market restored = Market.restore(legacy, ContractSpec.defaults());

        assertNull(legacy.contracts().get(0).liveMinute());
        assertTrue(restored.klines(SYMBOL).candles(CandlePeriod.M1, 10).isEmpty());
        run(restored, 1);
        assertEquals(1, restored.klines(SYMBOL).candles(CandlePeriod.M1, 10).size());
    }

    @Test
    @DisplayName("24h 统计随行情步更新：最新价格即收盘价，涨跌幅按 24h 窗口开盘价计算")
    void stats24hFollowTheMarket() {
        Market market = market(21L);

        MarketSnapshot last = run(market, 300).get(299);
        var stats = market.stats24h(SYMBOL).orElseThrow();

        assertEquals(last.lastPrice(), stats.close());
        assertTrue(stats.high() >= stats.close() && stats.low() <= stats.close());
        assertEquals((stats.close() - stats.open()) / stats.open(), stats.changeRatio(), 1e-12);
    }

    @Test
    @DisplayName("快进 N 个行情步与逐 tick 推进 N 个行情步的结果完全相同，只返回最后一步的快照")
    void advanceStepsEqualsTickingThroughThem() {
        Market ticked = market(8L);
        Market advanced = market(8L);
        List<MarketSnapshot> steps = run(ticked, 2_000);

        List<MarketSnapshot> last = advanced.advanceSteps(2_000);

        assertEquals(steps.get(1_999), last.get(0));
        assertEquals(2, last.size(), "每个合约各一个快照");
        assertEquals(ticked.state(), advanced.state());
        assertEquals(ticked.klines(SYMBOL).candles(CandlePeriod.M5, 100), advanced.klines(SYMBOL).candles(CandlePeriod.M5, 100));
        assertEquals(ticked.klines("GUNPOWDERCRP").candles(CandlePeriod.M5, 100),
                advanced.klines("GUNPOWDERCRP").candles(CandlePeriod.M5, 100));
    }

    @Test
    @DisplayName("暂停的市场快进不会前进")
    void advanceStepsDoesNothingWhilePaused() {
        Market market = market(8L);
        market.pause();

        assertTrue(market.advanceSteps(10).isEmpty());
        assertEquals(0L, market.snapshot(SYMBOL).step());
    }

    @Test
    @DisplayName("每个合约有自己的 K 线：GUNPOWDERCRP 的 K 线在它自己的价位（约 100），不混入 DIAMONDCRP（约 1000）")
    void everyContractHasItsOwnKlines() {
        Market market = market(31L);
        run(market, 200);

        Candle diamond = market.klines(SYMBOL).candles(CandlePeriod.M1, 1).get(0);
        Candle gunpowder = market.klines("GUNPOWDERCRP").candles(CandlePeriod.M1, 1).get(0);

        assertTrue(diamond.close() > 500 && diamond.close() < 2_000, "钻石 " + diamond.close());
        assertTrue(gunpowder.close() > 50 && gunpowder.close() < 200, "火药 " + gunpowder.close());
        assertTrue(market.stats24h("GUNPOWDERCRP").orElseThrow().close() < 200);
    }

    /** 各周期的 K 线（含当前这根）与没中断过的市场逐根相同。 */
    private static void assertSameHistory(Market expected, Market actual) {
        for (CandlePeriod period : CandlePeriod.values()) {
            assertEquals(expected.klines(SYMBOL).candles(period, Integer.MAX_VALUE),
                    actual.klines(SYMBOL).candles(period, Integer.MAX_VALUE), period + " 的历史 K 线");
        }
        assertEquals(expected.stats24h(SYMBOL), actual.stats24h(SYMBOL));
    }

    /** 文件里每层的起点严格按层周期递增（无断档、无重复），并且与市场内存里已收盘的各层完全相同。 */
    private static void assertContiguousWithoutDuplicates(KlineStore store, Market market) throws IOException {
        for (CandlePeriod tier : TIERS) {
            List<Candle> stored = store.read(tier);
            assertTrue(!stored.isEmpty(), tier + " 层应有已收盘的 K 线");
            for (int i = 1; i < stored.size(); i++) {
                assertEquals(stored.get(i - 1).start() + tier.seconds(), stored.get(i).start(),
                        tier + " 层第 " + i + " 根与前一根之间不连续");
            }
            List<Candle> inMemory = market.klines(SYMBOL).candles(tier, Integer.MAX_VALUE);
            assertEquals(inMemory.subList(0, inMemory.size() - 1), stored, tier + " 层文件应与内存一致");
        }
    }

    private static double correlation(double[] x, double[] y) {
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < x.length; i++) {
            meanX += x[i] / x.length;
            meanY += y[i] / y.length;
        }
        double covariance = 0;
        double varianceX = 0;
        double varianceY = 0;
        for (int i = 0; i < x.length; i++) {
            covariance += (x[i] - meanX) * (y[i] - meanY);
            varianceX += (x[i] - meanX) * (x[i] - meanX);
            varianceY += (y[i] - meanY) * (y[i] - meanY);
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }
}
