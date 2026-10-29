package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.client.TradingLayout.Layout;
import io.github.djp440.creepercapital.client.TradingLayout.Rect;

/**
 * 交易界面各区域的布局：常见窗口尺寸 × GUI 缩放下互不重叠、不出屏幕、关键面板不小于最小尺寸。
 */
class TradingLayoutTest {
    /** 常见窗口尺寸（物理像素）：16:9 的几档、1366×768 笔记本、1600×900，以及 4:3 的 1024×768、800×600。 */
    private static final int[][] WINDOWS = {
            {854, 480}, {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}, {1024, 768}, {800, 600}};
    /** 选项里的 GUI 缩放：0 是「自动」，1~4 是固定倍数（超出窗口能承受的倍数时游戏会自动压低）。 */
    private static final int[] GUI_SCALES = {0, 1, 2, 3, 4};

    @Test
    void defaultScreenshotSizeHasHeaderSideColumnAndFullHeightOrderPanel() {
        // 1280×720、GUI 缩放 2 → 640×360；行情栏折成两行
        Layout layout = TradingLayout.compute(640, 360, 2);

        assertFalse(layout.compact());
        assertEquals(new Rect(0, 0, 640, 16), layout.header());
        assertEquals(new Rect(4, 20, 636, 68), layout.ticker());
        assertEquals(new Rect(470, 72, 636, 356), layout.orderPanel());
        assertEquals(new Rect(4, 254, 466, 356), layout.table());
        assertEquals(TradingLayout.ColumnMode.SIDE, layout.columnMode());
        assertEquals(new Rect(322, 72, 466, 250), layout.marketColumn());
        assertEquals(new Rect(4, 72, 318, 250), layout.chart());
    }

    @Test
    void lowScreenDropsHeaderAndMergesMarketColumnIntoChartTabs() {
        // 854×480、GUI 缩放 2 → 427×240：没有标题栏，盘口 / 最新成交并进图表区的页签
        Layout layout = TradingLayout.compute(427, 240, 1);

        assertTrue(layout.compact());
        assertTrue(layout.header().isEmpty());
        assertEquals(new Rect(4, 4, 423, 26), layout.ticker());
        assertEquals(new Rect(273, 30, 423, 236), layout.orderPanel());
        assertEquals(new Rect(4, 162, 269, 236), layout.table());
        assertEquals(TradingLayout.ColumnMode.TABBED, layout.columnMode());
        assertTrue(layout.marketColumn().isEmpty());
        assertEquals(new Rect(4, 30, 269, 158), layout.chart());
    }

    @Test
    void tickerWrapsToTwoRowsOnlyWhenTheScreenIsTallEnough() {
        assertEquals(1, TradingLayout.maxTickerRows(240));
        assertEquals(1, TradingLayout.maxTickerRows(300));
        assertEquals(2, TradingLayout.maxTickerRows(360));
        // 要求的行数超过上限时按上限排
        assertEquals(TradingLayout.compute(427, 240, 1), TradingLayout.compute(427, 240, 3));
    }

    @Test
    void tableGrowsWithTheScreenButStopsAtItsMaximum() {
        Layout large = TradingLayout.compute(1920, 1080, 1);
        assertEquals(TradingLayout.TABLE_MAX_HEIGHT, large.table().height());
        assertEquals(TradingLayout.COLUMN_MAX_WIDTH, large.marketColumn().width());
        assertEquals(TradingLayout.ORDER_PANEL_MAX_WIDTH, large.orderPanel().width());
    }

    @Test
    void everyCommonWindowAndGuiScaleGetsAUsableLayout() {
        Map<String, Layout> checked = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (int[] window : WINDOWS) {
            for (int guiScale : GUI_SCALES) {
                int[] gui = guiSize(window[0], window[1], guiScale);
                for (int rows = 1; rows <= TradingLayout.maxTickerRows(gui[1]); rows++) {
                    String name = window[0] + "x" + window[1] + " scale " + guiScale + " → " + gui[0] + "x" + gui[1] + " rows " + rows;
                    Layout layout = TradingLayout.compute(gui[0], gui[1], rows);
                    checked.put(name, layout);
                    problems.addAll(check(name, gui[0], gui[1], layout));
                }
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(checked.size() >= WINDOWS.length * GUI_SCALES.length, "矩阵没有展开：" + checked.size());
    }

    @Test
    void smallestPossibleGuiStillFits() {
        // 游戏保证缩放后的界面不小于 320×240
        assertEquals(List.of(), check("320x240", 320, 240, TradingLayout.compute(320, 240, 1)));
    }

    private static List<String> check(String name, int width, int height, Layout layout) {
        List<String> problems = new ArrayList<>();
        Map<String, Rect> regions = new LinkedHashMap<>();
        regions.put("header", layout.header());
        regions.put("ticker", layout.ticker());
        regions.put("chart", layout.chart());
        regions.put("marketColumn", layout.marketColumn());
        regions.put("orderPanel", layout.orderPanel());
        regions.put("table", layout.table());
        List<String> names = new ArrayList<>(regions.keySet());
        for (int i = 0; i < names.size(); i++) {
            Rect a = regions.get(names.get(i));
            if (a.isEmpty()) {
                continue;
            }
            if (a.left() < 0 || a.top() < 0 || a.right() > width || a.bottom() > height) {
                problems.add(name + "：" + names.get(i) + " 超出屏幕 " + a);
            }
            for (int j = i + 1; j < names.size(); j++) {
                Rect b = regions.get(names.get(j));
                if (!b.isEmpty() && a.intersects(b)) {
                    problems.add(name + "：" + names.get(i) + " 与 " + names.get(j) + " 重叠 " + a + " / " + b);
                }
            }
        }
        minimum(problems, name, "orderPanel", layout.orderPanel(), TradingLayout.ORDER_PANEL_MIN_WIDTH,
                TradingLayout.ORDER_PANEL_MIN_HEIGHT);
        minimum(problems, name, "table", layout.table(), TradingLayout.TABLE_MIN_WIDTH, TradingLayout.TABLE_MIN_HEIGHT);
        boolean side = layout.columnMode() == TradingLayout.ColumnMode.SIDE;
        minimum(problems, name, "chart", layout.chart(),
                side ? TradingLayout.CHART_MIN_WIDTH_BESIDE_COLUMN : TradingLayout.CHART_MIN_WIDTH, TradingLayout.CHART_MIN_HEIGHT);
        if (side) {
            minimum(problems, name, "marketColumn", layout.marketColumn(), TradingLayout.COLUMN_MIN_WIDTH, TradingLayout.CHART_MIN_HEIGHT);
        } else if (!layout.marketColumn().isEmpty()) {
            problems.add(name + "：盘口并进图表页签时不应再单独占一栏");
        }
        if (layout.compact() != layout.header().isEmpty()) {
            problems.add(name + "：矮屏才省掉标题栏");
        }
        return problems;
    }

    private static void minimum(List<String> problems, String name, String region, Rect rect, int minWidth, int minHeight) {
        if (rect.width() < minWidth || rect.height() < minHeight) {
            problems.add(name + "：" + region + " " + rect.width() + "x" + rect.height() + " 小于最小尺寸 " + minWidth + "x" + minHeight);
        }
    }

    /**
     * 窗口尺寸和 GUI 缩放选项换算成界面坐标的尺寸，照抄原版 {@code Window#calculateScale} 与 {@code setGuiScale}：
     * 缩放从 1 往上加，直到等于选项值，或再加一档就会让界面小于 320×240；界面尺寸是窗口尺寸除以缩放后向上取整。
     */
    static int[] guiSize(int windowWidth, int windowHeight, int guiScale) {
        int scale = 1;
        while (scale != guiScale && scale < windowWidth && scale < windowHeight
                && windowWidth / (scale + 1) >= 320 && windowHeight / (scale + 1) >= 240) {
            scale++;
        }
        return new int[] {Math.ceilDiv(windowWidth, scale), Math.ceilDiv(windowHeight, scale)};
    }

    @Test
    void guiSizeFollowsVanillaScaling() {
        assertEquals(List.of(640, 360), List.of(guiSize(1280, 720, 2)[0], guiSize(1280, 720, 2)[1]));
        // 自动缩放：1280×720 最多 3 倍 → 427×240；1366×768 → 3 倍，宽度向上取整
        assertEquals(List.of(427, 240), List.of(guiSize(1280, 720, 0)[0], guiSize(1280, 720, 0)[1]));
        assertEquals(List.of(456, 256), List.of(guiSize(1366, 768, 0)[0], guiSize(1366, 768, 0)[1]));
        // 854×480 要求 4 倍也只能到 2 倍
        assertEquals(List.of(427, 240), List.of(guiSize(854, 480, 4)[0], guiSize(854, 480, 4)[1]));
    }
}
