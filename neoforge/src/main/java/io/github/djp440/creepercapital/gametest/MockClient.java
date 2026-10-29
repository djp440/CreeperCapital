package io.github.djp440.creepercapital.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.github.djp440.creepercapital.network.AccountPayload;
import io.github.djp440.creepercapital.network.KlineHistoryPayload;
import io.github.djp440.creepercapital.network.MarketSnapshotPayload;
import io.github.djp440.creepercapital.network.OpenTradingScreenPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.network.TradingStatePayload;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

/**
 * GameTest 用的模拟客户端：一名在线玩家，连接另一端是 {@link EmbeddedChannel}，服务端发给它的包都留在出站队列里。
 */
final class MockClient {
    private MockClient() {
    }

    /** 让一名装有本模组的模拟玩家进服。 */
    static ServerPlayer join(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        // 模拟连接没有走 NeoForge 通道协商；把本模组的载荷登记为 ad-hoc 通道，等同于装了本模组的客户端。
        // ChannelAttributes 是 NeoForge 内部 API，只在测试里使用。
        ChannelAttributes.getOrCreateAdHocChannels(connection)
                .addAll(List.of(MarketSnapshotPayload.TYPE.id(), OpenTradingScreenPayload.TYPE.id(),
                        KlineHistoryPayload.TYPE.id(), AccountPayload.TYPE.id(), TradingStatePayload.TYPE.id(),
                        OrderResultPayload.TYPE.id()));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    static void leave(ServerPlayer player) {
        player.level().getServer().getPlayerList().remove(player);
    }

    /** 这名玩家迄今收到的某类载荷，按收到的先后排列。 */
    static <T> List<T> received(ServerPlayer player, Class<T> payloadType) {
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.getConnection().channel();
        channel.flushOutbound();
        List<T> payloads = new ArrayList<>();
        for (Object message : channel.outboundMessages()) {
            if (message instanceof ClientboundCustomPayloadPacket packet && payloadType.isInstance(packet.payload())) {
                payloads.add(payloadType.cast(packet.payload()));
            }
        }
        return payloads;
    }
}
