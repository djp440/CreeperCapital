package io.github.djp440.creepercapital.core.trading;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 下单面板对一笔市价开仓的预估，由 {@link TradingBook#estimateOpen} 按实际开仓的同一套算法得出。
 *
 * @param price    成交均价（沿虚拟流动性曲线逐档吃单）
 * @param notional 名义价值
 * @param margin   保证金（名义价值 / 杠杆，向上取整）
 * @param fee      taker 手续费
 */
public record OpenEstimate(double price, Money notional, Money margin, Money fee) {
}
