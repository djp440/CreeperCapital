package io.github.djp440.creepercapital.core.market;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 双层定价（ADR-0002）：在指数价格上叠加溢价得到最新价格，再按标准永续合约公式推导资金费率和标记价格。
 * <p>
 * 溢价是围绕「偏移目标」的均值回归随机过程（离散 Ornstein-Uhlenbeck），半衰期 1 市场小时，平稳标准差约 0.1%；
 * 偏移目标随市场状态变化：牛市偏正，熊市偏负，震荡围绕 0（幅度见 {@link VolatilityProfile#premiumBias()}）。
 * 还没有盘口，溢价指数直接取溢价本身，基差取最新价格 − 指数价格。
 */
final class ContractPricing {
    private static final int HALF_LIFE_STEPS = 60 * 60 / Market.MARKET_SECONDS_PER_STEP;
    private static final double REVERSION = 1 - Math.pow(0.5, 1.0 / HALF_LIFE_STEPS);
    private static final double STATIONARY_DEVIATION = 0.001;
    /** 让 AR(1) 过程的平稳标准差恰为 {@link #STATIONARY_DEVIATION}。 */
    private static final double STEP_VOLATILITY =
            STATIONARY_DEVIATION * Math.sqrt(1 - (1 - REVERSION) * (1 - REVERSION));

    /** 基差每市场分钟采样一次，移动平均取最近 30 分钟。 */
    private static final long BASIS_SAMPLE_SECONDS = 60;
    private static final int BASIS_WINDOW = 30;

    private double premium;
    private int premiumIndexSamples;
    private double premiumIndexWeightedSum;
    private final Deque<Double> basisSamples;

    ContractPricing(PricingState state) {
        this.premium = state.premium();
        this.premiumIndexSamples = state.premiumIndexSamples();
        this.premiumIndexWeightedSum = state.premiumIndexWeightedSum();
        this.basisSamples = new ArrayDeque<>(state.basisSamples());
    }

    PricingState state() {
        return new PricingState(premium, premiumIndexSamples, premiumIndexWeightedSum, basisSamples.stream().toList());
    }

    /**
     * 走一个行情步：溢价随机变化，并采样溢价指数和基差。
     *
     * @param previous   这一步之前的市场时间；它若是结算点，本步开始新一期的溢价指数采样
     * @param now        这一步之后的市场时间
     * @param indexPrice 这一步之后的指数价格
     * @param bias       溢价此刻回归的目标（比例），由市场状态决定
     */
    void next(MarketTime previous, MarketTime now, double indexPrice, MarketRandom random, double bias) {
        if (Funding.secondsUntilNext(previous) == 0) {
            premiumIndexSamples = 0;
            premiumIndexWeightedSum = 0;
        }
        premium = bias + (premium - bias) * (1 - REVERSION) + STEP_VOLATILITY * random.nextGaussian();
        premiumIndexSamples++;
        premiumIndexWeightedSum += premiumIndexSamples * premium;
        if (now.totalSeconds() % BASIS_SAMPLE_SECONDS == 0) {
            basisSamples.addLast(lastPrice(indexPrice) - indexPrice);
            if (basisSamples.size() > BASIS_WINDOW) {
                basisSamples.removeFirst();
            }
        }
    }

    double lastPrice(double indexPrice) {
        return indexPrice * (1 + premium);
    }

    /** 本期实时预估的资金费率；本期还没有采样时按当前溢价估算。 */
    double fundingRate() {
        if (premiumIndexSamples == 0) {
            return Funding.rate(premium);
        }
        double totalWeight = premiumIndexSamples * (premiumIndexSamples + 1) / 2.0;
        return Funding.rate(premiumIndexWeightedSum / totalWeight);
    }

    double markPrice(double indexPrice, MarketTime time) {
        double lastPrice = lastPrice(indexPrice);
        double basisAverage = basisSamples.isEmpty()
                ? lastPrice - indexPrice
                : basisSamples.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return MarkPrice.of(indexPrice, fundingRate(), Funding.secondsUntilNext(time), basisAverage, lastPrice);
    }
}
