package io.github.djp440.creepercapital.core.market;

/**
 * 标记价格：取三个候选价的中位数，抗短时操纵。
 */
public final class MarkPrice {
    private MarkPrice() {
    }

    /**
     * 标记价格 = 中位数(价格 1, 价格 2, 最新价格)，其中
     * 价格 1 = 指数 × (1 + 资金费率 × 距结算时间 / 结算周期)，价格 2 = 指数 + 基差移动平均。
     *
     * @param indexPrice          指数价格
     * @param fundingRate         最近一期资金费率（本期实时预估）
     * @param secondsUntilFunding 距下次结算的市场秒数
     * @param basisMovingAverage  基差（最新价格 − 指数价格）的移动平均
     * @param lastPrice           最新价格
     */
    public static double of(double indexPrice, double fundingRate, long secondsUntilFunding,
            double basisMovingAverage, double lastPrice) {
        double fundingPrice = indexPrice * (1 + fundingRate * secondsUntilFunding / Funding.INTERVAL_SECONDS);
        double basisPrice = indexPrice + basisMovingAverage;
        return median(fundingPrice, basisPrice, lastPrice);
    }

    private static double median(double a, double b, double c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }
}
