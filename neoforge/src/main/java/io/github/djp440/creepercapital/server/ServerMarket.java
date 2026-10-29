package io.github.djp440.creepercapital.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.KlineStore;
import io.github.djp440.creepercapital.core.kline.Retention;
import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.Funding;
import io.github.djp440.creepercapital.core.market.JumpEvent;
import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.MarketState;
import io.github.djp440.creepercapital.core.market.MarketTime;
import io.github.djp440.creepercapital.core.market.MarketTrade;
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
 * 市场里有哪些合约由服务端配置文件决定（{@link ContractConfig}），每个行情步每个合约各同步一份快照。
 * <p>
 * K 线收盘时追加写进世界目录下的 {@code creepercapital/klines}（每个合约一组文件），不进存档的自动保存；
 * 启动时按存档的市场时间对账。
 */
public final class ServerMarket {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 一次历史请求最多返回多少根 K 线，足够铺满任何屏幕，再留出算 MA99 的余量。 */
    private static final int HISTORY_CANDLES = 500;

    /** 新建世界时预生成多少个市场日的历史（术语表：预生成历史）。 */
    private static final int BACKFILL_DAYS = 30;

    /** 每个合约一个 K 线存储，键是交易代码。 */
    private static final Map<String, KlineStore> KLINE_STORES = new LinkedHashMap<>();

    private static @Nullable MarketSavedData data;
    private static @Nullable Market market;

    private ServerMarket() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerMarket::onPlayerLoggedIn);
    }

    /** 市场里的合约的交易代码，按配置顺序，只能在服务器运行时调用。 */
    public static List<String> symbols() {
        return market().symbols();
    }

    /** 某个合约的定义，只能在服务器运行时调用。 */
    public static ContractSpec spec(String symbol) {
        return market().spec(symbol);
    }

    /** 某个合约当前的市场快照，只能在服务器运行时调用。 */
    public static MarketSnapshot snapshot(String symbol) {
        return market().snapshot(symbol);
    }

    /** 每个合约当前的市场快照，按配置顺序。 */
    public static List<MarketSnapshot> snapshots() {
        return market().snapshots();
    }

    /** 市场此刻要写进存档的状态（含排队中的跳跃），供测试与诊断读取。 */
    public static MarketState state() {
        return market().state();
    }

    /** 行情栏的 24h 统计；新市场还没有行情步时用最新价格填一个平的、成交量为 0 的统计。 */
    public static Stats24h stats24h(String symbol) {
        Market market = market();
        double price = market.snapshot(symbol).lastPrice();
        return market.stats24h(symbol).orElse(new Stats24h(price, price, price, price, 0));
    }

    /** 把某个合约某个周期最近的 K 线发给一名玩家（客户端打开界面、切换合约或周期时请求）；没有这个合约则忽略。 */
    public static void sendKlines(ServerPlayer player, String symbol, CandlePeriod period) {
        if (market != null && market.symbols().contains(symbol)) {
            ModPayloads.sendTo(player,
                    new KlineHistoryPayload(symbol, period, market.klines(symbol).candles(period, HISTORY_CANDLES)));
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
        long before = market.snapshots().get(0).step();
        List<MarketSnapshot> last = market.advanceSteps(steps);
        if (!last.isEmpty()) {
            broadcast(server, last);
        }
        markDirty();
        return (int) (market.snapshots().get(0).step() - before);
    }

    /**
     * 快进到接下来第 {@code periods} 个资金费结算点（{@code /creeper dev market funding}）：每次快进恰好停在结算点那一步并广播它，
     * 所以每个结算点都会触发一次资金费结算（普通的 {@link #advance} 只广播最后一步，跨过的结算点不会结算）。
     *
     * @return 实际走了的行情步数；市场暂停时为 0
     */
    public static int advanceToFunding(MinecraftServer server, int periods) {
        int total = 0;
        for (int i = 0; i < periods; i++) {
            long seconds = market().snapshots().get(0).secondsUntilFunding();
            long stepSeconds = MarketTime.ofStep(1).totalSeconds();
            long steps = seconds == 0 ? Funding.INTERVAL_SECONDS / stepSeconds
                    : seconds / stepSeconds;
            total += advance(server, (int) steps);
        }
        return total;
    }

    /**
     * 让某个合约在下一个行情步跳跃（{@code /creeper dev market jump}）。
     *
     * @param changeRatio 价格变化比例，+0.1 涨 10%，-0.2 跌 20%
     * @throws IllegalArgumentException 没有这个合约，或幅度不合法
     */
    public static void queueJump(String symbol, double changeRatio) {
        market().queueJump(symbol, changeRatio);
        markDirty();
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
        if (market == null) {
            throw new IllegalStateException("服务器未运行，没有市场");
        }
        return market;
    }

    private static void onServerStarting(ServerStartingEvent event) {
        // 先读合约配置；存档里有市场就按当前配置从停下的地方接着走（不补算），没有就按世界种子新建
        List<ContractSpec> specs = ContractConfig.load(ContractConfig.defaultPath());
        SavedDataStorage storage = event.getServer().getDataStorage();
        MarketSavedData saved = storage.get(MarketSavedData.TYPE);
        Market started;
        boolean created = saved == null;
        if (created) {
            started = new Market(event.getServer().overworld().getSeed(), specs);
            saved = MarketSavedData.of(started);
            storage.set(MarketSavedData.TYPE, saved);
            saved.setDirty();
            LOGGER.info("新建市场：{}", started.snapshots());
        } else {
            started = saved.bind(specs);
            LOGGER.info("从存档恢复市场：{}，市场时间 {}，暂停={}", started.snapshots(),
                    started.snapshots().get(0).time(), started.isPaused());
        }
        started.addJumpListener(ServerMarket::onJump);
        data = saved;
        market = started;
        openKlineStores(event.getServer(), started);
        if (created) {
            backfill(started);
        }
    }

    /** 新世界在 K 线存储接好之后预生成历史，让 K 线一开始就有完整历史；已有世界重启不会走到这里。 */
    private static void backfill(Market started) {
        long begin = System.nanoTime();
        started.backfill(BACKFILL_DAYS);
        LOGGER.info("预生成 {} 个市场日的历史（{} 个合约）耗时 {} ms，开市时间 {}", BACKFILL_DAYS, started.symbols().size(),
                (System.nanoTime() - begin) / 1_000_000, started.snapshots().get(0).time());
    }

    /** 跳跃事件的落点：现在只写日志，新闻文字之后从这里（或直接订阅 {@link Market#addJumpListener}）挂接。 */
    private static void onJump(JumpEvent event) {
        LOGGER.info("跳跃：{} 在行情步 {} {}{}（{}）", event.symbol(), event.step(), event.isUp() ? "上涨 " : "下跌 ",
                String.format("%.2f%%", Math.abs(event.changeRatio()) * 100),
                event.cause() == JumpEvent.Cause.MANUAL ? "手动" : "随机");
    }

    /** 打开世界目录下每个合约的 K 线文件，与存档里的市场时间对账，并把历史装回市场。失败只影响 K 线的持久化，不影响行情。 */
    private static void openKlineStores(MinecraftServer server, Market market) {
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("creepercapital").resolve("klines");
        for (String symbol : market.symbols()) {
            MarketSnapshot snapshot = market.snapshot(symbol);
            try {
                KlineStore store = KlineStore.open(directory, symbol, Retention.DEFAULT);
                try {
                    long missing = store.bind(market.klines(symbol), snapshot.time().totalSeconds(),
                            e -> LOGGER.warn("{} 的 K 线写入失败，这一根不会持久化", symbol, e));
                    if (missing > 0) {
                        LOGGER.warn("{} 的 K 线文件比存档旧，缺了 {} 根已收盘的 1m K 线（可能是异常断电或文件被删），这些 K 线无法找回",
                                symbol, missing);
                    }
                } catch (IOException | RuntimeException e) {
                    store.close();
                    throw e;
                }
                KLINE_STORES.put(symbol, store);
                LOGGER.info("K 线存储 {}（{}）：读回 1m {} 根、1h {} 根、1d {} 根（均含正在走的一根）", directory, symbol,
                        market.klines(symbol).candles(CandlePeriod.M1, Integer.MAX_VALUE).size(),
                        market.klines(symbol).candles(CandlePeriod.H1, Integer.MAX_VALUE).size(),
                        market.klines(symbol).candles(CandlePeriod.D1, Integer.MAX_VALUE).size());
            } catch (IOException | RuntimeException e) {
                LOGGER.error("无法打开 {} 的 K 线存储 {}，本次运行它的已收盘 K 线不会持久化", symbol, directory, e);
            }
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (market != null) {
            LOGGER.info("停服时的市场：{}，市场时间 {}，暂停={}", market.snapshots(), market.snapshots().get(0).time(),
                    market.isPaused());
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        data = null;
        market = null;
        for (Map.Entry<String, KlineStore> store : KLINE_STORES.entrySet()) {
            try {
                store.getValue().close();
            } catch (IOException e) {
                LOGGER.warn("关闭 {} 的 K 线存储失败", store.getKey(), e);
            }
        }
        KLINE_STORES.clear();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // /tick freeze 冻结世界时，市场一起冻结
        if (data == null || market == null || !server.tickRateManager().runsNormally()) {
            return;
        }
        // 暂停时状态不变，不必重复写盘
        if (!market.isPaused()) {
            data.setDirty();
        }
        List<MarketSnapshot> stepped = market.tick();
        if (!stepped.isEmpty()) {
            broadcast(server, stepped);
        }
    }

    /** 同一个行情步里，所有在线玩家收到的是同一份快照；每个合约一个包。 */
    private static void broadcast(MinecraftServer server, List<MarketSnapshot> snapshots) {
        for (MarketSnapshot snapshot : snapshots) {
            LOGGER.debug("行情步 {}：{} 指数价格 {} 最新价格 {} 标记价格 {} 资金费率 {} 成交量 {}", snapshot.step(),
                    snapshot.symbol(), snapshot.indexPrice(), snapshot.lastPrice(), snapshot.markPrice(),
                    snapshot.fundingRate(), snapshot.volume());
            MarketSnapshotPayload payload = new MarketSnapshotPayload(snapshot, stats24h(snapshot.symbol()));
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ModPayloads.sendTo(player, payload);
            }
        }
        ServerLatestTrades.onMarketStep(server, snapshots);
        ServerTrading.onMarketStep(server, snapshots);
    }

    /** 某个行情步的模拟成交（只展示用，不碰行情的随机数流，见 {@link Market#simulatedTrades}）。 */
    static List<MarketTrade> simulatedTrades(MarketSnapshot snapshot, double previousLastPrice) {
        return market().simulatedTrades(snapshot, previousLastPrice);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (market != null && event.getEntity() instanceof ServerPlayer player) {
            for (MarketSnapshot snapshot : market.snapshots()) {
                ModPayloads.sendTo(player, new MarketSnapshotPayload(snapshot, stats24h(snapshot.symbol())));
            }
        }
    }
}
