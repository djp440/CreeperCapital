package io.github.djp440.creepercapital.client;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 客户端缓存的最新行情，只由服务端同步过来的快照更新；离开服务器时清空。
 */
final class ClientMarket {
    private static @Nullable MarketSnapshot latest;
    private static @Nullable MarketSnapshot previous;

    private ClientMarket() {
    }

    static void accept(MarketSnapshot snapshot) {
        previous = latest;
        latest = snapshot;
    }

    static void clear() {
        latest = null;
        previous = null;
    }

    static Optional<MarketSnapshot> latest() {
        return Optional.ofNullable(latest);
    }

    /** 指数价格相对上一个行情步的涨跌；还没有上一步时视为持平。 */
    static PriceDirection indexPriceDirection() {
        if (latest == null || previous == null) {
            return PriceDirection.FLAT;
        }
        return PriceDirection.between(previous.indexPrice(), latest.indexPrice());
    }
}
