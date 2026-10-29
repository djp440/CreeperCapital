package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：打开交易界面（由 {@code /creeper} 命令发出）。
 */
public record OpenTradingScreenPayload() implements CustomPacketPayload {
    public static final OpenTradingScreenPayload INSTANCE = new OpenTradingScreenPayload();

    public static final Type<OpenTradingScreenPayload> TYPE = new Type<>(CreeperCapital.id("open_trading_screen"));

    public static final StreamCodec<ByteBuf, OpenTradingScreenPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<OpenTradingScreenPayload> type() {
        return TYPE;
    }
}
