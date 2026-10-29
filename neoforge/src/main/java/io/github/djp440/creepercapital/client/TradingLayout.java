package io.github.djp440.creepercapital.client;

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
     */
    record Layout(boolean compact, Rect header, Rect ticker, Rect chart, Rect marketColumn, Rect orderPanel, Rect table,
            ColumnMode columnMode) {
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
        boolean compact = height < COMPACT_HEIGHT;
        int rows = Math.max(1, Math.min(tickerRows, maxTickerRows(height)));
        Rect header = compact ? Rect.EMPTY : new Rect(0, 0, width, HEADER_HEIGHT);
        int tickerTop = header.bottom() + GAP;
        Rect ticker = new Rect(GAP, tickerTop, width - GAP, tickerTop + rows * tickerRowHeight(height));

        int bodyTop = ticker.bottom() + GAP;
        int bodyBottom = height - GAP;
        int panelWidth = clamp(Math.round(width * ORDER_PANEL_WIDTH_RATIO), ORDER_PANEL_MIN_WIDTH, ORDER_PANEL_MAX_WIDTH);
        Rect orderPanel = new Rect(width - GAP - panelWidth, bodyTop, width - GAP, bodyBottom);

        int leftRight = orderPanel.left() - GAP;
        int leftHeight = bodyBottom - bodyTop;
        int tableHeight = clamp(Math.round(leftHeight * TABLE_HEIGHT_RATIO), TABLE_MIN_HEIGHT, TABLE_MAX_HEIGHT);
        tableHeight = Math.max(TABLE_MIN_HEIGHT, Math.min(tableHeight, leftHeight - GAP - CHART_MIN_HEIGHT));
        Rect table = new Rect(GAP, bodyBottom - tableHeight, leftRight, bodyBottom);

        int chartBottom = table.top() - GAP;
        int columnWidth = clamp(Math.round(width * COLUMN_WIDTH_RATIO), COLUMN_MIN_WIDTH, COLUMN_MAX_WIDTH);
        if (leftRight - GAP >= CHART_MIN_WIDTH_BESIDE_COLUMN + GAP + columnWidth) {
            Rect column = new Rect(leftRight - columnWidth, bodyTop, leftRight, chartBottom);
            Rect chart = new Rect(GAP, bodyTop, column.left() - GAP, chartBottom);
            return new Layout(compact, header, ticker, chart, column, orderPanel, table, ColumnMode.SIDE);
        }
        Rect chart = new Rect(GAP, bodyTop, leftRight, chartBottom);
        return new Layout(compact, header, ticker, chart, Rect.EMPTY, orderPanel, table, ColumnMode.TABBED);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
