package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：撤销自己的一张挂单。服务端只认委托编号属于该玩家的挂单；市场暂停时也可以撤单（只是释放资金）。
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
