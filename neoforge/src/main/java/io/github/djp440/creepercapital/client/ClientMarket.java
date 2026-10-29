package io.github.djp440.creepercapital.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.network.MarketSnapshotPayload;

/**
 * 客户端缓存的各合约最新行情与 24h 统计，只由服务端同步过来的快照更新；离开服务器时清空。
 * <p>
 * 服务端每个行情步为每个合约各发一份快照，所以这里按交易代码分开缓存；界面只展示被选中的那个合约。
 * 合约列表按快照第一次到达的顺序排列（服务端按配置顺序发送），第一个到达的合约默认被选中。
 */
public final class ClientMarket {
    private static final Map<String, MarketSnapshot> LATEST = new LinkedHashMap<>();
    private static final Map<String, MarketSnapshot> PREVIOUS = new HashMap<>();
    private static final Map<String, Stats24h> STATS_24H = new HashMap<>();
    private static @Nullable String selected;

    private ClientMarket() {
    }

    static void accept(MarketSnapshotPayload payload) {
        MarketSnapshot snapshot = payload.snapshot();
        String symbol = snapshot.symbol();
        MarketSnapshot before = LATEST.put(symbol, snapshot);
        if (before != null) {
            PREVIOUS.put(symbol, before);
        }
        STATS_24H.put(symbol, payload.stats24h());
        if (selected == null) {
            selected = symbol;
            ClientKlines.onContractSelected();
        }
        if (symbol.equals(selected)) {
            ClientKlines.onSnapshot(snapshot);
        }
    }

    static void clear() {
        LATEST.clear();
        PREVIOUS.clear();
        STATS_24H.clear();
        selected = null;
        ClientKlines.clear();
    }

    /** 已收到行情的合约的交易代码，按到达顺序。 */
    public static List<String> symbols() {
        return List.copyOf(LATEST.keySet());
    }

    /** 当前选中的合约；还没有收到任何行情时为空。 */
    public static Optional<String> selectedSymbol() {
        return Optional.ofNullable(selected);
    }

    /** 这个合约是否是当前选中的。 */
    static boolean isSelected(String symbol) {
        return symbol.equals(selected);
    }

    /** 切换界面展示的合约，K 线随之换成新合约的；不认识的交易代码或已经选中的合约则忽略。 */
    public static void select(String symbol) {
        if (!LATEST.containsKey(symbol) || symbol.equals(selected)) {
            return;
        }
        selected = symbol;
        ClientKlines.onContractSelected();
    }

    /** 某个合约的最新快照；还没收到过它的行情时为空。 */
    public static Optional<MarketSnapshot> snapshot(String symbol) {
        return Optional.ofNullable(LATEST.get(symbol));
    }

    /** 选中合约的最新快照。 */
    public static Optional<MarketSnapshot> latest() {
        return selected == null ? Optional.empty() : Optional.ofNullable(LATEST.get(selected));
    }

    /** 选中合约的 24h 统计。 */
    static Optional<Stats24h> stats24h() {
        return selected == null ? Optional.empty() : Optional.ofNullable(STATS_24H.get(selected));
    }

    /** 选中合约的最新价格相对上一个行情步的涨跌；还没有上一步时视为持平。 */
    static PriceDirection lastPriceDirection() {
        MarketSnapshot latest = selected == null ? null : LATEST.get(selected);
        MarketSnapshot previous = selected == null ? null : PREVIOUS.get(selected);
        if (latest == null || previous == null) {
            return PriceDirection.FLAT;
        }
        return PriceDirection.between(previous.lastPrice(), latest.lastPrice());
    }
}
