package io.github.djp440.creepercapital.core.market;

/**
 * 占位的指数价格：几何随机游走，日波动约 3%（按市场日计）。
 * <p>
 * 只用来打通行走骨架，后续由组合随机行情模型替换。
 */
final class RandomWalkIndexPrice {
    private static final int STEPS_PER_MARKET_DAY = 24 * 60 * 60 / Market.MARKET_SECONDS_PER_STEP;
    private static final double DAILY_VOLATILITY = 0.03;
    private static final double STEP_VOLATILITY = DAILY_VOLATILITY / Math.sqrt(STEPS_PER_MARKET_DAY);
    /** 平静时每个行情步的成交量（钻石个数）。 */
    private static final double BASE_VOLUME = 5.0;

    private final MarketRandom random;
    private double price;

    RandomWalkIndexPrice(double price, MarketRandom random) {
        this.price = price;
        this.random = random;
    }

    double current() {
        return price;
    }

    /**
     * 走一个行情步。
     *
     * @return 这一步的对数收益率
     */
    double next() {
        double logReturn = STEP_VOLATILITY * random.nextGaussian();
        price *= Math.exp(logReturn);
        return logReturn;
    }

    /**
     * 占位成交量：基础量 × (1 + |收益率| / 步波动率)，恒为正，与波动正相关。
     * 只用来让 K 线和 24h 统计有数可看，后续由行情模型自己生成成交量替换。
     */
    static double volumeFor(double logReturn) {
        return BASE_VOLUME * (1 + Math.abs(logReturn) / STEP_VOLATILITY);
    }
}
