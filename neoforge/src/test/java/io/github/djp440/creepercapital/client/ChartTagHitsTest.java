package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图表 tag 的命中测试：拖拽区（tag 的文字部分）与按钮（✓ / ✕）各占一块，中间隔着一段什么都不触发的缝，不会误触。
 */
class ChartTagHitsTest {
    /** 一个带两个按钮（✓、✕）的可拖动 tag：[10, 110) × [50, 62)。 */
    private static final ChartTagHits.Tag CONFIRM = new ChartTagHits.Tag(10, 50, 110, 62, 2, true);

    private static ChartTagHits.Hit hit(double x, double y, ChartTagHits.Tag... tags) {
        return ChartTagHits.hit(List.of(tags), x, y);
    }

    @Test
    @DisplayName("按钮靠右等宽排开，文字区在按钮左边、隔一段缝")
    void buttonsSitAtTheRightEnd() {
        assertEquals(110 - 2 * ChartTagHits.BUTTON_WIDTH, ChartTagHits.buttonLeft(CONFIRM, 0));
        assertEquals(110 - ChartTagHits.BUTTON_WIDTH, ChartTagHits.buttonLeft(CONFIRM, 1));
        assertEquals(ChartTagHits.buttonLeft(CONFIRM, 0) - ChartTagHits.DEAD_ZONE, ChartTagHits.bodyRight(CONFIRM));
    }

    @Test
    @DisplayName("点文字区开始拖拽，点按钮命中对应按钮")
    void bodyDragsAndButtonsClick() {
        assertEquals(new ChartTagHits.Hit(0, ChartTagHits.Zone.DRAG, -1), hit(20, 55, CONFIRM));
        assertEquals(new ChartTagHits.Hit(0, ChartTagHits.Zone.BUTTON, 0), hit(ChartTagHits.buttonLeft(CONFIRM, 0) + 1, 55, CONFIRM));
        assertEquals(new ChartTagHits.Hit(0, ChartTagHits.Zone.BUTTON, 1), hit(109, 61, CONFIRM));
    }

    @Test
    @DisplayName("文字区与按钮之间的缝、tag 之外：什么都不触发")
    void deadZoneAndOutsideMiss() {
        int gap = ChartTagHits.bodyRight(CONFIRM);
        assertEquals(ChartTagHits.Zone.NONE, hit(gap, 55, CONFIRM).zone());
        assertEquals(ChartTagHits.Zone.NONE, hit(gap + ChartTagHits.DEAD_ZONE - 1, 55, CONFIRM).zone());
        assertEquals(ChartTagHits.Zone.NONE, hit(5, 55, CONFIRM).zone());
        assertEquals(ChartTagHits.Zone.NONE, hit(20, 62, CONFIRM).zone(), "下边缘不含");
    }

    @Test
    @DisplayName("不可拖的 tag（强平价、限价委托）：文字区不触发拖拽")
    void staticTagsDoNotDrag() {
        ChartTagHits.Tag liquidation = new ChartTagHits.Tag(10, 50, 90, 62, 0, false);
        assertEquals(ChartTagHits.Zone.NONE, hit(20, 55, liquidation).zone());
        assertEquals(90, ChartTagHits.bodyRight(liquidation), "没有按钮时文字区占满");
    }

    @Test
    @DisplayName("tag 叠在一起时，后画的（盖在上面的）优先")
    void laterTagsWin() {
        ChartTagHits.Tag below = new ChartTagHits.Tag(10, 50, 110, 62, 0, true);
        ChartTagHits.Tag above = new ChartTagHits.Tag(10, 55, 110, 67, 0, true);
        assertEquals(1, hit(20, 58, below, above).tag());
        assertEquals(0, hit(20, 52, below, above).tag());
    }
}
