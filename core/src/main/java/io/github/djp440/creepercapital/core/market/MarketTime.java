package io.github.djp440.creepercapital.core.market;

/**
 * 市场时间：市场自己的加速日历，只在服务器运行时流逝（ADR-0003）。
 * <p>
 * 日历从第 1 日 00:00:00 起算，1 个行情步 = 6 市场秒，24 个市场小时 = 1 个市场日。
 *
 * @param totalSeconds 自日历起点以来经过的市场秒数
 */
public record MarketTime(long totalSeconds) {
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE;
    private static final int SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR;

    /** 走过 {@code step} 个行情步后的市场时间。 */
    public static MarketTime ofStep(long step) {
        return new MarketTime(step * Market.MARKET_SECONDS_PER_STEP);
    }

    /** 市场日历的日期，从 1 开始。 */
    public long day() {
        return totalSeconds / SECONDS_PER_DAY + 1;
    }

    public int hour() {
        return (int) (totalSeconds % SECONDS_PER_DAY / SECONDS_PER_HOUR);
    }

    public int minute() {
        return (int) (totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE);
    }

    public int second() {
        return (int) (totalSeconds % SECONDS_PER_MINUTE);
    }
}
