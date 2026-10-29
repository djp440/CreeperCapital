package io.github.djp440.creepercapital.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.djp440.creepercapital.core.market.MarketTrade;
import io.github.djp440.creepercapital.network.LatestTradesPayload;
import net.minecraft.util.Util;

/**
 * 客户端缓存的全服「最新成交」（模拟成交和玩家成交），每个合约只保留最近 {@link #KEEP} 笔；只由服务端同步过来的数据更新，离开服务器时清空。
 * 玩家成交另外在 {@link PlayerTradePins} 里置顶一段时间（最新成交页顶部），免得两三秒就被模拟成交挤出可见区域。
 */
public final class ClientLatestTrades {
    /** 每个合约保留的笔数，面板滚动时最多能翻到这么多。 */
    public static final int KEEP = 50;

    private static final Map<String, ArrayDeque<MarketTrade>> TRADES = new HashMap<>();
    private static final Map<String, PlayerTradePins<MarketTrade>> PINS = new HashMap<>();

    private ClientLatestTrades() {
    }

    static void accept(LatestTradesPayload payload) {
        for (MarketTrade trade : payload.trades()) {
            ArrayDeque<MarketTrade> buffer = TRADES.computeIfAbsent(trade.symbol(), symbol -> new ArrayDeque<>());
            buffer.addLast(trade);
            while (buffer.size() > KEEP) {
                buffer.removeFirst();
            }
            if (trade.isPlayer()) {
                PINS.computeIfAbsent(trade.symbol(), symbol -> new PlayerTradePins<>()).add(trade, Util.getMillis());
            }
        }
    }

    static void clear() {
        TRADES.clear();
        PINS.clear();
    }

    /** 某个合约此刻置顶的玩家成交，最新的在前。 */
    static List<MarketTrade> pinned(String symbol) {
        PlayerTradePins<MarketTrade> pins = PINS.get(symbol);
        return pins == null ? List.of() : pins.active(Util.getMillis());
    }

    /** 某个合约最近的成交，最新的在前。 */
    public static List<MarketTrade> newestFirst(String symbol) {
        ArrayDeque<MarketTrade> buffer = TRADES.get(symbol);
        if (buffer == null) {
            return List.of();
        }
        List<MarketTrade> newestFirst = new ArrayList<>(buffer);
        java.util.Collections.reverse(newestFirst);
        return newestFirst;
    }
}
