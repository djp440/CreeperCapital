package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.kline.Candle;

/**
 * 价格轴：价格 ↔ 纵坐标换算，以及按可见区间自动缩放（隐藏的指标不参与）。
 */
class PriceScaleTest {
    private static final double EPS = 1e-9;
    /** 开 高 低 收 依次：[10,20,5,15]、[15,30,12,25]、[25,26,24,25]（第三根离群的极值在区间外时不能影响）。 */
    private static final List<Candle> CANDLES = List.of(
            new Candle(0, 10, 20, 5, 15, 1),
            new Candle(60, 15, 30, 12, 25, 1),
            new Candle(120, 25, 100, 1, 25, 1));

    @Test
    @DisplayName("价格轴只看可见区间的最高 / 最低价，上下各留 8% 边距；最高价在顶端、最低价在底端")
    void fitsTheVisibleRangeWithPadding() {
        PriceScale scale = PriceScale.fit(100, 201, CANDLES, 0, 1, false, List.of());

        // 区间最高 30、最低 5，差 25，边距 2
        assertEquals(32.0, scale.high(), EPS);
        assertEquals(3.0, scale.low(), EPS);
        assertEquals(100 + (int) Math.round(2.0 / 29 * 200), scale.y(30));
        assertEquals(100, scale.y(32));
        assertEquals(300, scale.y(3));
        assertEquals(18.5, scale.priceAt(scale.y(18.5)), 29.0 / 200);
    }

    @Test
    @DisplayName("线形图只看收盘价")
    void lineChartFitsCloseOnly() {
        PriceScale scale = PriceScale.fit(0, 100, CANDLES, 0, 1, true, List.of());

        // 收盘 15、25，差 10，边距 0.8
        assertEquals(25.8, scale.high(), EPS);
        assertEquals(14.2, scale.low(), EPS);
    }

    @Test
    @DisplayName("显示的指标线参与价格轴范围，没有传进来的（隐藏的）指标不参与；NaN 点跳过")
    void shownIndicatorsExtendTheRangeButHiddenOnesDoNot() {
        double[] shown = {Double.NaN, 40, 2};

        PriceScale with = PriceScale.fit(0, 100, CANDLES, 0, 1, false, List.of(shown));
        PriceScale without = PriceScale.fit(0, 100, CANDLES, 0, 1, false, List.of());

        // 区间 [5, 40]，差 35，边距 2.8；第 2 个点（值 2）在可见区间之外，不影响下沿
        assertEquals(42.8, with.high(), EPS);
        assertEquals(2.2, with.low(), EPS);
        assertEquals(32.0, without.high(), EPS);
    }

    @Test
    @DisplayName("价格一动不动时仍有可画的范围")
    void flatPriceStillHasARange() {
        PriceScale scale = PriceScale.fit(0, 100, List.of(new Candle(0, 50, 50, 50, 50, 1)), 0, 0, false, List.of());

        assertTrue(scale.high() > 50 && scale.low() < 50);
    }
}
