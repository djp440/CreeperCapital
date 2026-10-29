package io.github.djp440.creepercapital.core.kline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 一个合约的 K 线存储：世界目录下的追加式时序文件，1m、1h、1d 三层各一个。
 * <p>
 * 文件由 8 字节文件头和定长记录（起点、开高低收、成交量，共 48 字节）组成，K 线收盘时在末尾追加一条记录，
 * 所以不需要重写整个文件，也不进世界存档的自动保存。每层攒到保留根数的 2 倍时压实一次，只留最新的保留根数，
 * 因此体积有上界，见 {@link #maxBytes}。
 * <p>
 * 崩溃时可能留下写了一半的末尾记录，打开时自动丢弃。存档（市场状态）可能比文件旧，
 * 启动时用 {@link #discardAfter} 丢掉文件里「存档之后才收盘」的 K 线，重放会按相同的随机数状态再生成它们。
 * <p>
 * 只能在一个线程里使用。
 */
public final class KlineStore implements AutoCloseable {
    private static final List<CandlePeriod> TIERS = List.of(CandlePeriod.M1, CandlePeriod.H1, CandlePeriod.D1);
    private static final int MAGIC = 0x43434B4C; // "CCKL"
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 8;
    private static final int RECORD_BYTES = 6 * 8;
    /** 每层最多攒到保留根数的这个倍数就压实。 */
    private static final int COMPACTION_FACTOR = 2;

    private final Retention retention;
    private final Map<CandlePeriod, TierFile> files = new EnumMap<>(CandlePeriod.class);

    private KlineStore(Retention retention) {
        this.retention = retention;
    }

    /**
     * 打开（必要时创建）某个合约的 K 线文件。
     *
     * @throws IOException 目录无法创建，或文件不是 K 线文件
     */
    public static KlineStore open(Path directory, String symbol, Retention retention) throws IOException {
        Files.createDirectories(directory);
        KlineStore store = new KlineStore(retention);
        try {
            for (CandlePeriod tier : TIERS) {
                store.files.put(tier, TierFile.open(directory.resolve(symbol + "-" + tier.label() + ".klines")));
            }
        } catch (IOException | RuntimeException e) {
            store.close();
            throw e;
        }
        return store;
    }

    /**
     * 三层文件的体积上限（字节）：每层最多攒到保留根数的 2 倍再压实，另加各自的文件头。
     * 默认保留策略下约 1.5 MB，不随运行时间增长。
     */
    public static long maxBytes(Retention retention) {
        long bytes = 0;
        for (CandlePeriod tier : TIERS) {
            bytes += HEADER_BYTES + (long) COMPACTION_FACTOR * retention.of(tier) * RECORD_BYTES;
        }
        return bytes;
    }

    /**
     * 把存储接到 K 线上：先按市场时间对账，再把三层历史装回，之后每根收盘的 K 线都追加进文件。
     * 追加失败时只通知 {@code onWriteFailure}，不影响行情继续走。
     * <p>
     * 文件比存档新（崩溃）由 {@link #discardAfter} 丢掉多出来的、重放补回；文件比存档旧（例如断电时文件末尾没落盘）
     * 则缺的那几根已经无法找回，这里只把缺了多少根 1m 报告出来，由调用方记录。
     *
     * @param marketSeconds 存档里的市场时间（市场秒）
     * @return 文件里缺失的、本该在存档的当前 1m 之前已收盘的 1m 根数；衔接正常时为 0
     */
    public long bind(Klines klines, long marketSeconds, Consumer<IOException> onWriteFailure) throws IOException {
        discardAfter(marketSeconds);
        long missing = klines.liveMinute().map(live -> missingMinutes(live.start())).orElse(0L);
        for (CandlePeriod tier : TIERS) {
            klines.loadHistory(tier, read(tier));
        }
        klines.onClosed((tier, candle) -> {
            try {
                append(tier, candle);
            } catch (IOException e) {
                onWriteFailure.accept(e);
            }
        });
        return missing;
    }

    /** 1m 文件末根与存档里正在走的那根 1m 之间，缺了多少根已收盘的 1m。日历从 0 起，所以空文件应从 0 开始衔接。 */
    private long missingMinutes(long liveStart) {
        TierFile minutes = files.get(CandlePeriod.M1);
        long expected = minutes.count == 0 ? 0 : minutes.lastStart + CandlePeriod.M1.seconds();
        return Math.max(0, (liveStart - expected) / CandlePeriod.M1.seconds());
    }

    /** 某一层的文件路径。 */
    public Path file(CandlePeriod tier) {
        return files.get(tier).path;
    }

    /** 某一层已存的最新若干根（至多保留根数），从旧到新。 */
    public List<Candle> read(CandlePeriod tier) throws IOException {
        TierFile file = files.get(tier);
        return file.read(Math.max(0, file.count - retention.of(tier)), file.count);
    }

    /** 追加一根刚收盘的 K 线。起点不晚于末根的视为已存过，忽略，保证文件里没有重复。 */
    public void append(CandlePeriod tier, Candle candle) throws IOException {
        TierFile file = files.get(tier);
        if (candle.start() <= file.lastStart) {
            return;
        }
        file.append(candle);
        if (file.count >= (long) COMPACTION_FACTOR * retention.of(tier)) {
            file.keepLast(retention.of(tier));
        }
    }

    /**
     * 丢弃收盘时间晚于 {@code marketSeconds} 的 K 线。
     * 用来和市场存档对账：存档比文件旧时，文件里多出来的是存档之后才收盘的 K 线，会由重放重新生成。
     */
    public void discardAfter(long marketSeconds) throws IOException {
        for (CandlePeriod tier : TIERS) {
            TierFile file = files.get(tier);
            long keep = file.count;
            while (keep > 0 && file.startOf(keep - 1) + tier.seconds() > marketSeconds) {
                keep--;
            }
            file.truncateTo(keep);
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (TierFile file : files.values()) {
            try {
                file.close();
            } catch (IOException e) {
                failure = failure == null ? e : failure;
            }
        }
        files.clear();
        if (failure != null) {
            throw failure;
        }
    }

    /** 一层的文件：头 + 定长记录。 */
    private static final class TierFile {
        private final Path path;
        private FileChannel channel;
        /** 文件里的记录数。 */
        private long count;
        /** 末根的起点；还没有记录时为 Long.MIN_VALUE。 */
        private long lastStart = Long.MIN_VALUE;

        private TierFile(Path path) {
            this.path = path;
        }

        static TierFile open(Path path) throws IOException {
            TierFile file = new TierFile(path);
            file.openChannel();
            return file;
        }

        private void openChannel() throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE);
            try {
                long size = channel.size();
                if (size < HEADER_BYTES) {
                    // 新文件，或者连文件头都没写完整就崩溃了
                    channel.truncate(0);
                    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).putInt(VERSION).flip();
                    writeFully(header, 0);
                    size = HEADER_BYTES;
                } else {
                    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
                    readFully(header, 0);
                    header.flip();
                    if (header.getInt() != MAGIC || header.getInt() != VERSION) {
                        throw new IOException("不是 K 线文件，或版本不受支持：" + path);
                    }
                }
                count = (size - HEADER_BYTES) / RECORD_BYTES;
                // 崩溃时可能留下写了一半的末尾记录
                channel.truncate(HEADER_BYTES + count * RECORD_BYTES);
                lastStart = count == 0 ? Long.MIN_VALUE : startOf(count - 1);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        }

        long startOf(long index) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
            readFully(buffer, HEADER_BYTES + index * RECORD_BYTES);
            return buffer.flip().getLong();
        }

        List<Candle> read(long from, long to) throws IOException {
            int records = (int) (to - from);
            ByteBuffer buffer = ByteBuffer.allocate(records * RECORD_BYTES);
            readFully(buffer, HEADER_BYTES + from * RECORD_BYTES);
            buffer.flip();
            List<Candle> candles = new ArrayList<>(records);
            for (int i = 0; i < records; i++) {
                candles.add(new Candle(buffer.getLong(), buffer.getDouble(), buffer.getDouble(), buffer.getDouble(),
                        buffer.getDouble(), buffer.getDouble()));
            }
            return candles;
        }

        void append(Candle candle) throws IOException {
            ByteBuffer record = encode(List.of(candle));
            writeFully(record, HEADER_BYTES + count * RECORD_BYTES);
            count++;
            lastStart = candle.start();
        }

        void truncateTo(long records) throws IOException {
            if (records == count) {
                return;
            }
            channel.truncate(HEADER_BYTES + records * RECORD_BYTES);
            count = records;
            lastStart = records == 0 ? Long.MIN_VALUE : startOf(records - 1);
        }

        /**
         * 压实：只留最新的 {@code records} 条。先把内容写进临时文件并落盘，再整体替换，中途崩溃不会损坏原文件。
         * 任何一步失败都保证原文件仍然可用（通道重新打开），下一次追加会再次尝试压实。
         */
        void keepLast(int records) throws IOException {
            List<Candle> kept = read(count - records, count);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            ByteBuffer content = ByteBuffer.allocate(HEADER_BYTES + records * RECORD_BYTES);
            content.putInt(MAGIC).putInt(VERSION).put(encode(kept)).flip();
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                while (content.hasRemaining()) {
                    out.write(content);
                }
                out.force(true);
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
            channel.close();
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                // 无论替换成没成功，都要回到可追加的状态；没成功时读到的仍是完整的旧文件
                openChannel();
            }
        }

        void close() throws IOException {
            if (channel != null && channel.isOpen()) {
                channel.force(false);
                channel.close();
            }
        }

        private void readFully(ByteBuffer buffer, long position) throws IOException {
            long at = position;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, at);
                if (read < 0) {
                    throw new IOException("K 线文件意外结束：" + path);
                }
                at += read;
            }
        }

        private void writeFully(ByteBuffer buffer, long position) throws IOException {
            long at = position;
            while (buffer.hasRemaining()) {
                at += channel.write(buffer, at);
            }
        }

        private static ByteBuffer encode(List<Candle> candles) {
            ByteBuffer buffer = ByteBuffer.allocate(candles.size() * RECORD_BYTES);
            for (Candle candle : candles) {
                buffer.putLong(candle.start()).putDouble(candle.open()).putDouble(candle.high())
                        .putDouble(candle.low()).putDouble(candle.close()).putDouble(candle.volume());
            }
            return buffer.flip();
        }

        private static byte[] toArray(ByteBuffer buffer) {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        }
    }
}
