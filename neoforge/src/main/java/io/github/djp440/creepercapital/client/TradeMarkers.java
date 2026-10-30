package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;

/**
 * 图表上的买卖点（纯函数）：把玩家自己的成交（客户端已同步的成交历史）落到对应的 K 线上。
 * 只看这个合约、只看落在已缓存 K 线时间范围内的成交；同一根 K 线上同一方向的多笔合并成一个标记，记下笔数。
 * 买 = 开多、平空（含强平 / 自动减仓空头）；卖 = 开空、平多。
 */
final class TradeMarkers {
    /**
     * @param candle K 线序号
     * @param buy    买入（否则卖出）
     * @param count  合并的笔数
     */
    record Marker(int candle, boolean buy, int count) {
    }

    private TradeMarkers() {
    }

    /** 成交是买入还是卖出。 */
    static boolean isBuy(PlayerTrade trade) {
        return (trade.kind() == TradeKind.OPEN) == (trade.side() == Side.LONG);
    }

    /** 按 K 线序号升序（同一根先买后卖）返回标记；{@code candles} 从旧到新、不能为空。 */
    static List<Marker> group(List<PlayerTrade> trades, String symbol, List<Candle> candles, long periodSeconds) {
        long from = candles.get(0).start();
        long until = candles.get(candles.size() - 1).start() + periodSeconds;
        Map<Long, int[]> counts = new LinkedHashMap<>();
        for (PlayerTrade trade : trades) {
            long time = trade.marketSeconds();
            if (!trade.symbol().equals(symbol) || time < from || time >= until) {
                continue;
            }
            int candle = (int) Math.floor(ChartViewport.indexOfTime(candles, periodSeconds, time));
            boolean buy = isBuy(trade);
            counts.computeIfAbsent(candle * 2L + (buy ? 0 : 1), key -> new int[1])[0]++;
        }
        List<Marker> markers = new ArrayList<>(counts.size());
        counts.keySet().stream().sorted().forEach(key ->
                markers.add(new Marker((int) (key / 2), key % 2 == 0, counts.get(key)[0])));
        return markers;
    }
}
