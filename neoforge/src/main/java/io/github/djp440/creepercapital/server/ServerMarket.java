package io.github.djp440.creepercapital.server;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.network.MarketSnapshotPayload;
import io.github.djp440.creepercapital.network.ModPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端持有的全服市场：由服务器 tick 推进，每个行情步把快照同步给所有在线玩家（ADR-0003）。
 * <p>
 * 停服、单人暂停、空服暂停时服务器不 tick，市场随之冻结；服务器卡顿时行情跟着变慢，不补跳。
 */
public final class ServerMarket {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static @Nullable Market market;

    private ServerMarket() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onPlayerLoggedIn);
    }

    /** 当前的市场快照，只能在服务器运行时调用。 */
    public static MarketSnapshot snapshot() {
        if (market == null) {
            throw new IllegalStateException("服务器未运行，没有市场");
        }
        return market.snapshot();
    }

    private static void onServerStarting(ServerStartingEvent event) {
        // 首版市场不持久化，按世界种子起步，便于复现
        market = new Market(event.getServer().overworld().getSeed());
        LOGGER.info("市场已开启：{}", market.snapshot());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        market = null;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // /tick freeze 冻结世界时，市场一起冻结
        if (market == null || !server.tickRateManager().runsNormally()) {
            return;
        }
        market.tick().ifPresent(snapshot -> broadcast(server, snapshot));
    }

    /** 同一个行情步里，所有在线玩家收到的是同一份快照。 */
    private static void broadcast(MinecraftServer server, MarketSnapshot snapshot) {
        LOGGER.debug("行情步 {}：{} 指数价格 {}", snapshot.step(), snapshot.symbol(), snapshot.indexPrice());
        MarketSnapshotPayload payload = new MarketSnapshotPayload(snapshot);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ModPayloads.sendTo(player, payload);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (market != null && event.getEntity() instanceof ServerPlayer player) {
            ModPayloads.sendTo(player, new MarketSnapshotPayload(market.snapshot()));
        }
    }
}
