package io.github.djp440.creepercapital.client;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * 交易界面（币安深色主题）。行走骨架阶段只有顶部行情栏，显示 DIAMONDCRP 的指数价格。
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

    /** 顶部行情栏：交易代码 + 指数价格，排版仿币安的「名称在上、数值在下」。 */
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

        graphics.verticalLine(x - STAT_GAP / 2, top + 6, bottom - 6, BinancePalette.DIVIDER);
        graphics.text(font, Component.translatable("screen.creepercapital.trading.index_price"),
                x, upperY, BinancePalette.TEXT_SECONDARY, false);
        graphics.text(font, priceFormat.format(snapshot.indexPrice()), x, lowerY, indexPriceColor(), false);

        graphics.horizontalLine(0, width, bottom, BinancePalette.DIVIDER);
    }

    private void extractWaiting(GuiGraphicsExtractor graphics) {
        graphics.centeredText(font, Component.translatable("screen.creepercapital.trading.waiting"),
                width / 2, centeredTextY(HEADER_HEIGHT, height), BinancePalette.TEXT_SECONDARY);
    }

    private static int indexPriceColor() {
        return switch (ClientMarket.indexPriceDirection()) {
            case UP -> BinancePalette.UP;
            case DOWN -> BinancePalette.DOWN;
            case FLAT -> BinancePalette.TEXT_PRIMARY;
        };
    }

    private int centeredTextY(int top, int bottom) {
        return top + (bottom - top - font.lineHeight) / 2 + 1;
    }
}
