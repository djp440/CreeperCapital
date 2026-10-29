package io.github.djp440.creepercapital.client;

import java.util.List;

import io.github.djp440.creepercapital.core.kline.Candle;

/**
 * 线形图的线色规则（纯函数）：可见区间最后一根收盘价不低于第一根开盘价用涨色，否则用跌色；平移、缩放后可见区间变了，线色跟着变。
 */
final class ChartTrend {
    private ChartTrend() {
    }

    /** 可见区间 {@code [first, last]} 是否算「涨」：最后一根的收盘价 &gt;= 第一根的开盘价（相等算涨）。 */
    static boolean risingOver(List<Candle> candles, int first, int last) {
        return candles.get(last).close() >= candles.get(first).open();
    }
}
