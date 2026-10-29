package io.github.djp440.creepercapital.client;

import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 图表与下单面板之间的右侧栏（币安的「盘口 / 最新成交」栏）：顶部一排页签，下面是当前页。
 * 目前只有「盘口」页（{@link OrderBookPage}）；「最新成交」页（#18）往 {@link Page} 里追加一个枚举值，
 * 并在 {@link #extract} 的 {@code switch} 里画它即可，页签条、栏宽和点击切换都不用改。
 */
final class MarketColumn {
    static final int WIDTH = 144;
    private static final int TAB_HEIGHT = 16;
    private static final int TAB_PADDING = 6;

    /** 右侧栏的页签。 */
    private enum Page {
        ORDER_BOOK("screen.creepercapital.trading.sidebar.order_book");

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
    private Page page = Page.ORDER_BOOK;
    /** 页签条的上沿；每帧渲染时更新，点击判定要用。 */
    private int top;
    private int left;

    MarketColumn(Font font) {
        this.font = font;
        this.orderBook = new OrderBookPage(font);
    }

    void extract(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, String symbol, MarketSnapshot snapshot,
            int mouseX, int mouseY) {
        this.left = left;
        this.top = top;
        graphics.fill(left, top, right, bottom, BinancePalette.PANEL);
        graphics.outline(left, top, right - left, bottom - top, BinancePalette.DIVIDER);

        // 页签条沿用底部表格区的样式：当前页黄字加下划线，悬停的页变亮
        int tabLeft = left + TAB_PADDING;
        for (Page tab : Page.values()) {
            Component label = tab.label();
            int tabWidth = font.width(label) + TAB_PADDING * 2;
            boolean selected = tab == page;
            boolean hovered = mouseX >= tabLeft - TAB_PADDING && mouseX < tabLeft - TAB_PADDING + tabWidth
                    && mouseY >= top && mouseY < top + TAB_HEIGHT;
            graphics.text(font, label, tabLeft, top + (TAB_HEIGHT - font.lineHeight) / 2 + 1,
                    selected ? BinancePalette.BRAND_YELLOW : hovered ? BinancePalette.TEXT_PRIMARY : BinancePalette.TEXT_SECONDARY, false);
            if (selected) {
                graphics.horizontalLine(tabLeft, tabLeft + tabWidth - TAB_PADDING * 2 - 1, top + TAB_HEIGHT - 1, BinancePalette.BRAND_YELLOW);
            }
            tabLeft += tabWidth;
        }
        graphics.horizontalLine(left + 1, right - 2, top + TAB_HEIGHT, BinancePalette.DIVIDER);

        switch (page) {
            case ORDER_BOOK -> orderBook.extract(graphics, left, top + TAB_HEIGHT + 1, right, bottom, symbol, snapshot);
        }
    }

    /** 点在页签上切换页；返回是否已处理这次点击。 */
    boolean mouseClicked(double x, double y) {
        if (y < top || y >= top + TAB_HEIGHT) {
            return false;
        }
        int tabLeft = left;
        for (Page tab : Page.values()) {
            int tabWidth = font.width(tab.label()) + TAB_PADDING * 2;
            if (x >= tabLeft && x < tabLeft + tabWidth) {
                page = tab;
                return true;
            }
            tabLeft += tabWidth;
        }
        return false;
    }
}
