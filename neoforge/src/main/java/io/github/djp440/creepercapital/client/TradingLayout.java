package io.github.djp440.creepercapital.client;

import org.jspecify.annotations.Nullable;

/**
 * 交易界面的区域划分（纯计算，不依赖 Minecraft，单元测试在常见窗口尺寸 × GUI 缩放的矩阵上验证）。
 * <p>
 * 合约网页式布局：深色底上一块块卡片，卡片之间留 {@link #GAP}。自上而下是标题栏、行情栏，
 * 下面左侧是 K 线图（右边并排盘口 / 最新成交栏）和底部表格区，右侧是通栏的下单面板。
 * <p>
 * 屏幕变小时的降级顺序：
 * <ol>
 * <li>高度不足 {@link #COMPACT_HEIGHT}（矮屏）：省掉标题栏，行情栏只排一行（放不下的次要数据由行情栏自己丢掉）；</li>
 * <li>左侧宽度不够图表与盘口栏并排：盘口 / 最新成交并进图表区，与 K 线一起用页签切换（{@link ColumnMode#TABBED}）；</li>
 * <li>下单面板、盘口栏按屏幕宽度比例取宽，限制在最小 / 最大宽度之间；表格区按左侧高度比例取高，同样有上下限，且优先保证图表的最小高度；</li>
 * <li>表格放不下全部列时由表格自己隐藏次要列（见 {@link LayoutFit#columns}）。</li>
 * </ol>
 * <p>
 * 标准布局（没有降级）里玩家可以拖拽卡片之间的三条边框（{@link Seam}）调整下单面板宽度、盘口栏宽度和表格区高度，
 * 结果以占屏幕宽度 / 左侧高度的比例（{@link Ratios}）保存，受各区域最小尺寸约束；降级布局忽略比例、也不可拖拽，窗口恢复后重新使用。
 */
final class TradingLayout {
    /** 卡片之间、卡片与屏幕边缘的间距。 */
    static final int GAP = 4;
    static final int HEADER_HEIGHT = 16;
    /** 行情栏每行的高度：名称、数值上下两行字。矮屏略紧凑。 */
    static final int TICKER_ROW_HEIGHT = 24;
    static final int COMPACT_TICKER_ROW_HEIGHT = 22;
    /** 屏幕高度低于它就是矮屏（854×480 两倍缩放、1080p 自动缩放之类）。 */
    static final int COMPACT_HEIGHT = 330;

    /** 下单面板：最坏情况（限价 + 有仓位 + 预估页）的内容高度加一行下单结果。 */
    static final int ORDER_PANEL_MIN_WIDTH = 150;
    static final int ORDER_PANEL_MAX_WIDTH = 200;
    static final int ORDER_PANEL_MIN_HEIGHT = 196;
    private static final float ORDER_PANEL_WIDTH_RATIO = 0.26f;

    static final int COLUMN_MIN_WIDTH = 144;
    static final int COLUMN_MAX_WIDTH = 200;
    private static final float COLUMN_WIDTH_RATIO = 0.2f;

    /** 图表：与盘口栏并排时至少要这么宽，否则盘口栏并进图表页签；并进以后图表的最小宽度更小。 */
    static final int CHART_MIN_WIDTH_BESIDE_COLUMN = 220;
    static final int CHART_MIN_WIDTH = 150;
    static final int CHART_MIN_HEIGHT = 100;

    /** 表格区：页签条 + 表头 + 三行数据。 */
    static final int TABLE_MIN_WIDTH = 150;
    static final int TABLE_MIN_HEIGHT = 64;
    static final int TABLE_MAX_HEIGHT = 260;
    private static final float TABLE_HEIGHT_RATIO = 0.36f;

    /** 盘口 / 最新成交放在哪里。 */
    enum ColumnMode {
        /** 图表右边单独一栏。 */
        SIDE,
        /** 并进图表区，和 K 线一起用页签切换。 */
        TABBED
    }

    /** 屏幕上的矩形区域，右、下边界不含。 */
    record Rect(int left, int top, int right, int bottom) {
        static final Rect EMPTY = new Rect(0, 0, 0, 0);

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        boolean isEmpty() {
            return width() <= 0 || height() <= 0;
        }

        boolean intersects(Rect other) {
            return left < other.right && other.left < right && top < other.bottom && other.top < bottom;
        }

        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    /**
     * 一次布局的结果。
     *
     * @param compact      是否矮屏（没有标题栏，行情栏只有一行）
     * @param header       标题栏（矮屏时为空）
     * @param ticker       行情栏卡片
     * @param chart        图表卡片（含周期页签；{@link ColumnMode#TABBED} 时还含盘口 / 最新成交页签）
     * @param marketColumn 盘口栏卡片（{@link ColumnMode#TABBED} 时为空）
     * @param orderPanel   下单面板卡片
     * @param table        底部表格区卡片
     * @param adjustable   是标准布局，卡片之间的边框可以拖拽（降级布局不可拖，也忽略玩家比例）
     */
    record Layout(boolean compact, Rect header, Rect ticker, Rect chart, Rect marketColumn, Rect orderPanel, Rect table,
            ColumnMode columnMode, boolean adjustable) {
    }

    /**
     * 玩家拖拽出来的区域比例，0 表示用缺省尺寸。
     *
     * @param orderPanel 下单面板宽度占屏幕宽度的比例
     * @param column     盘口 / 最新成交栏宽度占屏幕宽度的比例
     * @param table      底部表格区高度占左侧区域高度的比例
     */
    record Ratios(double orderPanel, double column, double table) {
        static final Ratios DEFAULT = new Ratios(0, 0, 0);

        boolean isDefault() {
            return orderPanel <= 0 && column <= 0 && table <= 0;
        }
    }

    private TradingLayout() {
    }

    /** 行情栏最多排几行：矮屏只排一行。 */
    static int maxTickerRows(int height) {
        return height < COMPACT_HEIGHT ? 1 : 2;
    }

    static int tickerRowHeight(int height) {
        return height < COMPACT_HEIGHT ? COMPACT_TICKER_ROW_HEIGHT : TICKER_ROW_HEIGHT;
    }

    /**
     * @param width      屏幕宽度（界面坐标）
     * @param height     屏幕高度（界面坐标）
     * @param tickerRows 行情栏需要的行数，超过 {@link #maxTickerRows} 时按上限排
     */
    static Layout compute(int width, int height, int tickerRows) {
        return compute(width, height, tickerRows, Ratios.DEFAULT);
    }

    /**
     * 带玩家拖拽比例的布局。只有标准布局（有标题栏、盘口栏单独成栏）才使用比例；矮屏 / 窄屏降级时忽略比例，
     * 窗口恢复后重新使用。降级与否只看缺省尺寸，与玩家比例无关，所以拖拽不会触发或破坏降级。
     */
    static Layout compute(int width, int height, int tickerRows, Ratios ratios) {
        boolean compact = height < COMPACT_HEIGHT;
        int rows = Math.max(1, Math.min(tickerRows, maxTickerRows(height)));
        Rect header = compact ? Rect.EMPTY : new Rect(0, 0, width, HEADER_HEIGHT);
        int tickerTop = header.bottom() + GAP;
        Rect ticker = new Rect(GAP, tickerTop, width - GAP, tickerTop + rows * tickerRowHeight(height));

        int bodyTop = ticker.bottom() + GAP;
        int bodyBottom = height - GAP;
        int defaultPanelWidth = clamp(Math.round(width * ORDER_PANEL_WIDTH_RATIO), ORDER_PANEL_MIN_WIDTH, ORDER_PANEL_MAX_WIDTH);
        int defaultColumnWidth = clamp(Math.round(width * COLUMN_WIDTH_RATIO), COLUMN_MIN_WIDTH, COLUMN_MAX_WIDTH);
        // 左侧可用宽度（缺省下单面板宽度时）放得下图表与盘口栏并排才是标准布局
        boolean side = width - GAP * 3 - defaultPanelWidth >= CHART_MIN_WIDTH_BESIDE_COLUMN + GAP + defaultColumnWidth;
        boolean adjustable = side && !compact;
        Ratios custom = adjustable ? ratios : Ratios.DEFAULT;

        int panelWidth = defaultPanelWidth;
        if (custom.orderPanel() > 0) {
            int maxPanel = width - GAP * 3 - CHART_MIN_WIDTH_BESIDE_COLUMN - GAP - COLUMN_MIN_WIDTH;
            panelWidth = clamp(Math.round((float) (width * custom.orderPanel())), ORDER_PANEL_MIN_WIDTH, maxPanel);
        }
        Rect orderPanel = new Rect(width - GAP - panelWidth, bodyTop, width - GAP, bodyBottom);

        int leftRight = orderPanel.left() - GAP;
        int leftHeight = bodyBottom - bodyTop;
        int maxTableHeight = leftHeight - GAP - CHART_MIN_HEIGHT;
        int tableHeight;
        if (custom.table() > 0) {
            tableHeight = clamp(Math.round((float) (leftHeight * custom.table())), TABLE_MIN_HEIGHT, maxTableHeight);
        } else {
            tableHeight = clamp(Math.round(leftHeight * TABLE_HEIGHT_RATIO), TABLE_MIN_HEIGHT, TABLE_MAX_HEIGHT);
            tableHeight = Math.max(TABLE_MIN_HEIGHT, Math.min(tableHeight, maxTableHeight));
        }
        Rect table = new Rect(GAP, bodyBottom - tableHeight, leftRight, bodyBottom);

        int chartBottom = table.top() - GAP;
        if (side) {
            // 图表至少留 CHART_MIN_WIDTH_BESIDE_COLUMN；玩家把下单面板拉宽后，盘口栏也跟着让出宽度
            int maxColumn = leftRight - GAP - GAP - CHART_MIN_WIDTH_BESIDE_COLUMN;
            int columnWidth = clamp(custom.column() > 0 ? Math.round((float) (width * custom.column())) : defaultColumnWidth,
                    COLUMN_MIN_WIDTH, maxColumn);
            Rect column = new Rect(leftRight - columnWidth, bodyTop, leftRight, chartBottom);
            Rect chart = new Rect(GAP, bodyTop, column.left() - GAP, chartBottom);
            return new Layout(compact, header, ticker, chart, column, orderPanel, table, ColumnMode.SIDE, adjustable);
        }
        Rect chart = new Rect(GAP, bodyTop, leftRight, chartBottom);
        return new Layout(compact, header, ticker, chart, Rect.EMPTY, orderPanel, table, ColumnMode.TABBED, false);
    }

    /** 玩家可以拖拽的三条边框。 */
    enum Seam {
        /** 左侧区域与下单面板之间的竖缝。 */
        ORDER_PANEL,
        /** 图表与盘口 / 最新成交栏之间的竖缝。 */
        MARKET_COLUMN,
        /** 图表 / 盘口栏与底部表格之间的横缝。 */
        TABLE
    }

    /** 边框两侧各多出这么多界面像素也算抓得住。 */
    static final int SEAM_SLOP = 2;

    /** 边框（卡片之间的缝）所在的矩形；布局不可拖拽时为空。 */
    static Rect seam(Layout layout, Seam seam) {
        if (!layout.adjustable()) {
            return Rect.EMPTY;
        }
        return switch (seam) {
            case ORDER_PANEL -> new Rect(layout.orderPanel().left() - GAP, layout.orderPanel().top(), layout.orderPanel().left(),
                    layout.orderPanel().bottom());
            case MARKET_COLUMN -> new Rect(layout.chart().right(), layout.chart().top(), layout.marketColumn().left(),
                    layout.chart().bottom());
            case TABLE -> new Rect(layout.table().left(), layout.chart().bottom(), layout.table().right(), layout.table().top());
        };
    }

    /** 鼠标所在的可拖拽边框（含两侧 {@link #SEAM_SLOP} 的容差）；不在边框上返回 {@code null}。 */
    @Nullable
    static Seam seamAt(Layout layout, double x, double y) {
        for (Seam seam : Seam.values()) {
            Rect strip = seam(layout, seam);
            if (strip.isEmpty()) {
                continue;
            }
            boolean vertical = seam != Seam.TABLE;
            Rect grab = vertical ? new Rect(strip.left() - SEAM_SLOP, strip.top(), strip.right() + SEAM_SLOP, strip.bottom())
                    : new Rect(strip.left(), strip.top() - SEAM_SLOP, strip.right(), strip.bottom() + SEAM_SLOP);
            if (grab.contains(x, y)) {
                return seam;
            }
        }
        return null;
    }

    /**
     * 把边框拖到鼠标位置（边框中心跟着鼠标）后的新比例。比例受最小尺寸约束，保存的是实际生效的值，
     * 所以拖到头再往回拖立刻跟手；布局不可拖拽时原样返回。
     */
    static Ratios dragged(int width, int height, int tickerRows, Ratios current, Seam seam, double mouseX, double mouseY) {
        Layout layout = compute(width, height, tickerRows, current);
        if (!layout.adjustable()) {
            return current;
        }
        int leftHeight = layout.table().bottom() - layout.orderPanel().top();
        switch (seam) {
            case ORDER_PANEL -> {
                int panelWidth = Math.max(1, width - GAP - (int) Math.round(mouseX + GAP / 2.0));
                Ratios trial = new Ratios((double) panelWidth / width, current.column(), current.table());
                return new Ratios((double) compute(width, height, tickerRows, trial).orderPanel().width() / width, current.column(),
                        current.table());
            }
            case MARKET_COLUMN -> {
                int columnWidth = Math.max(1, layout.orderPanel().left() - GAP - (int) Math.round(mouseX + GAP / 2.0));
                Ratios trial = new Ratios(current.orderPanel(), (double) columnWidth / width, current.table());
                return new Ratios(current.orderPanel(),
                        (double) compute(width, height, tickerRows, trial).marketColumn().width() / width, current.table());
            }
            default -> {
                int tableHeight = Math.max(1, layout.table().bottom() - (int) Math.round(mouseY + GAP / 2.0));
                Ratios trial = new Ratios(current.orderPanel(), current.column(), (double) tableHeight / leftHeight);
                return new Ratios(current.orderPanel(), current.column(),
                        (double) compute(width, height, tickerRows, trial).table().height() / leftHeight);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
