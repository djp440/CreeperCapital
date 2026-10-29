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
import net.minecraft.world.level.storage.SavedDataStorage;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端持有的全服市场：由服务器 tick 推进，每个行情步把快照同步给所有在线玩家（ADR-0003）。
 * <p>
 * 停服、单人暂停、空服暂停时服务器不 tick，市场随之冻结；服务器卡顿时行情跟着变慢，不补跳。
 * 市场状态随世界存档保存，重启后接着走。
 */
public final class ServerMarket {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static @Nullable MarketSavedData data;

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
        return market().snapshot();
    }

    /** 暂停市场：价格和市场时间一起冻结（{@code /creeper dev market pause}）。 */
    public static void pause() {
        market().pause();
    }

    public static void resume() {
        market().resume();
    }

    public static boolean isPaused() {
        return market().isPaused();
    }

    private static Market market() {
        if (data == null) {
            throw new IllegalStateException("服务器未运行，没有市场");
        }
        return data.market();
    }

    private static void onServerStarting(ServerStartingEvent event) {
        // 存档里有市场就从停下的地方接着走（不补算），没有就按世界种子新建
        SavedDataStorage storage = event.getServer().getDataStorage();
        MarketSavedData saved = storage.get(MarketSavedData.TYPE);
        if (saved == null) {
            saved = new MarketSavedData(new Market(event.getServer().overworld().getSeed()));
            storage.set(MarketSavedData.TYPE, saved);
            saved.setDirty();
            LOGGER.info("新建市场：{}", saved.market().snapshot());
        } else {
            MarketSnapshot snapshot = saved.market().snapshot();
            LOGGER.info("从存档恢复市场：{}，市场时间 {}", snapshot, snapshot.time());
        }
        data = saved;
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        data = null;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // /tick freeze 冻结世界时，市场一起冻结
        if (data == null || !server.tickRateManager().runsNormally()) {
            return;
        }
        // 暂停时状态不变，不必重复写盘
        if (!data.market().isPaused()) {
            data.setDirty();
        }
        data.market().tick().ifPresent(snapshot -> broadcast(server, snapshot));
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
        if (data != null && event.getEntity() instanceof ServerPlayer player) {
            ModPayloads.sendTo(player, new MarketSnapshotPayload(data.market().snapshot()));
        }
    }
}
