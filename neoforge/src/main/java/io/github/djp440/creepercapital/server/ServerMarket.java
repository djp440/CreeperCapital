package io.github.djp440.creepercapital.server;

import java.io.IOException;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.KlineStore;
import io.github.djp440.creepercapital.core.kline.Retention;
import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.network.KlineHistoryPayload;
import io.github.djp440.creepercapital.network.MarketSnapshotPayload;
import io.github.djp440.creepercapital.network.ModPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端持有的全服市场：由服务器 tick 推进，每个行情步把快照同步给所有在线玩家（ADR-0003）。
 * <p>
 * 停服、单人暂停、空服暂停时服务器不 tick，市场随之冻结；服务器卡顿时行情跟着变慢，不补跳。
 * 市场状态随世界存档保存，重启后接着走。
 * <p>
 * K 线收盘时追加写进世界目录下的 {@code creepercapital/klines}，不进存档的自动保存；启动时按存档的市场时间对账。
 */
public final class ServerMarket {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 一次历史请求最多返回多少根 K 线，足够铺满任何屏幕，再留出算 MA99 的余量。 */
    private static final int HISTORY_CANDLES = 500;

    private static @Nullable MarketSavedData data;
    private static @Nullable KlineStore klineStore;

    private ServerMarket() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onPlayerLoggedIn);
    }

    /** 当前的市场快照，只能在服务器运行时调用。 */
    public static MarketSnapshot snapshot() {
        return market().snapshot();
    }

    /** 行情栏的 24h 统计；新市场还没有行情步时用最新价格填一个平的、成交量为 0 的统计。 */
    public static Stats24h stats24h() {
        Market market = market();
        double price = market.snapshot().lastPrice();
        return market.stats24h().orElse(new Stats24h(price, price, price, price, 0));
    }

    /** 把某个周期最近的 K 线发给一名玩家（客户端打开界面、切换周期时请求）。 */
    public static void sendKlines(ServerPlayer player, CandlePeriod period) {
        if (data != null) {
            ModPayloads.sendTo(player,
                    new KlineHistoryPayload(period, market().klines().candles(period, HISTORY_CANDLES)));
        }
    }

    /**
     * 不等 tick，直接把行情快进若干个行情步（{@code /creeper dev market advance}），K 线随之生成。
     * 只把最后一步广播给玩家，客户端发现步数不连续会重新拉取 K 线。
     *
     * @return 实际走了的行情步数；市场暂停时为 0
     */
    public static int advance(MinecraftServer server, int steps) {
        Market market = market();
        long before = market.snapshot().step();
        market.advanceSteps(steps).ifPresent(snapshot -> broadcast(server, snapshot));
        markDirty();
        return (int) (market.snapshot().step() - before);
    }

    /** 暂停市场：价格和市场时间一起冻结（{@code /creeper dev market pause}）。 */
    public static void pause() {
        market().pause();
        markDirty();
    }

    public static void resume() {
        market().resume();
        markDirty();
    }

    /** 暂停状态要存档，而暂停时每 tick 不再标脏，所以切换时主动标脏一次。 */
    private static void markDirty() {
        if (data != null) {
            data.setDirty();
        }
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
            LOGGER.info("从存档恢复市场：{}，市场时间 {}，暂停={}", snapshot, snapshot.time(), saved.market().isPaused());
        }
        data = saved;
        openKlineStore(event.getServer(), saved.market());
    }

    /** 打开世界目录下的 K 线文件，与存档里的市场时间对账，并把历史装回市场。失败只影响 K 线的持久化，不影响行情。 */
    private static void openKlineStore(MinecraftServer server, Market market) {
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("creepercapital").resolve("klines");
        MarketSnapshot snapshot = market.snapshot();
        try {
            KlineStore store = KlineStore.open(directory, snapshot.symbol(), Retention.DEFAULT);
            try {
                long missing = store.bind(market.klines(), snapshot.time().totalSeconds(),
                        e -> LOGGER.warn("K 线写入失败，这一根不会持久化", e));
                if (missing > 0) {
                    LOGGER.warn("K 线文件比存档旧，缺了 {} 根已收盘的 1m K 线（可能是异常断电或文件被删），这些 K 线无法找回", missing);
                }
            } catch (IOException | RuntimeException e) {
                store.close();
                throw e;
            }
            klineStore = store;
            LOGGER.info("K 线存储 {}：读回 1m {} 根、1h {} 根、1d {} 根（均含正在走的一根）", directory,
                    market.klines().candles(CandlePeriod.M1, Integer.MAX_VALUE).size(),
                    market.klines().candles(CandlePeriod.H1, Integer.MAX_VALUE).size(),
                    market.klines().candles(CandlePeriod.D1, Integer.MAX_VALUE).size());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("无法打开 K 线存储 {}，本次运行的已收盘 K 线不会持久化", directory, e);
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (data != null) {
            MarketSnapshot snapshot = data.market().snapshot();
            LOGGER.info("停服时的市场：{}，市场时间 {}，暂停={}", snapshot, snapshot.time(), data.market().isPaused());
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        data = null;
        if (klineStore != null) {
            try {
                klineStore.close();
            } catch (IOException e) {
                LOGGER.warn("关闭 K 线存储失败", e);
            }
            klineStore = null;
        }
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
        LOGGER.debug("行情步 {}：{} 指数价格 {} 最新价格 {} 标记价格 {} 资金费率 {} 成交量 {}", snapshot.step(),
                snapshot.symbol(), snapshot.indexPrice(), snapshot.lastPrice(), snapshot.markPrice(),
                snapshot.fundingRate(), snapshot.volume());
        MarketSnapshotPayload payload = new MarketSnapshotPayload(snapshot, stats24h());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ModPayloads.sendTo(player, payload);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (data != null && event.getEntity() instanceof ServerPlayer player) {
            ModPayloads.sendTo(player, new MarketSnapshotPayload(data.market().snapshot(), stats24h()));
        }
    }
}
