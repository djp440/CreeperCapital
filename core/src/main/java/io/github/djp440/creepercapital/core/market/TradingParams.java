package io.github.djp440.creepercapital.core.market;

/**
 * 合约定义里与下单有关的部分，同步给客户端的下单面板。
 *
 * @param symbol           交易代码
 * @param spread           点差（CRP）
 * @param maxLeverage      最高杠杆倍数
 * @param quantityDecimals 数量精度（小数位数）
 * @param marginTiers      维持保证金与杠杆上限的分档：界面据此算保证金率和预估强平价，口径与服务端一致
 */
public record TradingParams(String symbol, double spread, int maxLeverage, int quantityDecimals, MarginTiers marginTiers) {
}
