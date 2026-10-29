package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 交易界面（币安深色主题）：顶部行情栏、周期切换栏和 K 线图。
 * <p>
 * 行情栏左侧是当前合约的交易代码，市场里有多个合约时带下拉箭头，点开列表可以切换合约（各合约的行情、K 线互相独立）；
 * 其后依次是最新价格、标记价格、指数价格、资金费率与结算倒计时、市场时间，以及 24h 涨跌、最高、最低、成交量；
 * 放不下时自动折到下一行。界面每帧读取客户端缓存的行情，服务端每个行情步同步一次，所以数值每个行情步刷新一次。
 * K 线图见 {@link KlineChart}，周期切换后向服务端拉取该周期的历史，之后随行情步增量更新。
 */
public final class TradingScreen extends Screen {
    private static final int HEADER_HEIGHT = 20;
    private static final int TICKER_ROW_HEIGHT = 31;
    private static final int PADDING = 12;
    private static final int STAT_GAP = 24;
    private static final int LINE_GAP = 3;
    private static final int TAB_HEIGHT = 14;
    private static final int TAB_PADDING = 6;
    private static final int MENU_ROW_HEIGHT = 16;
    private static final int MENU_PADDING = 8;
    /** 交易代码与下拉箭头的间距；箭头是 4 行逐行收窄的小三角。 */
    private static final int CARET_GAP = 6;
    private static final int CARET_HALF_WIDTH = 3;
    private static final int HOVER = 0xFF1E2329;

    private final KlineChart chart;
    /** 周期切换栏的上沿；每帧渲染时更新，点击判定要用。 */
    private int tabsTop;
    /** 行情栏的上沿，合约下拉列表挂在它的第一行下面；每帧渲染时更新。 */
    private int tickerTop = HEADER_HEIGHT + 1;
    /** 交易代码按钮的右沿（点击区域从屏幕左边缘算起）；每帧渲染时更新。 */
    private int symbolButtonRight;
    private boolean contractMenuOpen;

    private TradingScreen() {
        super(Component.translatable("screen.creepercapital.trading.title"));
        this.chart = new KlineChart(Minecraft.getInstance().font);
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setScreen(new TradingScreen());
        }
    }

    /** 开发用（自动截图）：打开交易界面并展开合约下拉列表。 */
    public static void openWithContractMenu() {
        open();
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.contractMenuOpen = true;
        }
    }

    @Override
    protected void init() {
        ClientKlines.open();
    }

    /** 单人游戏里打开交易界面不暂停游戏，市场照常推进。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 再按一次打开界面的快捷键就关闭界面。 */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (contractMenuOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            contractMenuOpen = false;
            return true;
        }
        if (CreeperCapitalClient.OPEN_TRADING_KEY.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && clickedContractControls(event.x(), event.y())) {
            return true;
        }
        if (event.button() == 0 && event.y() >= tabsTop && event.y() < tabsTop + TAB_HEIGHT) {
            int x = PADDING;
            for (CandlePeriod period : CandlePeriod.values()) {
                int tabWidth = tabWidth(period);
                if (event.x() >= x && event.x() < x + tabWidth) {
                    ClientKlines.select(period);
                    return true;
                }
                x += tabWidth;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** 点在交易代码按钮上就展开/收起合约列表；列表展开时点列表项切换合约，点别处只收起列表。返回是否已处理这次点击。 */
    private boolean clickedContractControls(double x, double y) {
        List<String> symbols = ClientMarket.symbols();
        if (symbols.size() > 1 && isOnSymbolButton(x, y)) {
            contractMenuOpen = !contractMenuOpen;
            return true;
        }
        if (!contractMenuOpen) {
            return false;
        }
        int index = menuIndexAt(symbols, x, y);
        if (index >= 0) {
            ClientMarket.select(symbols.get(index));
        }
        contractMenuOpen = false;
        return true;
    }

    private boolean isOnSymbolButton(double x, double y) {
        return x >= 0 && x < symbolButtonRight && y >= tickerTop && y < tickerTop + TICKER_ROW_HEIGHT;
    }

    private int menuLeft() {
        return PADDING - 4;
    }

    private int menuTop() {
        return tickerTop + TICKER_ROW_HEIGHT;
    }

    private int menuWidth(List<String> symbols) {
        int widest = 0;
        for (String symbol : symbols) {
            widest = Math.max(widest, font.width(symbol));
        }
        return widest + MENU_PADDING * 2;
    }

    /** 鼠标所在的列表项下标；不在列表里返回 -1。 */
    private int menuIndexAt(List<String> symbols, double x, double y) {
        int left = menuLeft();
        int top = menuTop();
        if (x < left || x >= left + menuWidth(symbols) || y < top || y >= top + symbols.size() * MENU_ROW_HEIGHT) {
            return -1;
        }
        return (int) ((y - top) / MENU_ROW_HEIGHT);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BinancePalette.BACKGROUND);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        extractHeader(graphics);
        MarketSnapshot snapshot = ClientMarket.latest().orElse(null);
        if (snapshot == null) {
            extractWaiting(graphics);
            return;
        }
        int tickerBottom = extractTicker(graphics, snapshot, mouseX, mouseY);
        tabsTop = tickerBottom + 5;
        extractTabs(graphics, mouseX, mouseY);
        extractChart(graphics, tabsTop + TAB_HEIGHT + 4, mouseX, mouseY);
        if (contractMenuOpen) {
            extractContractMenu(graphics, snapshot.symbol(), mouseX, mouseY);
        }
    }

    /** 合约下拉列表：盖在图表之上，当前合约黄字，鼠标悬停的一项底色变亮。 */
    private void extractContractMenu(GuiGraphicsExtractor graphics, String current, int mouseX, int mouseY) {
        List<String> symbols = ClientMarket.symbols();
        int left = menuLeft();
        int top = menuTop();
        int right = left + menuWidth(symbols);
        int bottom = top + symbols.size() * MENU_ROW_HEIGHT;
        graphics.fill(left, top, right, bottom, BinancePalette.PANEL);
        graphics.horizontalLine(left, right - 1, top, BinancePalette.DIVIDER);
        graphics.horizontalLine(left, right - 1, bottom - 1, BinancePalette.DIVIDER);
        graphics.verticalLine(left, top, bottom - 1, BinancePalette.DIVIDER);
        graphics.verticalLine(right - 1, top, bottom - 1, BinancePalette.DIVIDER);
        int hovered = menuIndexAt(symbols, mouseX, mouseY);
        for (int i = 0; i < symbols.size(); i++) {
            int rowTop = top + i * MENU_ROW_HEIGHT;
            if (i == hovered) {
                graphics.fill(left + 1, rowTop, right - 1, rowTop + MENU_ROW_HEIGHT, HOVER);
            }
            String symbol = symbols.get(i);
            int color = symbol.equals(current) ? BinancePalette.BRAND_YELLOW
                    : i == hovered ? BinancePalette.TEXT_PRIMARY : BinancePalette.TEXT_SECONDARY;
            graphics.text(font, symbol, left + MENU_PADDING, centeredTextY(rowTop, rowTop + MENU_ROW_HEIGHT), color, false);
        }
    }

    private void extractHeader(GuiGraphicsExtractor graphics) {
        graphics.fill(0, 0, width, HEADER_HEIGHT, BinancePalette.PANEL);
        graphics.text(font, title, PADDING, centeredTextY(0, HEADER_HEIGHT), BinancePalette.BRAND_YELLOW, false);
        graphics.horizontalLine(0, width, HEADER_HEIGHT, BinancePalette.DIVIDER);
    }

    /** 一栏「名称在上、数值在下」的行情数据。 */
    private record Stat(Component label, String value, int valueColor) {
    }

    /** 一栏数据排在第几行、横向从哪里开始。 */
    private record Placed(Stat stat, int row, int x) {
    }

    /**
     * 顶部行情栏：交易代码后依次是各项行情数据，排版仿币安的「名称在上、数值在下」，一行放不下就折到下一行。
     *
     * @return 行情栏下沿的纵坐标
     */
    private int extractTicker(GuiGraphicsExtractor graphics, MarketSnapshot snapshot, int mouseX, int mouseY) {
        int top = HEADER_HEIGHT + 1;
        tickerTop = top;
        Component perpetual = Component.translatable("screen.creepercapital.trading.perpetual");
        boolean switchable = ClientMarket.symbols().size() > 1;
        int caretSpace = switchable ? CARET_GAP + CARET_HALF_WIDTH * 2 + 1 : 0;
        int symbolWidth = Math.max(font.width(snapshot.symbol()) + caretSpace, font.width(perpetual));
        symbolButtonRight = PADDING + symbolWidth + STAT_GAP / 2;

        List<Placed> placed = new ArrayList<>();
        int x = PADDING + symbolWidth + STAT_GAP;
        int row = 0;
        for (Stat stat : stats(snapshot)) {
            int statWidth = Math.max(font.width(stat.label()), font.width(stat.value()));
            if (x + statWidth > width - PADDING && x > PADDING) {
                row++;
                x = PADDING;
            }
            placed.add(new Placed(stat, row, x));
            x += statWidth + STAT_GAP;
        }

        int bottom = top + (row + 1) * TICKER_ROW_HEIGHT;
        graphics.fill(0, top, width, bottom, BinancePalette.PANEL);
        int upperY = top + (TICKER_ROW_HEIGHT - font.lineHeight * 2 - LINE_GAP) / 2;
        if (switchable && (contractMenuOpen || isOnSymbolButton(mouseX, mouseY))) {
            graphics.fill(0, top, symbolButtonRight, top + TICKER_ROW_HEIGHT, HOVER);
        }
        graphics.text(font, snapshot.symbol(), PADDING, upperY, BinancePalette.TEXT_PRIMARY, false);
        if (switchable) {
            int caretColor = contractMenuOpen ? BinancePalette.BRAND_YELLOW : BinancePalette.TEXT_SECONDARY;
            int caretCenter = PADDING + font.width(snapshot.symbol()) + CARET_GAP + CARET_HALF_WIDTH;
            for (int line = 0; line <= CARET_HALF_WIDTH; line++) {
                int half = CARET_HALF_WIDTH - line;
                int y = upperY + 2 + line;
                graphics.fill(caretCenter - half, y, caretCenter + half + 1, y + 1, caretColor);
            }
        }
        graphics.text(font, perpetual, PADDING, upperY + font.lineHeight + LINE_GAP, BinancePalette.TEXT_SECONDARY, false);
        for (Placed item : placed) {
            int rowTop = top + item.row() * TICKER_ROW_HEIGHT;
            int rowUpperY = rowTop + (TICKER_ROW_HEIGHT - font.lineHeight * 2 - LINE_GAP) / 2;
            if (item.x() > PADDING) {
                graphics.verticalLine(item.x() - STAT_GAP / 2, rowTop + 6, rowTop + TICKER_ROW_HEIGHT - 6, BinancePalette.DIVIDER);
            }
            graphics.text(font, item.stat().label(), item.x(), rowUpperY, BinancePalette.TEXT_SECONDARY, false);
            graphics.text(font, item.stat().value(), item.x(), rowUpperY + font.lineHeight + LINE_GAP,
                    item.stat().valueColor(), false);
        }
        graphics.horizontalLine(0, width, bottom, BinancePalette.DIVIDER);
        return bottom;
    }

    private static List<Stat> stats(MarketSnapshot snapshot) {
        List<Stat> stats = new ArrayList<>();
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.last_price"),
                MarketFormat.price(snapshot.lastPrice()), lastPriceColor()));
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.mark_price"),
                MarketFormat.price(snapshot.markPrice()), BinancePalette.TEXT_PRIMARY));
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.index_price"),
                MarketFormat.price(snapshot.indexPrice()), BinancePalette.TEXT_PRIMARY));
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.funding"),
                MarketFormat.fundingRate(snapshot.fundingRate()) + " / " + MarketFormat.countdown(snapshot.secondsUntilFunding()),
                BinancePalette.BRAND_YELLOW));
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.market_time"),
                MarketFormat.marketTime(snapshot.time()), BinancePalette.TEXT_PRIMARY));
        ClientMarket.stats24h().ifPresent(stats24h -> {
            double change = stats24h.close() - stats24h.open();
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.change_24h"),
                    MarketFormat.signedPrice(change) + " " + MarketFormat.signedPercent(stats24h.changeRatio()),
                    change > 0 ? BinancePalette.UP : change < 0 ? BinancePalette.DOWN : BinancePalette.TEXT_PRIMARY));
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.high_24h"),
                    MarketFormat.price(stats24h.high()), BinancePalette.TEXT_PRIMARY));
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.low_24h"),
                    MarketFormat.price(stats24h.low()), BinancePalette.TEXT_PRIMARY));
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.volume_24h"),
                    MarketFormat.volume(stats24h.volume()), BinancePalette.TEXT_PRIMARY));
        });
        return stats;
    }

    /** 周期切换栏：当前周期黄字加下划线，鼠标悬停的周期底色变亮。 */
    private void extractTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = PADDING;
        for (CandlePeriod period : CandlePeriod.values()) {
            int tabWidth = tabWidth(period);
            boolean selected = period == ClientKlines.selected();
            boolean hovered = mouseX >= x && mouseX < x + tabWidth && mouseY >= tabsTop && mouseY < tabsTop + TAB_HEIGHT;
            if (hovered) {
                graphics.fill(x, tabsTop, x + tabWidth, tabsTop + TAB_HEIGHT, BinancePalette.PANEL);
            }
            graphics.text(font, period.label(), x + TAB_PADDING, centeredTextY(tabsTop, tabsTop + TAB_HEIGHT),
                    selected ? BinancePalette.BRAND_YELLOW : hovered ? BinancePalette.TEXT_PRIMARY : BinancePalette.TEXT_SECONDARY,
                    false);
            if (selected) {
                graphics.horizontalLine(x + TAB_PADDING, x + tabWidth - TAB_PADDING - 1, tabsTop + TAB_HEIGHT - 1,
                        BinancePalette.BRAND_YELLOW);
            }
            x += tabWidth;
        }
    }

    private int tabWidth(CandlePeriod period) {
        return font.width(period.label()) + TAB_PADDING * 2;
    }

    private void extractChart(GuiGraphicsExtractor graphics, int top, int mouseX, int mouseY) {
        int bottom = height - PADDING;
        List<Candle> candles = ClientKlines.candles();
        if (candles == null || candles.isEmpty()) {
            graphics.centeredText(font, Component.translatable("screen.creepercapital.trading.kline.loading"),
                    width / 2, centeredTextY(top, bottom), BinancePalette.TEXT_SECONDARY);
            return;
        }
        chart.extract(graphics, PADDING, top, width - PADDING, bottom, ClientKlines.selected(), candles, mouseX, mouseY);
    }

    private void extractWaiting(GuiGraphicsExtractor graphics) {
        graphics.centeredText(font, Component.translatable("screen.creepercapital.trading.waiting"),
                width / 2, centeredTextY(HEADER_HEIGHT, height), BinancePalette.TEXT_SECONDARY);
    }

    private static int lastPriceColor() {
        return switch (ClientMarket.lastPriceDirection()) {
            case UP -> BinancePalette.UP;
            case DOWN -> BinancePalette.DOWN;
            case FLAT -> BinancePalette.TEXT_PRIMARY;
        };
    }

    private int centeredTextY(int top, int bottom) {
        return top + (bottom - top - font.lineHeight) / 2 + 1;
    }
}
