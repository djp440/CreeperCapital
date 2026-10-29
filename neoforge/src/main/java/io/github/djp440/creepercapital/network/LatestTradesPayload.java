package io.github.djp440.creepercapital.network;

import java.util.List;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.market.MarketTrade;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：「最新成交」里新增的若干笔，旧的在前。发给所有在线玩家：每个行情步一包模拟成交、玩家成交一笔一包，
 * 登录时再补发各合约最近的缓冲。客户端按交易代码分别追加，只保留最近若干笔。
 */
public record LatestTradesPayload(List<MarketTrade> trades) implements CustomPacketPayload {
    /** 一包最多多少笔：登录补发是每合约 50 笔，留足余量。 */
    public static final int MAX_TRADES = 1_000;

    public static final Type<LatestTradesPayload> TYPE = new Type<>(CreeperCapital.id("latest_trades"));

    public static final StreamCodec<ByteBuf, LatestTradesPayload> STREAM_CODEC = ModCodecs.MARKET_TRADE
            .apply(ByteBufCodecs.list(MAX_TRADES)).map(LatestTradesPayload::new, LatestTradesPayload::trades);

    @Override
    public Type<LatestTradesPayload> type() {
        return TYPE;
    }
}
