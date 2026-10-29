package io.github.djp440.creepercapital.core.kline;

/**
 * K 线周期，按市场时间计。边界都对齐市场日历的起点（第 1 日 00:00:00）。
 * <p>
 * 市场日历没有星期，周 K 以第 1 日为一周的第一天，每 7 个市场日一根。
 */
public enum CandlePeriod {
    M1("1m", 60),
    M5("5m", 5 * 60),
    M15("15m", 15 * 60),
    H1("1h", 60 * 60),
    H4("4h", 4 * 60 * 60),
    D1("1d", 24 * 60 * 60),
    W1("1w", 7 * 24 * 60 * 60);

    private final String label;
    private final long seconds;

    CandlePeriod(String label, long seconds) {
        this.label = label;
        this.seconds = seconds;
    }

    /** 界面上显示的周期名，例如 {@code 15m}。 */
    public String label() {
        return label;
    }

    /** 周期时长（市场秒）。 */
    public long seconds() {
        return seconds;
    }

    /** 包含该市场时刻的 K 线的起点（市场秒）；恰在边界的时刻属于新的一根。 */
    public long startOf(long marketSeconds) {
        return Math.floorDiv(marketSeconds, seconds) * seconds;
    }
}
