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

    private final MarketRandom random;
    private double price;

    RandomWalkIndexPrice(double initialPrice, long seed) {
        this(initialPrice, new MarketRandom(seed));
    }

    RandomWalkIndexPrice(double price, MarketRandom random) {
        this.price = price;
        this.random = random;
    }

    double current() {
        return price;
    }

    /** 随机数发生器当前的状态，随存档保存。 */
    long randomState() {
        return random.state();
    }

    /** 走一个行情步。 */
    void next() {
        price *= Math.exp(STEP_VOLATILITY * random.nextGaussian());
    }
}
