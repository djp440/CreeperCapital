package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：请求某个合约某个周期最近的历史 K 线（打开交易界面、切换合约或周期时发出）。
 */
public record KlineRequestPayload(String symbol, CandlePeriod period) implements CustomPacketPayload {
    public static final Type<KlineRequestPayload> TYPE = new Type<>(CreeperCapital.id("kline_request"));

    public static final StreamCodec<ByteBuf, KlineRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ModCodecs.SYMBOL, KlineRequestPayload::symbol,
            ModCodecs.PERIOD, KlineRequestPayload::period,
            KlineRequestPayload::new);

    @Override
    public Type<KlineRequestPayload> type() {
        return TYPE;
    }
}
