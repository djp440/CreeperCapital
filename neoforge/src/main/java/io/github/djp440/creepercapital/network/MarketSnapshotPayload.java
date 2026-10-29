package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：某个行情步结束时某个合约的市场快照，以及此刻的 24h 统计（行情栏用）。每个行情步每个合约一个包。
 */
public record MarketSnapshotPayload(MarketSnapshot snapshot, Stats24h stats24h) implements CustomPacketPayload {
    public static final Type<MarketSnapshotPayload> TYPE = new Type<>(CreeperCapital.id("market_snapshot"));

    /** core 不依赖 MC，快照的编解码写在适配层。 */
    private static final StreamCodec<ByteBuf, MarketSnapshot> SNAPSHOT_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MarketSnapshot::step,
            ModCodecs.SYMBOL, MarketSnapshot::symbol,
            ByteBufCodecs.DOUBLE, MarketSnapshot::indexPrice,
            ByteBufCodecs.DOUBLE, MarketSnapshot::lastPrice,
            ByteBufCodecs.DOUBLE, MarketSnapshot::markPrice,
            ByteBufCodecs.DOUBLE, MarketSnapshot::fundingRate,
            ByteBufCodecs.DOUBLE, MarketSnapshot::volume,
            MarketSnapshot::new);

    public static final StreamCodec<ByteBuf, MarketSnapshotPayload> STREAM_CODEC = StreamCodec.composite(
            SNAPSHOT_CODEC, MarketSnapshotPayload::snapshot,
            ModCodecs.STATS_24H, MarketSnapshotPayload::stats24h,
            MarketSnapshotPayload::new);

    @Override
    public Type<MarketSnapshotPayload> type() {
        return TYPE;
    }
}
