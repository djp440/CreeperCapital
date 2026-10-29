package io.github.djp440.creepercapital.core.kline;

/**
 * 三层 K 线各保留多少根：旧数据逐级聚合（1m → 1h → 1d），每层只留最近若干根，所以存储体积有上界。
 * <p>
 * 5m/15m 由 1m 层聚合，4h 由 1h 层聚合，1w 由 1d 层聚合，所以更细的周期只能回看到所在层的保留范围。
 *
 * @param minutes 1m 层保留的根数，至少一个市场日（24h 统计要用）
 * @param hours   1h 层保留的根数，至少一个市场日（日 K 要由它聚合）
 * @param days    1d 层保留的根数，至少 1 根
 */
public record Retention(int minutes, int hours, int days) {
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int HOURS_PER_DAY = 24;

    /** 默认：1m 留 7 个市场日，1h 留 90 个市场日，1d 留 3650 个市场日（10 个市场年）。 */
    public static final Retention DEFAULT = new Retention(7 * MINUTES_PER_DAY, 90 * HOURS_PER_DAY, 3_650);

    public Retention {
        if (minutes < MINUTES_PER_DAY) {
            throw new IllegalArgumentException("1m 层至少保留一个市场日（" + MINUTES_PER_DAY + " 根），实际 " + minutes);
        }
        if (hours < HOURS_PER_DAY) {
            throw new IllegalArgumentException("1h 层至少保留一个市场日（" + HOURS_PER_DAY + " 根），实际 " + hours);
        }
        if (days < 1) {
            throw new IllegalArgumentException("1d 层至少保留 1 根，实际 " + days);
        }
    }

    /** 某一层保留的根数；只有 1m/1h/1d 是存储层。 */
    public int of(CandlePeriod tier) {
        return switch (tier) {
            case M1 -> minutes;
            case H1 -> hours;
            case D1 -> days;
            default -> throw new IllegalArgumentException(tier + " 不是存储层");
        };
    }
}
