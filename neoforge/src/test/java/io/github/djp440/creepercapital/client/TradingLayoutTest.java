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

    // ===== #31：拖拽边框调整区域大小 =====

    /** 用来展开矩阵的拖拽比例：每个边框取「极小 / 缺省 / 中间 / 极大」，两两组合都要成立。 */
    private static final double[] PANEL_RATIOS = {0.001, 0, 0.3, 0.95};
    private static final double[] COLUMN_RATIOS = {0.001, 0, 0.3, 0.95};
    private static final double[] TABLE_RATIOS = {0.001, 0, 0.5, 0.99};

    private static List<TradingLayout.Ratios> ratioMatrix() {
        List<TradingLayout.Ratios> all = new ArrayList<>();
        for (double panel : PANEL_RATIOS) {
            for (double column : COLUMN_RATIOS) {
                for (double table : TABLE_RATIOS) {
                    all.add(new TradingLayout.Ratios(panel, column, table));
                }
            }
        }
        return all;
    }

    @Test
    void defaultRatiosKeepTheDefaultLayout() {
        assertEquals(TradingLayout.compute(640, 360, 2), TradingLayout.compute(640, 360, 2, TradingLayout.Ratios.DEFAULT));
        assertTrue(TradingLayout.compute(640, 360, 2).adjustable());
        assertTrue(TradingLayout.Ratios.DEFAULT.isDefault());
        assertFalse(new TradingLayout.Ratios(0.3, 0, 0).isDefault());
    }

    @Test
    void everyWindowGuiScaleAndDragRatioStaysValid() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (int[] window : WINDOWS) {
            for (int guiScale : GUI_SCALES) {
                int[] gui = guiSize(window[0], window[1], guiScale);
                for (int rows = 1; rows <= TradingLayout.maxTickerRows(gui[1]); rows++) {
                    for (TradingLayout.Ratios ratios : ratioMatrix()) {
                        String name = window[0] + "x" + window[1] + " scale " + guiScale + " → " + gui[0] + "x" + gui[1] + " rows " + rows
                                + " " + ratios;
                        Layout layout = TradingLayout.compute(gui[0], gui[1], rows, ratios);
                        problems.addAll(check(name, gui[0], gui[1], layout));
                        checked++;
                        if (!layout.adjustable()) {
                            // 矮屏 / 窄屏降级时忽略玩家比例
                            assertEquals(TradingLayout.compute(gui[0], gui[1], rows), layout, name);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(checked >= WINDOWS.length * GUI_SCALES.length * ratioMatrix().size(), "矩阵没有展开：" + checked);
    }

    @Test
    void degradedLayoutsIgnoreRatiosAndHaveNoDraggableSeams() {
        TradingLayout.Ratios ratios = new TradingLayout.Ratios(0.4, 0.4, 0.6);
        // 矮屏
        Layout low = TradingLayout.compute(427, 240, 1, ratios);
        assertFalse(low.adjustable());
        assertEquals(TradingLayout.compute(427, 240, 1), low);
        // 窄屏（盘口并进图表页签）
        Layout narrow = TradingLayout.compute(420, 400, 2, ratios);
        assertEquals(TradingLayout.ColumnMode.TABBED, narrow.columnMode());
        assertFalse(narrow.adjustable());
        assertEquals(TradingLayout.compute(420, 400, 2), narrow);
        for (TradingLayout.Seam seam : TradingLayout.Seam.values()) {
            assertTrue(TradingLayout.seam(low, seam).isEmpty());
            assertTrue(TradingLayout.seam(narrow, seam).isEmpty());
        }
        assertEquals(null, TradingLayout.seamAt(narrow, 273, 100));
        // 窗口恢复后重新使用玩家比例
        Layout restored = TradingLayout.compute(640, 360, 2, ratios);
        assertTrue(restored.adjustable());
        assertTrue(restored.orderPanel().width() > TradingLayout.compute(640, 360, 2).orderPanel().width());
    }

    @Test
    void ratiosScaleTheRegionsAndRespectTheirMinimums() {
        Layout base = TradingLayout.compute(640, 360, 2);
        Layout wide = TradingLayout.compute(640, 360, 2, new TradingLayout.Ratios(0.35, 0, 0));
        assertEquals(224, wide.orderPanel().width());
        assertTrue(wide.chart().width() < base.chart().width());
        Layout tall = TradingLayout.compute(640, 360, 2, new TradingLayout.Ratios(0, 0, 0.6));
        assertTrue(tall.table().height() > base.table().height());
        assertTrue(tall.chart().height() >= TradingLayout.CHART_MIN_HEIGHT);
        // 极小比例被最小尺寸托住
        Layout tiny = TradingLayout.compute(640, 360, 2, new TradingLayout.Ratios(0.001, 0.001, 0.001));
        assertEquals(TradingLayout.ORDER_PANEL_MIN_WIDTH, tiny.orderPanel().width());
        assertEquals(TradingLayout.COLUMN_MIN_WIDTH, tiny.marketColumn().width());
        assertEquals(TradingLayout.TABLE_MIN_HEIGHT, tiny.table().height());
        // 极大比例让出其余区域的最小尺寸
        Layout huge = TradingLayout.compute(640, 360, 2, new TradingLayout.Ratios(0.95, 0.95, 0.99));
        assertEquals(TradingLayout.CHART_MIN_WIDTH_BESIDE_COLUMN, huge.chart().width());
        assertEquals(TradingLayout.CHART_MIN_HEIGHT, huge.chart().height());
    }

    @Test
    void seamsSitBetweenTheCardsAndOnlyThereAreGrabbable() {
        Layout layout = TradingLayout.compute(640, 360, 2);
        assertEquals(new Rect(layout.orderPanel().left() - TradingLayout.GAP, layout.orderPanel().top(), layout.orderPanel().left(),
                layout.orderPanel().bottom()), TradingLayout.seam(layout, TradingLayout.Seam.ORDER_PANEL));
        assertEquals(new Rect(layout.chart().right(), layout.chart().top(), layout.marketColumn().left(), layout.chart().bottom()),
                TradingLayout.seam(layout, TradingLayout.Seam.MARKET_COLUMN));
        assertEquals(new Rect(layout.table().left(), layout.chart().bottom(), layout.table().right(), layout.table().top()),
                TradingLayout.seam(layout, TradingLayout.Seam.TABLE));
        for (TradingLayout.Seam seam : TradingLayout.Seam.values()) {
            Rect strip = TradingLayout.seam(layout, seam);
            int x = (strip.left() + strip.right()) / 2;
            int y = (strip.top() + strip.bottom()) / 2;
            assertEquals(seam, TradingLayout.seamAt(layout, x, y));
        }
        // 卡片内部点不到边框
        assertEquals(null, TradingLayout.seamAt(layout, layout.chart().left() + 40, layout.chart().top() + 40));
        assertEquals(null, TradingLayout.seamAt(layout, layout.orderPanel().left() + 40, layout.orderPanel().top() + 40));
        assertEquals(null, TradingLayout.seamAt(layout, layout.table().left() + 40, layout.table().top() + 20));
        assertEquals(null, TradingLayout.seamAt(layout, layout.marketColumn().left() + 40, layout.marketColumn().top() + 40));
        // 标题栏、行情栏上没有边框
        assertEquals(null, TradingLayout.seamAt(layout, 320, 8));
        assertEquals(null, TradingLayout.seamAt(layout, 320, 40));
    }

    @Test
    void draggingASeamMovesItUnderTheMouseAndClampsAtTheMinimums() {
        TradingLayout.Ratios none = TradingLayout.Ratios.DEFAULT;
        // 往左拖订单面板边框：面板变宽，边框中心跟到鼠标
        TradingLayout.Ratios panel = TradingLayout.dragged(640, 360, 2, none, TradingLayout.Seam.ORDER_PANEL, 400, 200);
        Layout wide = TradingLayout.compute(640, 360, 2, panel);
        assertEquals(400, TradingLayout.seam(wide, TradingLayout.Seam.ORDER_PANEL).left() + TradingLayout.GAP / 2);
        // 往右拖到底：面板停在最小宽度
        TradingLayout.Ratios narrowest = TradingLayout.dragged(640, 360, 2, panel, TradingLayout.Seam.ORDER_PANEL, 639, 200);
        assertEquals(TradingLayout.ORDER_PANEL_MIN_WIDTH, TradingLayout.compute(640, 360, 2, narrowest).orderPanel().width());
        // 往左拖到底：图表停在最小宽度，保存的比例就是实际生效的（再拖回来立刻跟手，没有死区）
        TradingLayout.Ratios widest = TradingLayout.dragged(640, 360, 2, none, TradingLayout.Seam.ORDER_PANEL, 0, 200);
        assertEquals(TradingLayout.CHART_MIN_WIDTH_BESIDE_COLUMN, TradingLayout.compute(640, 360, 2, widest).chart().width());
        TradingLayout.Ratios back = TradingLayout.dragged(640, 360, 2, widest, TradingLayout.Seam.ORDER_PANEL, 450, 200);
        assertEquals(450, TradingLayout.seam(TradingLayout.compute(640, 360, 2, back), TradingLayout.Seam.ORDER_PANEL).left() + 2);

        // 盘口栏边框
        TradingLayout.Ratios column = TradingLayout.dragged(640, 360, 2, none, TradingLayout.Seam.MARKET_COLUMN, 250, 150);
        Layout withColumn = TradingLayout.compute(640, 360, 2, column);
        assertEquals(250, TradingLayout.seam(withColumn, TradingLayout.Seam.MARKET_COLUMN).left() + 2);
        assertTrue(withColumn.marketColumn().width() > TradingLayout.compute(640, 360, 2).marketColumn().width());

        // 表格上边框
        TradingLayout.Ratios table = TradingLayout.dragged(640, 360, 2, none, TradingLayout.Seam.TABLE, 100, 200);
        Layout withTable = TradingLayout.compute(640, 360, 2, table);
        assertEquals(200, TradingLayout.seam(withTable, TradingLayout.Seam.TABLE).top() + 2);
        TradingLayout.Ratios shortest = TradingLayout.dragged(640, 360, 2, table, TradingLayout.Seam.TABLE, 100, 9999);
        assertEquals(TradingLayout.TABLE_MIN_HEIGHT, TradingLayout.compute(640, 360, 2, shortest).table().height());

        // 拖动只改被拖的那一项
        assertEquals(0, panel.column());
        assertEquals(0, panel.table());
        // 降级布局里拖不动
        assertEquals(none, TradingLayout.dragged(427, 240, 1, none, TradingLayout.Seam.ORDER_PANEL, 100, 100));
    }

    @Test
    void draggedRatiosKeepTheLayoutValidAcrossWindowsAndGuiScales() {
        List<String> problems = new ArrayList<>();
        for (int[] window : WINDOWS) {
            for (int guiScale : GUI_SCALES) {
                int[] gui = guiSize(window[0], window[1], guiScale);
                TradingLayout.Ratios ratios = TradingLayout.Ratios.DEFAULT;
                for (double fraction : new double[] {0.05, 0.5, 0.95}) {
                    for (TradingLayout.Seam seam : TradingLayout.Seam.values()) {
                        ratios = TradingLayout.dragged(gui[0], gui[1], 1, ratios, seam, gui[0] * fraction, gui[1] * fraction);
                        String name = window[0] + "x" + window[1] + " scale " + guiScale + " drag " + seam + " to " + fraction;
                        problems.addAll(check(name, gui[0], gui[1], TradingLayout.compute(gui[0], gui[1], 1, ratios)));
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    /** #39：输入框的命中区域必须和绘制出来的输入框一致（含框内边距与右侧单位文字），在整个「窗口 × GUI 缩放」矩阵下成立。 */
    @Test
    void inputFieldsAreClickableOverTheirWholeDrawnArea() {
        List<String> problems = new ArrayList<>();
        for (int[] window : WINDOWS) {
            for (int guiScale : GUI_SCALES) {
                int[] gui = guiSize(window[0], window[1], guiScale);
                for (int rows = 1; rows <= TradingLayout.maxTickerRows(gui[1]); rows++) {
                    Rect panel = TradingLayout.compute(gui[0], gui[1], rows).orderPanel();
                    for (boolean limit : new boolean[] {false, true}) {
                        for (boolean protection : new boolean[] {false, true}) {
                            String name = window[0] + "x" + window[1] + " scale " + guiScale + " rows " + rows + " limit " + limit
                                    + " protection " + protection;
                            problems.addAll(checkFields(name, panel, OrderPanelLayout.compute(panel, limit, protection), limit, protection));
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void inputFieldsFollowTheOrderPanelPageState() {
        Rect panel = TradingLayout.compute(640, 360, 2).orderPanel();
        OrderPanelLayout.Fields market = OrderPanelLayout.compute(panel, false, false);
        assertTrue(market.price().isEmpty());
        assertTrue(market.takeProfit().isEmpty());
        assertTrue(market.stopLoss().isEmpty());
        assertFalse(market.quantity().isEmpty());

        OrderPanelLayout.Fields limitProtection = OrderPanelLayout.compute(panel, true, true);
        assertFalse(limitProtection.price().isEmpty());
        assertFalse(limitProtection.takeProfit().isEmpty());
        assertFalse(limitProtection.stopLoss().isEmpty());
        // 限价多一行委托价，数量输入框随之下移
        assertTrue(limitProtection.quantity().top() > market.quantity().top());
    }

    @Test
    void clickInTheUnitLabelOrPaddingStillFocusesTheFieldAndLandsInsideItsTextArea() {
        Rect panel = TradingLayout.compute(640, 360, 2).orderPanel();
        OrderPanelLayout.Fields fields = OrderPanelLayout.compute(panel, true, false);
        Rect quantity = fields.quantity();
        // 右侧单位文字所在的位置、上边距、左边距都算点中数量输入框
        assertEquals(OrderPanelLayout.Input.QUANTITY, fields.hitTest(quantity.right() - 2, quantity.top() + 8));
        assertEquals(OrderPanelLayout.Input.QUANTITY, fields.hitTest(quantity.left(), quantity.top()));
        assertEquals(OrderPanelLayout.Input.QUANTITY, fields.hitTest(quantity.left() + 20, quantity.bottom() - 1));
        // 框外不算
        assertEquals(null, fields.hitTest(quantity.right(), quantity.top() + 8));
        assertEquals(null, fields.hitTest(quantity.left() + 20, quantity.bottom()));
        // 点击落在单位文字上时，交给输入框的坐标被夹回文字区域内，光标落在文字末尾
        Rect text = OrderPanelLayout.textArea(quantity, 30);
        assertEquals(text.right() - 1, OrderPanelLayout.clampX(text, quantity.right() - 2));
        assertEquals(text.left(), OrderPanelLayout.clampX(text, quantity.left()));
        assertEquals(text.top(), OrderPanelLayout.clampY(text, quantity.top()));
        assertEquals(text.bottom() - 1, OrderPanelLayout.clampY(text, quantity.bottom() - 1));
    }

    private static List<String> checkFields(String name, Rect panel, OrderPanelLayout.Fields fields, boolean limit, boolean protection) {
        List<String> problems = new ArrayList<>();
        Map<OrderPanelLayout.Input, Rect> expected = new LinkedHashMap<>();
        expected.put(OrderPanelLayout.Input.PRICE, fields.price());
        expected.put(OrderPanelLayout.Input.QUANTITY, fields.quantity());
        expected.put(OrderPanelLayout.Input.TAKE_PROFIT, fields.takeProfit());
        expected.put(OrderPanelLayout.Input.STOP_LOSS, fields.stopLoss());
        if (fields.price().isEmpty() == limit) {
            problems.add(name + "：委托价输入框只在限价模式出现");
        }
        if (fields.takeProfit().isEmpty() == protection || fields.stopLoss().isEmpty() == protection) {
            problems.add(name + "：止盈止损输入框只在止盈止损页出现");
        }
        for (Map.Entry<OrderPanelLayout.Input, Rect> entry : expected.entrySet()) {
            Rect field = entry.getValue();
            if (field.isEmpty()) {
                continue;
            }
            if (field.left() < panel.left() || field.top() < panel.top() || field.right() > panel.right() || field.bottom() > panel.bottom()) {
                problems.add(name + "：" + entry.getKey() + " 超出下单面板 " + field + " / " + panel);
            }
            Rect text = OrderPanelLayout.textArea(field, entry.getKey() == OrderPanelLayout.Input.QUANTITY ? 40 : 0);
            if (text.isEmpty() || text.left() < field.left() || text.right() > field.right() || text.top() < field.top()
                    || text.bottom() > field.bottom()) {
                problems.add(name + "：" + entry.getKey() + " 文字区域不在输入框内 " + text + " / " + field);
            }
        }
        // 逐像素：面板范围内每个点的命中结果必须正好等于「画着哪个输入框」
        for (int x = panel.left(); x < panel.right(); x++) {
            for (int y = panel.top(); y < panel.bottom(); y++) {
                OrderPanelLayout.Input drawn = null;
                for (Map.Entry<OrderPanelLayout.Input, Rect> entry : expected.entrySet()) {
                    if (entry.getValue().contains(x, y)) {
                        drawn = entry.getKey();
                    }
                }
                if (fields.hitTest(x, y) != drawn) {
                    problems.add(name + "：(" + x + "," + y + ") 命中 " + fields.hitTest(x, y) + "，绘制 " + drawn);
                    return problems;
                }
            }
        }
        return problems;
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
