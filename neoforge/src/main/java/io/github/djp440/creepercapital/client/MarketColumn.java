package io.github.djp440.creepercapital.client;

import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 盘口栏（币安的「盘口 / 最新成交」栏）：顶部一排页签，下面是当前页。
 * 目前有「盘口」页（{@link OrderBookPage}）和「最新成交」页（{@link LatestTradesPage}）；再加页就往 {@link Page} 里追加一个枚举值，
 * 并在 {@link #extractPage} 的 {@code switch} 里画它。
 * <p>
 * 宽屏时它是图表右边单独的一栏卡片（{@link #extract}，自带页签条）；窄屏时并进图表卡片，由交易界面把这两页和 K 线放进同一排页签，
 * 这里只画页面内容（{@link #extractPage}）。见 {@link TradingLayout.ColumnMode}。
 */
final class MarketColumn {
    static final int TAB_HEIGHT = 16;

    /** 盘口栏的页。 */
    enum Page {
        ORDER_BOOK("screen.creepercapital.trading.sidebar.order_book"),
        LATEST_TRADES("screen.creepercapital.trading.sidebar.latest_trades");

        private final String key;

        Page(String key) {
            this.key = key;
        }

        Component label() {
            return Component.translatable(key);
        }
    }

    private final Font font;
    private final OrderBookPage orderBook;
    private final LatestTradesPage latestTrades;
    private Page page = Page.ORDER_BOOK;
    /** 上一帧页签条的点击区域（单独一栏时才有）。 */
    private int[][] tabBoxes = new int[0][];
    /** 上一帧页面内容的区域，滚轮判定要用。 */
    private TradingLayout.Rect pageArea = TradingLayout.Rect.EMPTY;

    MarketColumn(Font font) {
        this.font = font;
        this.orderBook = new OrderBookPage(font);
        this.latestTrades = new LatestTradesPage(font);
    }

    Page page() {
        return page;
    }

    void setPage(Page page) {
        this.page = page;
    }

    /** 单独一栏：卡片底色、页签条（当前页黄字加下划线）和当前页。 */
    void extract(GuiGraphicsExtractor graphics, TradingLayout.Rect card, String symbol, MarketSnapshot snapshot, int mouseX, int mouseY) {
        graphics.fill(card.left(), card.top(), card.right(), card.bottom(), BinancePalette.PANEL);
        Page[] pages = Page.values();
        Component[] labels = new Component[pages.length];
        for (int i = 0; i < pages.length; i++) {
            labels[i] = pages[i].label();
        }
        tabBoxes = TabStrip.extract(graphics, font, card.left() + 4, card.top(), TAB_HEIGHT, labels, page.ordinal(), 6, mouseX, mouseY);
        graphics.horizontalLine(card.left(), card.right() - 1, card.top() + TAB_HEIGHT, BinancePalette.DIVIDER);
        extractPage(graphics, card.left(), card.top() + TAB_HEIGHT + 1, card.right(), card.bottom(), symbol, snapshot);
    }

    /** 只画当前页的内容（并进图表卡片时用）。 */
    void extractPage(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, String symbol, MarketSnapshot snapshot) {
        pageArea = new TradingLayout.Rect(left, top, right, bottom);
        switch (page) {
            case ORDER_BOOK -> orderBook.extract(graphics, left, top, right, bottom, symbol, snapshot);
            case LATEST_TRADES -> latestTrades.extract(graphics, left, top, right, bottom, symbol);
        }
    }

    /** 不画页签条的帧（并进图表卡片、或图表正显示 K 线）要清掉上一帧的点击区域。 */
    void forgetTabs() {
        tabBoxes = new int[0][];
    }

    void forgetPage() {
        pageArea = TradingLayout.Rect.EMPTY;
    }

    /** 开发用（自动截图）：切到「最新成交」页。 */
    void showLatestTrades() {
        page = Page.LATEST_TRADES;
    }

    /** 滚轮落在页面里且当前是「最新成交」页时翻动列表；返回是否已处理。 */
    boolean mouseScrolled(double x, double y, double scrollY) {
        if (page != Page.LATEST_TRADES || !pageArea.contains(x, y)) {
            return false;
        }
        latestTrades.mouseScrolled(scrollY);
        return true;
    }

    /** 点在页签上切换页；返回是否已处理这次点击。 */
    boolean mouseClicked(double x, double y) {
        int index = TabStrip.indexAt(tabBoxes, x, y);
        if (index < 0) {
            return false;
        }
        page = Page.values()[index];
        return true;
    }
}
