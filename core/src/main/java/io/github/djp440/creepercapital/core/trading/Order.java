package io.github.djp440.creepercapital.core.trading;

import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 玩家的委托：限价开仓、限价平仓（只减仓）、止盈、止损。限价开仓挂单时冻结保证金和预估手续费，撤单或成交后释放 / 转为仓位保证金；
 * 平仓类委托（只减仓、止盈、止损）不冻结资金，只占用仓位的数量。
 *
 * @param id             委托编号，全服递增
 * @param player         玩家
 * @param symbol         交易代码
 * @param side           所属仓位的方向（限价开多、平多的止盈止损都是 LONG）
 * @param type           委托类型
 * @param price          限价委托的委托价；止盈止损委托的触发价
 * @param lots           数量，以最小数量单位（手）计；平仓类委托成交时按当时的仓位缩减，成交后记录实际成交的数量
 * @param leverage       杠杆倍数；平仓类委托为 0
 * @param frozen         挂着时冻结的资金（保证金 + 按 maker 费率预估的手续费）；终结后为 0；平仓类委托恒为 0
 * @param status         状态
 * @param fillPrice      成交价；未成交为 0
 * @param fee            成交手续费；未成交为 0
 * @param createdSeconds 下单时的市场时间（市场秒）
 * @param closedSeconds  成交或撤销时的市场时间；挂着时为 0
 * @param cancelReason   撤销原因；未撤销为 {@link CancelReason#NONE}
 * @param extras         附加条款：只减仓、触发价来源、附带的止盈止损
 */
public record Order(long id, UUID player, String symbol, Side side, OrderType type, double price, long lots,
        int leverage, Money frozen, OrderStatus status, double fillPrice, Money fee, long createdSeconds,
        long closedSeconds, CancelReason cancelReason, OrderExtras extras) {
    /** 没有附加条款的委托（普通限价开仓）。 */
    public Order(long id, UUID player, String symbol, Side side, OrderType type, double price, long lots, int leverage,
            Money frozen, OrderStatus status, double fillPrice, Money fee, long createdSeconds, long closedSeconds,
            CancelReason cancelReason) {
        this(id, player, symbol, side, type, price, lots, leverage, frozen, status, fillPrice, fee, createdSeconds,
                closedSeconds, cancelReason, OrderExtras.NONE);
    }

    public boolean isOpen() {
        return status == OrderStatus.OPEN;
    }

    /** 是只减仓委托（含止盈止损）：只缩减仓位，不开仓。 */
    public boolean reduceOnly() {
        return extras.reduceOnly();
    }

    public TriggerSource triggerSource() {
        return extras.triggerSource();
    }

    Order filled(double fillPrice, Money fee, long marketSeconds) {
        return filled(fillPrice, fee, lots, marketSeconds);
    }

    /** 成交；平仓类委托按仓位缩减后，{@code executedLots} 是实际成交的数量。 */
    Order filled(double fillPrice, Money fee, long executedLots, long marketSeconds) {
        return new Order(id, player, symbol, side, type, price, executedLots, leverage, Money.ZERO, OrderStatus.FILLED,
                fillPrice, fee, createdSeconds, marketSeconds, CancelReason.NONE, extras);
    }

    Order cancelled(CancelReason reason, long marketSeconds) {
        return new Order(id, player, symbol, side, type, price, lots, leverage, Money.ZERO, OrderStatus.CANCELLED, 0,
                Money.ZERO, createdSeconds, marketSeconds, reason, extras);
    }
}
