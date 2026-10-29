package io.github.djp440.creepercapital.core.trading;

import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 玩家的限价委托（目前只有开仓方向）。挂单时冻结保证金和预估手续费，撤单或成交后释放 / 转为仓位保证金。
 *
 * @param id             委托编号，全服递增
 * @param player         玩家
 * @param symbol         交易代码
 * @param side           开仓方向（开多是买单，开空是卖单）
 * @param type           委托类型
 * @param price          委托价
 * @param lots           数量，以最小数量单位（手）计
 * @param leverage       杠杆倍数
 * @param frozen         挂着时冻结的资金（保证金 + 按 maker 费率预估的手续费）；终结后为 0
 * @param status         状态
 * @param fillPrice      成交价；未成交为 0
 * @param fee            成交手续费；未成交为 0
 * @param createdSeconds 下单时的市场时间（市场秒）
 * @param closedSeconds  成交或撤销时的市场时间；挂着时为 0
 * @param cancelReason   撤销原因；未撤销为 {@link CancelReason#NONE}
 */
public record Order(long id, UUID player, String symbol, Side side, OrderType type, double price, long lots,
        int leverage, Money frozen, OrderStatus status, double fillPrice, Money fee, long createdSeconds,
        long closedSeconds, CancelReason cancelReason) {
    public boolean isOpen() {
        return status == OrderStatus.OPEN;
    }

    Order filled(double fillPrice, Money fee, long marketSeconds) {
        return new Order(id, player, symbol, side, type, price, lots, leverage, Money.ZERO, OrderStatus.FILLED, fillPrice,
                fee, createdSeconds, marketSeconds, CancelReason.NONE);
    }

    Order cancelled(CancelReason reason, long marketSeconds) {
        return new Order(id, player, symbol, side, type, price, lots, leverage, Money.ZERO, OrderStatus.CANCELLED, 0,
                Money.ZERO, createdSeconds, marketSeconds, reason);
    }
}
