package io.github.djp440.creepercapital.core.kline;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一个合约的 K 线：把行情步按市场时间聚合成各周期的 OHLCV。
 * <p>
 * 只有 1m、1h、1d 三层是存储层；其它周期读取时由所在层聚合得到。行情步只喂给 1m 层「正在走的那一根」，
 * 1m 收盘后聚合成 1h，1h 收盘后聚合成 1d，所以旧的 1m 被淘汰后，它的聚合结果仍然留在更粗的层里。
 * <p>
 * 需要随市场状态保存的只有正在走的 1m；已收盘的各层通过 {@link ClosedListener} 交给存储，启动时再用
 * {@link #loadHistory} 装回。正在走的 1h、1d 由已收盘的下一细层加上正在走的 1m 即时合出。
 */
public final class Klines {
    /** 某一层有 K 线收盘时的回调，存储层据此追加文件。 */
    @FunctionalInterface
    public interface ClosedListener {
        void closed(CandlePeriod tier, Candle candle);
    }

    private static final int MINUTES_PER_DAY = 24 * 60;

    private final Retention retention;
    private final List<Candle> minutes = new ArrayList<>();
    private final List<Candle> hours = new ArrayList<>();
    private final List<Candle> days = new ArrayList<>();
    /** 正在走的 1m；还没有任何行情步时为 null。 */
    private Candle liveMinute;
    private ClosedListener listener = (tier, candle) -> {
    };

    public Klines(Retention retention) {
        this(retention, null);
    }

    /** @param liveMinute 随市场状态恢复的、正在走的 1m；新市场为 null */
    public Klines(Retention retention, Candle liveMinute) {
        this.retention = retention;
        this.liveMinute = liveMinute;
    }

    public void onClosed(ClosedListener listener) {
        this.listener = listener;
    }

    /** 启动时从存储装回某一层已收盘的 K 线（从旧到新），只保留该层保留策略允许的最新若干根；必须在记录行情步之前调用。 */
    public void loadHistory(CandlePeriod tier, List<Candle> closed) {
        List<Candle> target = tier(tier);
        target.clear();
        target.addAll(closed);
        trim(target, retention.of(tier));
    }

    /** 正在走的 1m，随市场状态保存。 */
    public Optional<Candle> liveMinute() {
        return Optional.ofNullable(liveMinute);
    }

    /**
     * 并入一个行情步。
     *
     * @param marketSeconds 该行情步结束时的市场时间（市场秒）
     * @param price         该行情步的价格
     * @param volume        该行情步的成交量
     */
    public void record(long marketSeconds, double price, double volume) {
        long start = CandlePeriod.M1.startOf(marketSeconds);
        if (liveMinute == null) {
            liveMinute = Candle.opening(start, price, volume);
        } else if (liveMinute.start() == start) {
            liveMinute = liveMinute.accept(price, volume);
        } else {
            closeMinute(liveMinute, start);
            liveMinute = Candle.opening(start, price, volume);
        }
    }

    /** 一根 1m 收盘；它若是所在小时、日的最后一根，逐级聚合出小时线、日线。 */
    private void closeMinute(Candle minute, long nextMinuteStart) {
        close(CandlePeriod.M1, minute);
        long hourStart = CandlePeriod.H1.startOf(minute.start());
        if (hourStart == CandlePeriod.H1.startOf(nextMinuteStart)) {
            return;
        }
        close(CandlePeriod.H1, mergeSince(minutes, hourStart));
        long dayStart = CandlePeriod.D1.startOf(hourStart);
        if (dayStart != CandlePeriod.D1.startOf(nextMinuteStart)) {
            close(CandlePeriod.D1, mergeSince(hours, dayStart));
        }
    }

    private void close(CandlePeriod tier, Candle candle) {
        List<Candle> target = tier(tier);
        target.add(candle);
        trim(target, retention.of(tier));
        listener.closed(tier, candle);
    }

    private List<Candle> tier(CandlePeriod tier) {
        return switch (tier) {
            case M1 -> minutes;
            case H1 -> hours;
            case D1 -> days;
            default -> throw new IllegalArgumentException(tier + " 不是存储层");
        };
    }

    private static void trim(List<Candle> candles, int max) {
        if (candles.size() > max) {
            candles.subList(0, candles.size() - max).clear();
        }
    }

    /** 把已收盘的某一层里起点不早于 {@code since} 的 K 线合成一根，起点记为 {@code since}；这样的 K 线至少要有一根。 */
    private static Candle mergeSince(List<Candle> closed, long since) {
        int first = closed.size();
        while (first > 0 && closed.get(first - 1).start() >= since) {
            first--;
        }
        Candle merged = closed.get(first);
        for (int i = first + 1; i < closed.size(); i++) {
            merged = merged.merge(closed.get(i));
        }
        return rebase(merged, since);
    }

    private static Candle rebase(Candle candle, long start) {
        return new Candle(start, candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
    }

    /**
     * 某个周期最新的至多 {@code limit} 根 K 线，从旧到新排列；最后一根是还在更新的当前 K 线。
     * <p>
     * 5m/15m 由 1m 层聚合，4h 由 1h 层聚合，1w 由 1d 层聚合；更细的层被淘汰后，这些周期只能回看到所在层的保留范围。
     */
    public List<Candle> candles(CandlePeriod period, int limit) {
        List<Candle> grouped = group(baseCandles(baseOf(period)), period);
        return new ArrayList<>(grouped.subList(Math.max(0, grouped.size() - limit), grouped.size()));
    }

    private static CandlePeriod baseOf(CandlePeriod period) {
        return switch (period) {
            case M1, M5, M15 -> CandlePeriod.M1;
            case H1, H4 -> CandlePeriod.H1;
            case D1, W1 -> CandlePeriod.D1;
        };
    }

    /** 某一层已收盘的 K 线，加上正在走的那一根（由已收盘的下一细层与正在走的 1m 合出）。 */
    private List<Candle> baseCandles(CandlePeriod base) {
        List<Candle> candles = new ArrayList<>(tier(base));
        if (liveMinute == null) {
            return candles;
        }
        if (base == CandlePeriod.M1) {
            candles.add(liveMinute);
            return candles;
        }
        long hourStart = CandlePeriod.H1.startOf(liveMinute.start());
        Candle liveHour = rebase(liveMinute, hourStart);
        if (!minutes.isEmpty() && minutes.get(minutes.size() - 1).start() >= hourStart) {
            liveHour = mergeSince(minutes, hourStart).merge(liveHour);
        }
        if (base == CandlePeriod.H1) {
            candles.add(liveHour);
            return candles;
        }
        long dayStart = CandlePeriod.D1.startOf(hourStart);
        Candle liveDay = rebase(liveHour, dayStart);
        if (!hours.isEmpty() && hours.get(hours.size() - 1).start() >= dayStart) {
            liveDay = mergeSince(hours, dayStart).merge(liveDay);
        }
        candles.add(liveDay);
        return candles;
    }

    /**
     * 把一个行情步并入某周期的 K 线列表（从旧到新）：落在最后一根里就更新它，跨过周期边界就开出新的一根。
     * 客户端拉取历史后，每收到一个行情步就用它增量更新当前 K 线，结果与服务端重新聚合完全一致。
     */
    public static void advance(List<Candle> candles, CandlePeriod period, long marketSeconds, double price,
            double volume) {
        long start = period.startOf(marketSeconds);
        int last = candles.size() - 1;
        if (last >= 0 && candles.get(last).start() > start) {
            return;
        }
        if (last >= 0 && candles.get(last).start() == start) {
            candles.set(last, candles.get(last).accept(price, volume));
        } else {
            candles.add(Candle.opening(start, price, volume));
        }
    }

    /** 把相邻的细周期 K 线按目标周期的边界合并；最旧的一组若因历史被淘汰而残缺，就舍去。 */
    private static List<Candle> group(List<Candle> base, CandlePeriod period) {
        List<Candle> grouped = new ArrayList<>();
        for (Candle candle : base) {
            long start = period.startOf(candle.start());
            Candle last = grouped.isEmpty() ? null : grouped.get(grouped.size() - 1);
            if (last != null && last.start() == start) {
                grouped.set(grouped.size() - 1, last.merge(candle));
            } else {
                grouped.add(rebase(candle, start));
            }
        }
        if (grouped.size() > 1 && base.get(0).start() != grouped.get(0).start()) {
            grouped.remove(0);
        }
        return grouped;
    }

    /** 最近 24 个市场小时（1440 根 1m）的统计；还没有任何行情步时为空。 */
    public Optional<Stats24h> stats24h() {
        if (liveMinute == null) {
            return Optional.empty();
        }
        long windowStart = liveMinute.start() - (MINUTES_PER_DAY - 1) * CandlePeriod.M1.seconds();
        Candle window = liveMinute;
        for (int i = minutes.size() - 1; i >= 0 && minutes.get(i).start() >= windowStart; i--) {
            window = minutes.get(i).merge(window);
        }
        return Optional.of(new Stats24h(window.open(), window.high(), window.low(), window.close(), window.volume()));
    }
}
