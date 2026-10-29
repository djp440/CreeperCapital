package io.github.djp440.creepercapital.core.trading;

import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 委托触发、成交或撤销时产出的结构化记录，供通知和 #19「离线事件汇总」使用（别只拼字符串）。
 * 止盈止损触发会先后产出 {@link Kind#TRIGGERED}（价格是触发价）和 {@link Kind#FILLED}（价格是市价成交价）两条。
 *
 * @param player        玩家
 * @param kind          触发、成交还是撤销
 * @param orderId       委托编号
 * @param symbol        交易代码
 * @param side          所属仓位的方向
 * @param price         触发时是触发价，成交时是成交价，撤销时是委托价
 * @param lots          数量（手）；平仓类委托成交时是实际成交的数量
 * @param amount        金额：成交时是手续费，撤销时是释放回可用余额的冻结资金，触发时为 0
 * @param marketSeconds 发生时的市场时间（市场秒）
 * @param cancelReason  撤销原因；其它为 {@link CancelReason#NONE}
 * @param type          委托类型（限价、止盈、止损）
 * @param triggerSource 触发价来源（止盈止损有意义）
 * @param realizedPnl   成交平仓时的已实现盈亏；其它为 0
 */
public record OrderEvent(UUID player, Kind kind, long orderId, String symbol, Side side, double price, long lots,
        Money amount, long marketSeconds, CancelReason cancelReason, OrderType type, TriggerSource triggerSource,
        Money realizedPnl) {
    public enum Kind {
        /** 止盈止损的触发价到了，即将按市价平仓。 */
        TRIGGERED,
        FILLED,
        CANCELLED
    }

    /** 限价开仓类事件（没有触发价来源与已实现盈亏）。 */
    public OrderEvent(UUID player, Kind kind, long orderId, String symbol, Side side, double price, long lots,
            Money amount, long marketSeconds, CancelReason cancelReason) {
        this(player, kind, orderId, symbol, side, price, lots, amount, marketSeconds, cancelReason, OrderType.LIMIT,
                TriggerSource.LAST, Money.ZERO);
    }
}
