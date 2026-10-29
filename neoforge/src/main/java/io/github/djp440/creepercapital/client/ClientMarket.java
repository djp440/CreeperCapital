package io.github.djp440.creepercapital.client;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.network.MarketSnapshotPayload;

/**
 * 客户端缓存的最新行情与 24h 统计，只由服务端同步过来的快照更新；离开服务器时清空。
 */
public final class ClientMarket {
    private static @Nullable MarketSnapshot latest;
    private static @Nullable MarketSnapshot previous;
    private static @Nullable Stats24h stats24h;

    private ClientMarket() {
    }

    static void accept(MarketSnapshotPayload payload) {
        previous = latest;
        latest = payload.snapshot();
        stats24h = payload.stats24h();
        ClientKlines.onSnapshot(payload.snapshot());
    }

    static void clear() {
        latest = null;
        previous = null;
        stats24h = null;
        ClientKlines.clear();
    }

    public static Optional<MarketSnapshot> latest() {
        return Optional.ofNullable(latest);
    }

    static Optional<Stats24h> stats24h() {
        return Optional.ofNullable(stats24h);
    }

    /** 最新价格相对上一个行情步的涨跌；还没有上一步时视为持平。 */
    static PriceDirection lastPriceDirection() {
        if (latest == null || previous == null) {
            return PriceDirection.FLAT;
        }
        return PriceDirection.between(previous.lastPrice(), latest.lastPrice());
    }
}
