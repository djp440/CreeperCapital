package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：某个行情步结束时的市场快照。
 */
public record MarketSnapshotPayload(MarketSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<MarketSnapshotPayload> TYPE = new Type<>(CreeperCapital.id("market_snapshot"));

    /** core 不依赖 MC，快照的编解码写在适配层。 */
    private static final StreamCodec<ByteBuf, MarketSnapshot> SNAPSHOT_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MarketSnapshot::step,
            ByteBufCodecs.STRING_UTF8, MarketSnapshot::symbol,
            ByteBufCodecs.DOUBLE, MarketSnapshot::indexPrice,
            ByteBufCodecs.DOUBLE, MarketSnapshot::lastPrice,
            ByteBufCodecs.DOUBLE, MarketSnapshot::markPrice,
            ByteBufCodecs.DOUBLE, MarketSnapshot::fundingRate,
            MarketSnapshot::new);

    public static final StreamCodec<ByteBuf, MarketSnapshotPayload> STREAM_CODEC =
            SNAPSHOT_CODEC.map(MarketSnapshotPayload::new, MarketSnapshotPayload::snapshot);

    @Override
    public Type<MarketSnapshotPayload> type() {
        return TYPE;
    }
}
