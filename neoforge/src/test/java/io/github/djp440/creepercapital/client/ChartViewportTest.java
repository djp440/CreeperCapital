package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.kline.Candle;

/**
 * 图表水平视口的纯函数：可见区间、平移越界、缩放上下限、鼠标锚点不漂移，以及 K 线序号与横坐标的互相换算。
 */
class ChartViewportTest {
    private static final double EPS = 1e-9;

    @Test
    @DisplayName("默认视口：最新一根紧贴绘图区右边界，往左每根占 7 像素")
    void latestViewportPinsNewestCandleToRightEdge() {
        // 100 根 K 线，绘图区 [0, 70]：恰好放下 10 根（第 90~99 根）
        ChartViewport.Projection view = ChartViewport.latest().project(0, 70, 100);

        assertEquals(90, view.firstVisible());
        assertEquals(99, view.lastVisible());
        assertEquals(66.5, view.centerX(99), EPS);
        assertEquals(3.5, view.centerX(90), EPS);
    }

    @Test
    @DisplayName("横坐标与 K 线序号互相换算；绘图区之外、没有 K 线的位置没有对应的 K 线")
    void convertsBetweenXAndCandleIndex() {
        ChartViewport.Projection view = ChartViewport.latest().project(0, 70, 100);

        assertEquals(99.5, view.indexAt(66.5), EPS);
        assertEquals(90, view.candleAt(0.0));
        assertEquals(99, view.candleAt(69.9));
        assertEquals(-1, view.candleAt(-1));
        assertEquals(-1, view.candleAt(70));
        // K 线比绘图区放得下的少：左边留空，没有 K 线的位置不对应任何一根
        ChartViewport.Projection few = ChartViewport.latest().project(0, 70, 4);
        assertEquals(-1, few.candleAt(10));
        assertEquals(0, few.candleAt(42));
        assertEquals(0, few.firstVisible());
    }

    @Test
    @DisplayName("向右拖是回看更早的 K 线：每拖 7 像素，可见区间往旧的方向挪一根")
    void draggingRightShowsOlderCandles() {
        ChartViewport panned = ChartViewport.latest().panned(14, 100, 70);

        assertEquals(2.0, panned.offset(), EPS);
        ChartViewport.Projection view = panned.project(0, 70, 100);
        assertEquals(88, view.firstVisible());
        assertEquals(97, view.lastVisible());
        // 向左拖回来
        assertEquals(1.0, panned.panned(-7, 100, 70).offset(), EPS);
    }

    @Test
    @DisplayName("平移越界：最旧一根拖到左边缘为止，最新一根不能拖过右边缘；K 线全放得下时根本拖不动")
    void panningIsClampedAtBothEnds() {
        // 100 根、绘图区放 10 根：最多往回拖 90 根，此时第 0 根贴着左边缘
        ChartViewport oldest = ChartViewport.latest().panned(1_000_000, 100, 70);
        assertEquals(90.0, oldest.offset(), EPS);
        assertEquals(0.0, oldest.project(0, 70, 100).leftIndex(), EPS);
        assertEquals(0, oldest.project(0, 70, 100).firstVisible());

        assertEquals(0.0, ChartViewport.latest().panned(-50, 100, 70).offset(), EPS);

        // 只有 4 根，放得下：不能平移
        assertEquals(0.0, ChartViewport.latest().panned(30, 4, 70).offset(), EPS);
    }

    @Test
    @DisplayName("平移被越界截断时，能算出没生效的那部分像素（用来给到头反馈）")
    void overscrollReportsTheUnappliedPart() {
        // 已经到最旧：再向右拖 20 像素，全部没生效
        ChartViewport oldest = ChartViewport.latest().panned(1_000_000, 100, 70);
        assertEquals(20.0, oldest.overscroll(20, 100, 70), EPS);
        // 反向拖是正常的，没有越界
        assertEquals(0.0, oldest.overscroll(-20, 100, 70), EPS);
        // 离最旧还差 7 像素（1 根），拖 20：生效 7，越界 13
        ChartViewport almost = new ChartViewport(7, 89);
        assertEquals(13.0, almost.overscroll(20, 100, 70), EPS);
        // 在最新一根处向左拖是越界（负值）
        assertEquals(-9.0, ChartViewport.latest().overscroll(-9, 100, 70), EPS);
    }

    @Test
    @DisplayName("滚轮缩放以鼠标位置为锚点：缩放前后鼠标下的那一点不漂移")
    void zoomKeepsTheAnchorUnderTheMouse() {
        ChartViewport start = new ChartViewport(7, 300); // 1000 根，往回看了 300 根
        double anchorX = 120;
        double before = start.project(0, 400, 1000).indexAt(anchorX);

        for (double steps : new double[] {1, 3, -1, -2.5}) {
            ChartViewport zoomed = start.zoomed(steps, anchorX, 0, 400, 1000);
            assertEquals(before, zoomed.project(0, 400, 1000).indexAt(anchorX), 1e-9, "steps=" + steps);
        }
        // 放大是每格 1.2 倍，缩小反之
        assertEquals(7 * 1.2, start.zoomed(1, anchorX, 0, 400, 1000).slotWidth(), EPS);
        assertEquals(7 / 1.2, start.zoomed(-1, anchorX, 0, 400, 1000).slotWidth(), EPS);
    }

    @Test
    @DisplayName("缩放有上下限：滚到头不再变化")
    void zoomIsLimited() {
        ChartViewport in = ChartViewport.latest().zoomed(100, 200, 0, 400, 1000);
        ChartViewport out = ChartViewport.latest().zoomed(-100, 200, 0, 400, 1000);

        assertEquals(ChartViewport.MAX_SLOT_WIDTH, in.slotWidth(), EPS);
        assertEquals(ChartViewport.MIN_SLOT_WIDTH, out.slotWidth(), EPS);
        assertEquals(in, in.zoomed(1, 200, 0, 400, 1000));
    }

    @Test
    @DisplayName("缩放后仍受平移边界约束：缩小到左边缘后，最旧一根不会离开左边缘")
    void zoomOutRespectsTheOldestEdge() {
        // 绘图区 400 宽，1000 根；已经看到最旧（offset=max），再缩小，左边缘仍是第 0 根
        ChartViewport oldest = ChartViewport.latest().panned(1_000_000, 1000, 400);
        ChartViewport zoomed = oldest.zoomed(-2, 10, 0, 400, 1000);

        assertEquals(0.0, zoomed.project(0, 400, 1000).leftIndex(), 1e-9);
        assertEquals(zoomed.maxOffset(1000, 400), zoomed.offset(), 1e-9);
    }

    @Test
    @DisplayName("市场时间换算成 K 线序号（小数）：落在某根 K 线内部按比例，早于第一根 / 晚于最后一根按周期外推")
    void marketTimeMapsToCandleIndex() {
        // 三根 1m K 线：[600,660) [660,720) [720,780)
        List<Candle> candles = List.of(new Candle(600, 1, 1, 1, 1, 1), new Candle(660, 1, 1, 1, 1, 1),
                new Candle(720, 1, 1, 1, 1, 1));

        assertEquals(0.0, ChartViewport.indexOfTime(candles, 60, 600), EPS);
        assertEquals(1.5, ChartViewport.indexOfTime(candles, 60, 690), EPS);
        assertEquals(2.25, ChartViewport.indexOfTime(candles, 60, 735), EPS);
        assertEquals(-2.0, ChartViewport.indexOfTime(candles, 60, 480), EPS);
        assertEquals(4.0, ChartViewport.indexOfTime(candles, 60, 840), EPS);
    }
}
