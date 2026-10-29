package io.github.djp440.creepercapital.network;

import java.util.List;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：某个合约某个周期最近的历史 K 线，从旧到新，最后一根是还在更新的当前 K 线。
 * 之后客户端靠每个行情步的快照增量更新当前 K 线。
 */
public record KlineHistoryPayload(String symbol, CandlePeriod period, List<Candle> candles)
        implements CustomPacketPayload {
    /** 一次最多传多少根，约 46 字节一根。 */
    public static final int MAX_CANDLES = 1_000;

    public static final Type<KlineHistoryPayload> TYPE = new Type<>(CreeperCapital.id("kline_history"));

    public static final StreamCodec<ByteBuf, KlineHistoryPayload> STREAM_CODEC = StreamCodec.composite(
            ModCodecs.SYMBOL, KlineHistoryPayload::symbol,
            ModCodecs.PERIOD, KlineHistoryPayload::period,
            ModCodecs.CANDLE.apply(ByteBufCodecs.list(MAX_CANDLES)), KlineHistoryPayload::candles,
            KlineHistoryPayload::new);

    @Override
    public Type<KlineHistoryPayload> type() {
        return TYPE;
    }
}
