package io.github.djp440.creepercapital.core.market;

/**
 * 某个行情步结束时的市场状态。
 *
 * @param step        自市场创建以来经过的行情步数
 * @param symbol      合约的交易代码
 * @param indexPrice  该合约的指数价格（CRP）
 * @param lastPrice   最新价格 = 指数价格 × (1 + 溢价)
 * @param markPrice   标记价格
 * @param fundingRate 本期实时预估的资金费率（比例，0.0001 即 0.01%）
 */
public record MarketSnapshot(long step, String symbol, double indexPrice, double lastPrice, double markPrice,
        double fundingRate) {

    /** 此刻的市场时间：每个行情步 6 市场秒。 */
    public MarketTime time() {
        return MarketTime.ofStep(step);
    }

    /** 距下次资金费结算的市场秒数；恰在结算点时为 0。 */
    public long secondsUntilFunding() {
        return Funding.secondsUntilNext(time());
    }
}
