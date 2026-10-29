package io.github.djp440.creepercapital.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.MarketTrade;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.network.LatestTradesPayload;
import io.github.djp440.creepercapital.network.ModPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 全服「最新成交」：模拟成交（每个行情步一批，只展示市场活跃度）和玩家成交（来自 {@link io.github.djp440.creepercapital.core.trading.TradingBook}
 * 的成交监听，高亮并标出玩家名）。
 * <p>
 * 两类成交都立刻发给<b>所有在线玩家</b>，不管他有没有打开界面：每步一个包、每笔十几字节，体量小，客户端始终保有最近的成交，
 * 随时打开界面就能看到，也省去「打开界面时再请求」的往返。服务端每个合约保留最近 {@link #BUFFER_PER_CONTRACT} 笔的内存环形缓冲，
 * 玩家登录时一次性补发；缓冲<b>不持久化</b>，服务器重启后清空（模拟成交可由行情步重新生成，历史玩家成交仍在各自的成交历史里）。
 */
public final class ServerLatestTrades {
    /** 每个合约在内存里保留（并补发给新玩家）的最近成交笔数。 */
    public static final int BUFFER_PER_CONTRACT = 50;

    /** 每个合约的最近成交，旧的在前。 */
    private static final Map<String, ArrayDeque<MarketTrade>> RECENT = new LinkedHashMap<>();
    /** 每个合约上一个行情步的快照，用来算本步涨跌（决定模拟成交的买卖倾向）。 */
    private static final Map<String, MarketSnapshot> PREVIOUS = new HashMap<>();

    private ServerLatestTrades() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerLatestTrades::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerLatestTrades::onPlayerLoggedIn);
    }

    /** 某个合约最近的成交，旧的在前，最多 {@link #BUFFER_PER_CONTRACT} 笔。 */
    public static List<MarketTrade> recent(String symbol) {
        ArrayDeque<MarketTrade> buffer = RECENT.get(symbol);
        return buffer == null ? List.of() : List.copyOf(buffer);
    }

    /** 每个行情步：拆出各合约的模拟成交，记入缓冲并发给所有在线玩家。 */
    public static void onMarketStep(MinecraftServer server, List<MarketSnapshot> snapshots) {
        List<MarketTrade> batch = new ArrayList<>();
        for (MarketSnapshot snapshot : snapshots) {
            MarketSnapshot previous = PREVIOUS.put(snapshot.symbol(), snapshot);
            // 上一步不紧挨着（刚启动、dev 快进）就当价格持平，不要把几千步的涨跌算到这一步头上
            double previousLast = previous != null && previous.step() == snapshot.step() - 1 ? previous.lastPrice() : Double.NaN;
            batch.addAll(ServerMarket.simulatedTrades(snapshot, previousLast));
        }
        publish(server, batch);
    }

    /** 玩家成交（交易账的成交监听）：高亮广播给所有在线玩家。 */
    static void onPlayerTrade(MinecraftServer server, PlayerTrade trade) {
        // 买入 = 开多或平空：和成交价的买卖判定同一个口径
        boolean buy = (trade.side() == Side.LONG) == (trade.kind() == TradeKind.OPEN);
        publish(server, List.of(new MarketTrade(trade.symbol(), trade.marketSeconds(), buy, trade.price(), trade.lots(),
                nameOf(server, trade))));
    }

    private static String nameOf(MinecraftServer server, PlayerTrade trade) {
        ServerPlayer online = server.getPlayerList().getPlayer(trade.player());
        if (online != null) {
            return online.getGameProfile().name();
        }
        // 离线玩家（以后强平、限价成交可能发生在他离线时）：查名字缓存，查不到就退回 UUID 前 8 位
        return server.services().nameToIdCache().get(trade.player()).map(profile -> profile.name())
                .orElseGet(() -> trade.player().toString().substring(0, 8));
    }

    private static void publish(MinecraftServer server, List<MarketTrade> trades) {
        if (trades.isEmpty()) {
            return;
        }
        for (MarketTrade trade : trades) {
            ArrayDeque<MarketTrade> buffer = RECENT.computeIfAbsent(trade.symbol(), symbol -> new ArrayDeque<>());
            buffer.addLast(trade);
            while (buffer.size() > BUFFER_PER_CONTRACT) {
                buffer.removeFirst();
            }
        }
        LatestTradesPayload payload = new LatestTradesPayload(trades);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ModPayloads.sendTo(player, payload);
        }
    }

    /** 新加入的玩家补发各合约的缓冲，之后靠实时推送。 */
    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            List<MarketTrade> all = new ArrayList<>();
            RECENT.values().forEach(all::addAll);
            if (!all.isEmpty()) {
                ModPayloads.sendTo(player, new LatestTradesPayload(all));
            }
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        RECENT.clear();
        PREVIOUS.clear();
    }
}
