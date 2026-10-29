package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：玩家自己的资产（登录时、余额变动时发送）。金额以最小单位的整数传输，无浮点误差。
 */
public record AccountPayload(Assets assets) implements CustomPacketPayload {
    public static final Type<AccountPayload> TYPE = new Type<>(CreeperCapital.id("account"));

    private static final StreamCodec<ByteBuf, Money> MONEY = ByteBufCodecs.VAR_LONG.map(Money::ofUnits, Money::units);

    private static final StreamCodec<ByteBuf, Assets> ASSETS = StreamCodec.composite(
            MONEY, Assets::walletBalance,
            MONEY, Assets::unrealizedPnl,
            MONEY, Assets::marginBalance,
            MONEY, Assets::availableBalance,
            Assets::new);

    public static final StreamCodec<ByteBuf, AccountPayload> STREAM_CODEC = ASSETS.map(AccountPayload::new, AccountPayload::assets);

    @Override
    public Type<AccountPayload> type() {
        return TYPE;
    }
}
