package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：撤销自己的一张当前委托（挂单、止盈或止损；「当前委托」页的撤销与图表 tag 上的「×」都走这里）。
 * 服务端只认委托编号属于该玩家、仍未结束的委托，否则回报 {@code no_such_order}；市场暂停时也可以撤单（只是释放资金）。
 *
 * @param orderId 委托编号
 */
public record CancelOrderPayload(long orderId) implements CustomPacketPayload {
    public static final Type<CancelOrderPayload> TYPE = new Type<>(CreeperCapital.id("cancel_order"));

    public static final StreamCodec<ByteBuf, CancelOrderPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, CancelOrderPayload::orderId,
            CancelOrderPayload::new);

    @Override
    public Type<CancelOrderPayload> type() {
        return TYPE;
    }
}
