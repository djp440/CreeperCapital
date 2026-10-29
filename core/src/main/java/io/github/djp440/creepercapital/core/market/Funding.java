package io.github.djp440.creepercapital.core.market;

/**
 * 资金费率（币安公式）与结算周期：每 8 市场小时结算一次，结算点在市场日的 00:00、08:00、16:00。
 */
public final class Funding {
    /** 资金费结算周期：8 市场小时。 */
    public static final long INTERVAL_SECONDS = 8 * 60 * 60;
    /** 每期利率 I：0.01%。 */
    static final double INTEREST_RATE = 0.0001;
    /** I − P 的钳制幅度：±0.05%。 */
    static final double CLAMP = 0.0005;

    private Funding() {
    }

    /**
     * 资金费率 F = P + clamp(I − P, −0.05%, +0.05%)。
     *
     * @param averagePremiumIndex 本期溢价指数的时间加权均值 P
     */
    public static double rate(double averagePremiumIndex) {
        double difference = INTEREST_RATE - averagePremiumIndex;
        return averagePremiumIndex + Math.max(-CLAMP, Math.min(CLAMP, difference));
    }

    /** 距下一个结算点的市场秒数；恰在结算点时为 0。 */
    public static long secondsUntilNext(MarketTime time) {
        return Math.floorMod(-time.totalSeconds(), INTERVAL_SECONDS);
    }
}
