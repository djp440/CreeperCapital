package io.github.djp440.creepercapital.core.trading;

import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 委托成交或撤销时产出的结构化记录，供通知和 #19「离线事件汇总」使用（别只拼字符串）。
 *
 * @param player        玩家
 * @param kind          成交还是撤销
 * @param orderId       委托编号
 * @param symbol        交易代码
 * @param side          开仓方向
 * @param price         成交时是成交价，撤销时是委托价
 * @param lots          数量（手）
 * @param amount        金额：成交时是手续费，撤销时是释放回可用余额的冻结资金
 * @param marketSeconds 发生时的市场时间（市场秒）
 * @param cancelReason  撤销原因；成交时为 {@link CancelReason#NONE}
 */
public record OrderEvent(UUID player, Kind kind, long orderId, String symbol, Side side, double price, long lots,
        Money amount, long marketSeconds, CancelReason cancelReason) {
    public enum Kind {
        FILLED,
        CANCELLED
    }
}
