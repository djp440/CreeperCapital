package io.github.djp440.creepercapital.core.market;

/**
 * 合约定义里与下单有关的部分，同步给客户端的下单面板。
 *
 * @param symbol           交易代码
 * @param spread           点差（CRP）
 * @param maxLeverage      最高杠杆倍数
 * @param quantityDecimals 数量精度（小数位数）
 * @param depthSlippage    流动性深度（见 {@link ContractSpec#depthSlippage()}）：客户端据此与服务端同源地算出盘口
 */
public record TradingParams(String symbol, double spread, int maxLeverage, int quantityDecimals, double depthSlippage) {
    /** 以 {@code lastPrice} 为最新价格的虚拟流动性曲线；服务端成交和客户端盘口用的是同一份。 */
    public LiquidityCurve liquidityCurve(double lastPrice) {
        return LiquidityCurve.of(spread, depthSlippage, quantityDecimals, lastPrice);
    }
}
