package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：玩家的委托意图（开多、开空、平仓；市价或限价）。服务端校验一切，不信任客户端；
 * 平仓时杠杆字段被忽略，市价单时价格字段被忽略，目前限价只支持开仓。
 *
 * @param action    动作
 * @param symbol    交易代码
 * @param lots      数量，以合约的最小数量单位（手）计
 * @param leverage  杠杆倍数
 * @param execution 市价还是限价
 * @param price     限价委托的委托价
 */
public record OrderPayload(Action action, String symbol, long lots, int leverage, Execution execution, double price)
        implements CustomPacketPayload {
    public static final Type<OrderPayload> TYPE = new Type<>(CreeperCapital.id("order"));

    public enum Action {
        OPEN_LONG,
        OPEN_SHORT,
        CLOSE
    }

    /** 委托的执行方式：市价立即成交，限价挂在服务端等价格穿越。 */
    public enum Execution {
        MARKET,
        LIMIT
    }

    /** 市价委托。 */
    public OrderPayload(Action action, String symbol, long lots, int leverage) {
        this(action, symbol, lots, leverage, Execution.MARKET, 0);
    }

    private static final StreamCodec<ByteBuf, Action> ACTION =
            ByteBufCodecs.VAR_INT.map(ordinal -> Action.values()[Math.floorMod(ordinal, Action.values().length)], Action::ordinal);

    private static final StreamCodec<ByteBuf, Execution> EXECUTION =
            ByteBufCodecs.VAR_INT.map(ordinal -> Execution.values()[Math.floorMod(ordinal, Execution.values().length)], Execution::ordinal);

    public static final StreamCodec<ByteBuf, OrderPayload> STREAM_CODEC = StreamCodec.composite(
            ACTION, OrderPayload::action,
            ModCodecs.SYMBOL, OrderPayload::symbol,
            ByteBufCodecs.VAR_LONG, OrderPayload::lots,
            ByteBufCodecs.VAR_INT, OrderPayload::leverage,
            EXECUTION, OrderPayload::execution,
            ByteBufCodecs.DOUBLE, OrderPayload::price,
            OrderPayload::new);

    @Override
    public Type<OrderPayload> type() {
        return TYPE;
    }
}
