package io.github.djp440.creepercapital.client;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.MarketTime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * 交易界面（币安深色主题）。目前只有顶部行情栏：最新价格、标记价格、指数价格、资金费率与结算倒计时、市场时间。
 * <p>
 * 界面每帧读取客户端缓存的行情，服务端每个行情步同步一次，所以价格每个行情步刷新一次。
 */
public final class TradingScreen extends Screen {
    private static final int HEADER_HEIGHT = 20;
    private static final int TICKER_HEIGHT = 32;
    private static final int PADDING = 12;
    private static final int STAT_GAP = 24;
    private static final int LINE_GAP = 3;

    private final DecimalFormat priceFormat = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
    /** 资金费率按币安的习惯显示为百分数、保留 4 位小数，例如 0.0100%。 */
    private final DecimalFormat fundingRateFormat = new DecimalFormat("0.0000%", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private TradingScreen() {
        super(Component.translatable("screen.creepercapital.trading.title"));
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setScreen(new TradingScreen());
        }
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
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BinancePalette.BACKGROUND);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        extractHeader(graphics);
        ClientMarket.latest().ifPresentOrElse(
                snapshot -> extractTicker(graphics, snapshot),
                () -> extractWaiting(graphics));
    }

    private void extractHeader(GuiGraphicsExtractor graphics) {
        graphics.fill(0, 0, width, HEADER_HEIGHT, BinancePalette.PANEL);
        graphics.text(font, title, PADDING, centeredTextY(0, HEADER_HEIGHT), BinancePalette.BRAND_YELLOW, false);
        graphics.horizontalLine(0, width, HEADER_HEIGHT, BinancePalette.DIVIDER);
    }

    /** 顶部行情栏：交易代码后依次是各项行情数据，排版仿币安的「名称在上、数值在下」。 */
    private void extractTicker(GuiGraphicsExtractor graphics, MarketSnapshot snapshot) {
        int top = HEADER_HEIGHT + 1;
        int bottom = top + TICKER_HEIGHT;
        int upperY = top + (TICKER_HEIGHT - font.lineHeight * 2 - LINE_GAP) / 2;
        int lowerY = upperY + font.lineHeight + LINE_GAP;
        graphics.fill(0, top, width, bottom, BinancePalette.PANEL);

        int x = PADDING;
        Component perpetual = Component.translatable("screen.creepercapital.trading.perpetual");
        graphics.text(font, snapshot.symbol(), x, upperY, BinancePalette.TEXT_PRIMARY, false);
        graphics.text(font, perpetual, x, lowerY, BinancePalette.TEXT_SECONDARY, false);
        x += Math.max(font.width(snapshot.symbol()), font.width(perpetual)) + STAT_GAP;

        x = extractStat(graphics, x, upperY, lowerY, top, bottom,
                Component.translatable("screen.creepercapital.trading.last_price"),
                priceFormat.format(snapshot.lastPrice()), lastPriceColor());
        x = extractStat(graphics, x, upperY, lowerY, top, bottom,
                Component.translatable("screen.creepercapital.trading.mark_price"),
                priceFormat.format(snapshot.markPrice()), BinancePalette.TEXT_PRIMARY);
        x = extractStat(graphics, x, upperY, lowerY, top, bottom,
                Component.translatable("screen.creepercapital.trading.index_price"),
                priceFormat.format(snapshot.indexPrice()), BinancePalette.TEXT_PRIMARY);
        x = extractStat(graphics, x, upperY, lowerY, top, bottom,
                Component.translatable("screen.creepercapital.trading.funding"),
                fundingRateFormat.format(snapshot.fundingRate()) + " / " + countdownText(snapshot.secondsUntilFunding()),
                BinancePalette.BRAND_YELLOW);
        extractStat(graphics, x, upperY, lowerY, top, bottom,
                Component.translatable("screen.creepercapital.trading.market_time"),
                marketTimeText(snapshot.time()), BinancePalette.TEXT_PRIMARY);

        graphics.horizontalLine(0, width, bottom, BinancePalette.DIVIDER);
    }

    /** 一栏「名称在上、数值在下」的数据，左侧画分隔线；返回下一栏的起点。 */
    private int extractStat(GuiGraphicsExtractor graphics, int x, int upperY, int lowerY, int top, int bottom,
            Component label, String value, int valueColor) {
        graphics.verticalLine(x - STAT_GAP / 2, top + 6, bottom - 6, BinancePalette.DIVIDER);
        graphics.text(font, label, x, upperY, BinancePalette.TEXT_SECONDARY, false);
        graphics.text(font, value, x, lowerY, valueColor, false);
        return x + Math.max(font.width(label), font.width(value)) + STAT_GAP;
    }

    /** 市场日历的日期和时分秒，例如「第 3 日 08:12:06」。 */
    private static String marketTimeText(MarketTime time) {
        return Component.translatable("screen.creepercapital.trading.market_time.value",
                time.day(), String.format(Locale.ROOT, "%02d:%02d:%02d", time.hour(), time.minute(), time.second()))
                .getString();
    }

    /** 距结算的倒计时，例如「07:59:54」。 */
    private static String countdownText(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60);
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
