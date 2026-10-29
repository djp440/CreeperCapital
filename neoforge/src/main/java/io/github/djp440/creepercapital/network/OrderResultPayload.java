package io.github.djp440.creepercapital.network;

import java.util.List;

import io.github.djp440.creepercapital.CreeperCapital;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：一次下单的结果，显示在下单面板上。文案在客户端按 {@code message.creepercapital.order.<code>} 翻译，
 * 参数是服务端已格式化的文本。
 *
 * @param success 成交为 true，被拒绝为 false
 * @param code    结果代码：成交为 {@code open_long} / {@code open_short} / {@code close}，拒单为小写的原因名
 * @param args    文案的参数
 */
public record OrderResultPayload(boolean success, String code, List<String> args) implements CustomPacketPayload {
    public static final Type<OrderResultPayload> TYPE = new Type<>(CreeperCapital.id("order_result"));

    public static final StreamCodec<ByteBuf, OrderResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, OrderResultPayload::success,
            ByteBufCodecs.stringUtf8(64), OrderResultPayload::code,
            ByteBufCodecs.stringUtf8(128).apply(ByteBufCodecs.list(16)), OrderResultPayload::args,
            OrderResultPayload::new);

    @Override
    public Type<OrderResultPayload> type() {
        return TYPE;
    }
}
