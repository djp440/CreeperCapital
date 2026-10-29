package io.github.djp440.creepercapital.network;

import java.util.List;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：玩家自己的交易状态（登录时、成交后发送）：各合约的下单参数（含维持保证金分档）、全部仓位、最近的成交（最旧的在前）、
 * 委托（挂着的和最近的历史委托，按编号从小到大），以及玩家设为全仓的合约（没有仓位的合约，保证金模式也要让下单面板知道；
 * 有仓位的合约以仓位自带的模式为准）。
 * 仓位的未实现盈亏、保证金率和强平价不在这里，客户端按每个行情步的标记价格自己算。
 */
public record TradingStatePayload(List<TradingParams> contracts, List<Position> positions, List<PlayerTrade> trades,
        List<Order> orders, List<String> crossSymbols) implements CustomPacketPayload {
    public static final Type<TradingStatePayload> TYPE = new Type<>(CreeperCapital.id("trading_state"));

    public static final StreamCodec<ByteBuf, TradingStatePayload> STREAM_CODEC = StreamCodec.composite(
            ModCodecs.TRADING_PARAMS.apply(ByteBufCodecs.list()), TradingStatePayload::contracts,
            ModCodecs.POSITION.apply(ByteBufCodecs.list()), TradingStatePayload::positions,
            ModCodecs.TRADE.apply(ByteBufCodecs.list()), TradingStatePayload::trades,
            ModCodecs.ORDER.apply(ByteBufCodecs.list()), TradingStatePayload::orders,
            ModCodecs.SYMBOL.apply(ByteBufCodecs.list()), TradingStatePayload::crossSymbols,
            TradingStatePayload::new);

    @Override
    public Type<TradingStatePayload> type() {
        return TYPE;
    }
}
