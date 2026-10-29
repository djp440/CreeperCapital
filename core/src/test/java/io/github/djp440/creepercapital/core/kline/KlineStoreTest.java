package io.github.djp440.creepercapital.core.kline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KlineStoreTest {
    private static final String SYMBOL = "DIAMONDCRP";
    private static final List<CandlePeriod> TIERS = List.of(CandlePeriod.M1, CandlePeriod.H1, CandlePeriod.D1);

    @TempDir
    Path directory;

    private static Candle candle(long start, double seed) {
        return new Candle(start, seed, seed + 3, seed - 2, seed + 1, seed * 10);
    }

    @Test
    @DisplayName("追加的已收盘 K 线在重新打开后原样读回，三层各自独立")
    void closedCandlesSurviveReopening() throws IOException {
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            store.append(CandlePeriod.M1, candle(0, 100));
            store.append(CandlePeriod.M1, candle(60, 101.5));
            store.append(CandlePeriod.H1, candle(0, 99));
            store.append(CandlePeriod.D1, candle(0, 98));
        }

        try (KlineStore reopened = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            assertEquals(List.of(candle(0, 100), candle(60, 101.5)), reopened.read(CandlePeriod.M1));
            assertEquals(List.of(candle(0, 99)), reopened.read(CandlePeriod.H1));
            assertEquals(List.of(candle(0, 98)), reopened.read(CandlePeriod.D1));
        }
    }

    @Test
    @DisplayName("新建的存储是空的")
    void freshStoreIsEmpty() throws IOException {
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            for (CandlePeriod tier : List.of(CandlePeriod.M1, CandlePeriod.H1, CandlePeriod.D1)) {
                assertTrue(store.read(tier).isEmpty());
            }
        }
    }

    @Test
    @DisplayName("压实失败（临时文件写不出来）时，已追加的记录与存储都保持可用，下一次追加会再次压实")
    void failedCompactionKeepsTheStoreUsable() throws IOException {
        Retention small = new Retention(1440, 24, 1); // 1d 层保留 1 根，攒到 2 根就压实
        try (KlineStore store = KlineStore.open(directory, SYMBOL, small)) {
            Path file = store.file(CandlePeriod.D1);
            Path blocker = Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp")); // 临时文件的位置被占了
            Path child = Files.writeString(blocker.resolve("占位"), "x"); // 非空目录，清理临时文件也会失败

            store.append(CandlePeriod.D1, candle(0, 100));
            assertThrows(IOException.class, () -> store.append(CandlePeriod.D1, candle(86_400, 101)));
            assertEquals(List.of(candle(86_400, 101)), store.read(CandlePeriod.D1), "失败的压实不丢已追加的记录");

            Files.delete(child);
            Files.delete(blocker);
            store.append(CandlePeriod.D1, candle(172_800, 102));

            assertEquals(List.of(candle(172_800, 102)), store.read(CandlePeriod.D1));
            assertEquals(8 + 48, Files.size(file), "再次压实成功，文件回到 1 条记录");
        }
    }

    @Test
    @DisplayName("崩溃时写了一半的末尾记录被丢弃，其余完好，之后还能继续追加")
    void tornTailRecordIsDiscarded() throws IOException {
        Path file;
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            store.append(CandlePeriod.M1, candle(0, 1));
            store.append(CandlePeriod.M1, candle(60, 2));
            store.append(CandlePeriod.M1, candle(120, 3));
            file = store.file(CandlePeriod.M1);
        }
        Files.write(file, new byte[20], StandardOpenOption.APPEND);

        try (KlineStore recovered = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            assertEquals(List.of(candle(0, 1), candle(60, 2), candle(120, 3)), recovered.read(CandlePeriod.M1));
            recovered.append(CandlePeriod.M1, candle(180, 4));
        }
        try (KlineStore again = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            assertEquals(List.of(candle(0, 1), candle(60, 2), candle(120, 3), candle(180, 4)),
                    again.read(CandlePeriod.M1));
        }
    }

    @Test
    @DisplayName("对账：丢弃收盘时间晚于存档市场时间的 K 线（存档回退、文件超前），之后重放的同一根不会重复")
    void discardAfterDropsCandlesClosedAfterTheSavedMarketTime() throws IOException {
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            store.append(CandlePeriod.M1, candle(0, 1));
            store.append(CandlePeriod.M1, candle(60, 2));
            store.append(CandlePeriod.M1, candle(120, 3));
            store.append(CandlePeriod.H1, candle(0, 9));
        }

        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            // 存档停在市场时间 150 秒：[0,60) 与 [60,120) 已收盘，[120,180) 还没收盘；1h 的 [0,3600) 更没有
            store.discardAfter(150);
            assertEquals(List.of(candle(0, 1), candle(60, 2)), store.read(CandlePeriod.M1));
            assertTrue(store.read(CandlePeriod.H1).isEmpty());

            // 重放时重新收盘的同一根 [120,180) 追加进去，不会产生重复
            store.append(CandlePeriod.M1, candle(120, 3));
        }
        try (KlineStore reopened = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            assertEquals(List.of(candle(0, 1), candle(60, 2), candle(120, 3)), reopened.read(CandlePeriod.M1));
        }
    }

    @Test
    @DisplayName("收盘时间恰等于存档市场时间的 K 线属于已收盘，保留")
    void candleEndingExactlyAtTheSavedTimeIsKept() throws IOException {
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            store.append(CandlePeriod.M1, candle(0, 1));
            store.append(CandlePeriod.M1, candle(60, 2));

            store.discardAfter(120);

            assertEquals(List.of(candle(0, 1), candle(60, 2)), store.read(CandlePeriod.M1));
        }
    }

    @Test
    @DisplayName("已经存过的 K 线（起点不晚于末根）再次追加会被忽略，文件里没有重复")
    void appendingAnAlreadyStoredCandleIsIgnored() throws IOException {
        try (KlineStore store = KlineStore.open(directory, SYMBOL, Retention.DEFAULT)) {
            store.append(CandlePeriod.M1, candle(0, 1));
            store.append(CandlePeriod.M1, candle(60, 2));
            store.append(CandlePeriod.M1, candle(60, 2));
            store.append(CandlePeriod.M1, candle(0, 1));

            assertEquals(List.of(candle(0, 1), candle(60, 2)), store.read(CandlePeriod.M1));
        }
    }

    @Test
    @DisplayName("不是 K 线文件的内容会被拒绝，而不是被当成数据")
    void foreignFileIsRejected() throws IOException {
        Files.write(directory.resolve(SYMBOL + "-1m.klines"), "this is not a kline file".getBytes());

        assertThrows(IOException.class, () -> KlineStore.open(directory, SYMBOL, Retention.DEFAULT));
    }

    @Test
    @DisplayName("长期运行后每层文件都不超过保留根数的两倍，读回的内容与内存中的各层一致")
    void storageStaysBoundedWhileRunningForManyMarketDays() throws IOException {
        Retention retention = new Retention(1_440, 24, 3);
        Klines klines = new Klines(retention);
        try (KlineStore store = KlineStore.open(directory, SYMBOL, retention)) {
            klines.onClosed((tier, candle) -> {
                try {
                    store.append(tier, candle);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Random random = new Random(11);
            int stepsPerDay = 14_400;
            for (int day = 1; day <= 20; day++) {
                for (int step = (day - 1) * stepsPerDay + 1; step <= day * stepsPerDay; step++) {
                    klines.record(step * 6L, 1_000 + random.nextInt(100), 1.0);
                }
                long total = 0;
                for (CandlePeriod tier : TIERS) {
                    long limit = 8L + 2L * retention.of(tier) * 48;
                    assertTrue(Files.size(store.file(tier)) <= limit,
                            "第 " + day + " 日末 " + tier + " 层文件 " + Files.size(store.file(tier)) + " 字节，上限 " + limit);
                    total += Files.size(store.file(tier));
                }
                assertTrue(total <= KlineStore.maxBytes(retention));
            }

            for (CandlePeriod tier : TIERS) {
                List<Candle> inMemory = klines.candles(tier, Integer.MAX_VALUE);
                // 内存里每层是「已收盘 + 正在走的一根」，文件里只有已收盘的
                assertEquals(inMemory.subList(0, inMemory.size() - 1), store.read(tier), tier + " 层读回应与内存一致");
            }
        }
    }

    @Test
    @DisplayName("默认保留策略下，三层文件加起来的体积上限约 1.5 MB（手算：15890 根 × 2 × 48 字节 + 3 个文件头）")
    void defaultRetentionBoundIsAboutOnePointFiveMegabytes() {
        // 1m: 7×1440 = 10080 根；1h: 90×24 = 2160 根；1d: 3650 根；合计 15890 根
        // 每层最多攒到保留根数的 2 倍再压实：15890 × 2 × 48 = 1_525_440，再加 3 个 8 字节的文件头
        assertEquals(1_525_464L, KlineStore.maxBytes(Retention.DEFAULT));
    }
}
