package io.github.djp440.creepercapital.core.market;

/**
 * 某个行情步结束时的市场状态。
 *
 * @param step       自市场创建以来经过的行情步数
 * @param symbol     合约的交易代码
 * @param indexPrice 该合约的指数价格（CRP）
 */
public record MarketSnapshot(long step, String symbol, double indexPrice) {

    /** 此刻的市场时间：每个行情步 6 市场秒。 */
    public MarketTime time() {
        return MarketTime.ofStep(step);
    }
}
