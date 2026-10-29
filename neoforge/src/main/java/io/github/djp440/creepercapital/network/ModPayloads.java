package io.github.djp440.creepercapital.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 本模组的网络载荷。客户端处理器在 {@code CreeperCapitalClient} 里单独注册，专用服务端不加载客户端类。
 */
public final class ModPayloads {
    private static final String PROTOCOL_VERSION = "1";

    private ModPayloads() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(ModPayloads::onRegisterPayloads);
    }

    /** 发给一名玩家；对方连接没有协商本模组的通道时跳过。 */
    public static void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION)
                .playToClient(MarketSnapshotPayload.TYPE, MarketSnapshotPayload.STREAM_CODEC)
                .playToClient(OpenTradingScreenPayload.TYPE, OpenTradingScreenPayload.STREAM_CODEC);
    }
}
