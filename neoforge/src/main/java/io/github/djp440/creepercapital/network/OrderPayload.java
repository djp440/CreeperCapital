package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：玩家的交易意图（市价开多、开空、平仓，以及切换保证金模式、调整杠杆）。服务端校验一切，不信任客户端；
 * 平仓时杠杆字段被忽略。
 *
 * @param action   动作
 * @param symbol   交易代码
 * @param lots     数量，以合约的最小数量单位（手）计
 * @param leverage 杠杆倍数
 */
public record OrderPayload(Action action, String symbol, long lots, int leverage) implements CustomPacketPayload {
    public static final Type<OrderPayload> TYPE = new Type<>(CreeperCapital.id("order"));

    public enum Action {
        OPEN_LONG,
        OPEN_SHORT,
        CLOSE,
        /** 把这个合约的保证金模式设为全仓（{@code lots} 与 {@code leverage} 被忽略）。 */
        SET_CROSS,
        /** 把这个合约的保证金模式设为逐仓（{@code lots} 与 {@code leverage} 被忽略）。 */
        SET_ISOLATED,
        /** 把已有仓位的杠杆调成 {@code leverage}（{@code lots} 被忽略）。 */
        SET_LEVERAGE;

        /** 是保证金设置（切换模式、调杠杆）而不是成交。 */
        public boolean isMarginSetting() {
            return this == SET_CROSS || this == SET_ISOLATED || this == SET_LEVERAGE;
        }
    }

    private static final StreamCodec<ByteBuf, Action> ACTION =
            ByteBufCodecs.VAR_INT.map(ordinal -> Action.values()[Math.floorMod(ordinal, Action.values().length)], Action::ordinal);

    public static final StreamCodec<ByteBuf, OrderPayload> STREAM_CODEC = StreamCodec.composite(
            ACTION, OrderPayload::action,
            ModCodecs.SYMBOL, OrderPayload::symbol,
            ByteBufCodecs.VAR_LONG, OrderPayload::lots,
            ByteBufCodecs.VAR_INT, OrderPayload::leverage,
            OrderPayload::new);

    @Override
    public Type<OrderPayload> type() {
        return TYPE;
    }
}
