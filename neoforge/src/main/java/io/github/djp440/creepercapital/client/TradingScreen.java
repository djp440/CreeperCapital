package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;

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
 * 行情栏依次是最新价格、标记价格、指数价格、资金费率与结算倒计时、市场时间，以及 24h 涨跌、最高、最低、成交量；
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

    private final KlineChart chart;
    /** 周期切换栏的上沿；每帧渲染时更新，点击判定要用。 */
    private int tabsTop;

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
        if (CreeperCapitalClient.OPEN_TRADING_KEY.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
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
        int tickerBottom = extractTicker(graphics, snapshot);
        tabsTop = tickerBottom + 5;
        extractTabs(graphics, mouseX, mouseY);
        extractChart(graphics, tabsTop + TAB_HEIGHT + 4, mouseX, mouseY);
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
    private int extractTicker(GuiGraphicsExtractor graphics, MarketSnapshot snapshot) {
        int top = HEADER_HEIGHT + 1;
        Component perpetual = Component.translatable("screen.creepercapital.trading.perpetual");
        int symbolWidth = Math.max(font.width(snapshot.symbol()), font.width(perpetual));

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
        graphics.text(font, snapshot.symbol(), PADDING, upperY, BinancePalette.TEXT_PRIMARY, false);
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
