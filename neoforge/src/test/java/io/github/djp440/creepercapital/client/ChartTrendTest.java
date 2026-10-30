package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.kline.Candle;

/**
 * 线形图的线色规则（#32）：可见区间最后一根收盘价不低于第一根开盘价用涨色，否则用跌色。
 */
class ChartTrendTest {
    /** 开盘 / 收盘依次：10→12、12→9、9→11、11→10（最高最低不参与）。 */
    private static final List<Candle> CANDLES = List.of(
            new Candle(0, 10, 99, 1, 12, 1),
            new Candle(60, 12, 99, 1, 9, 1),
            new Candle(120, 9, 99, 1, 11, 1),
            new Candle(180, 11, 99, 1, 10, 1));

    @Test
    @DisplayName("最后一根收盘价高于第一根开盘价：涨色；低于：跌色；相等：涨色")
    void lineColorFollowsTheVisibleRange() {
        assertTrue(ChartTrend.risingOver(CANDLES, 0, 2));   // 11 >= 10
        assertFalse(ChartTrend.risingOver(CANDLES, 1, 3));  // 10 < 12
        assertTrue(ChartTrend.risingOver(CANDLES, 0, 3));   // 10 >= 10，相等算涨
    }

    @Test
    @DisplayName("只看可见区间：区间之外的 K 线（平移 / 缩放后看不见的）不影响线色")
    void candlesOutsideTheRangeDoNotMatter() {
        // 整体看是平的，但只看第 1~2 根：开 12 收 11，跌
        assertFalse(ChartTrend.risingOver(CANDLES, 1, 2));
        // 只看一根时就是这根自己的涨跌
        assertTrue(ChartTrend.risingOver(CANDLES, 0, 0));
        assertFalse(ChartTrend.risingOver(CANDLES, 1, 1));
    }
}
