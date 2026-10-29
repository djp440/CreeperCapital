package io.github.djp440.creepercapital.core.market;

import java.util.List;

/**
 * 双层定价需要持久化的状态：溢价，以及计算资金费率和标记价格用的两组采样。
 *
 * @param premium                 当前溢价（比例）
 * @param premiumIndexSamples     本期已采样的溢价指数个数
 * @param premiumIndexWeightedSum 本期溢价指数按采样序号加权的和（1·P1 + 2·P2 + … + n·Pn）
 * @param basisSamples            最近 30 个市场分钟的基差采样，从旧到新
 */
public record PricingState(double premium, int premiumIndexSamples, double premiumIndexWeightedSum,
        List<Double> basisSamples) {
    /** 新市场：没有溢价，也还没有任何采样。 */
    public static final PricingState INITIAL = new PricingState(0.0, 0, 0.0, List.of());

    public PricingState {
        basisSamples = List.copyOf(basisSamples);
    }
}
