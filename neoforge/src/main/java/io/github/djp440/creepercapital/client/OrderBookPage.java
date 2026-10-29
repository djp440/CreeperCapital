package io.github.djp440.creepercapital.client;

import java.util.List;

import io.github.djp440.creepercapital.core.market.LiquidityCurve;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.Quantities;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 盘口页（币安样式）：卖盘在上（跌色）、买盘在下（涨色），中间是最新价格（右侧灰字是标记价格）；每档三列，价格、数量、累计数量，
 * 背景有按累计数量画的深度条。档数随可用高度增减（最多 {@link #MAX_LEVELS} 档）。
 * <p>
 * 盘口就是虚拟流动性曲线（{@link LiquidityCurve}）的前若干档：用同步下来的合约深度参数和当前最新价格在本地算，
 * 与服务端市价成交走的是同一份代码，所以按这里的档位逐档吃单算出的均价就是实际成交均价。
 */
final class OrderBookPage {
    private static final int PAD = 6;
    private static final int ROW = 10;
    private static final int HEADER_HEIGHT = 12;
    private static final int LAST_PRICE_HEIGHT = 16;
    private static final int MAX_LEVELS = 20;
    /** 深度条的透明度（0x00 到 0xFF）。 */
    private static final int BAR_ALPHA = 0x30;

    private final Font font;

    OrderBookPage(Font font) {
        this.font = font;
    }

    void extract(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, String symbol, MarketSnapshot snapshot) {
        TradingParams params = ClientTrading.params(symbol).orElse(null);
        if (params == null) {
            graphics.centeredText(font, "--", (left + right) / 2, top + 20, BinancePalette.TEXT_SECONDARY);
            return;
        }
        int levels = Math.max(1, Math.min(MAX_LEVELS, (bottom - top - HEADER_HEIGHT - LAST_PRICE_HEIGHT - 2) / (ROW * 2)));
        LiquidityCurve curve = params.liquidityCurve(snapshot.lastPrice());
        List<LiquidityCurve.Level> asks = curve.levels(true, levels);
        List<LiquidityCurve.Level> bids = curve.levels(false, levels);
        int decimals = params.quantityDecimals();
        long totalLots = curve.levelLots() * levels;
        String widestTotal = Quantities.format(totalLots, decimals);

        int innerLeft = left + PAD;
        int innerRight = right - PAD;
        int totalRight = innerRight;
        Component totalHeader = Component.translatable("screen.creepercapital.trading.book.total");
        int quantityRight = totalRight - Math.max(font.width(widestTotal), font.width(totalHeader)) - 8;

        int y = top + 2;
        Component price = Component.translatable("screen.creepercapital.trading.book.price");
        graphics.text(font, price, innerLeft, y, BinancePalette.TEXT_SECONDARY, false);
        drawRight(graphics, Component.translatable("screen.creepercapital.trading.book.quantity"), quantityRight, y,
                BinancePalette.TEXT_SECONDARY);
        drawRight(graphics, totalHeader, totalRight, y, BinancePalette.TEXT_SECONDARY);
        y += HEADER_HEIGHT;

        // 卖盘：离最新价格最近的一档（卖一）在最下面，所以从最远的一档往下画
        for (int i = levels - 1; i >= 0; i--) {
            extractLevel(graphics, asks.get(i), (i + 1) * curve.levelLots(), totalLots, decimals, innerLeft, innerRight, quantityRight,
                    y, BinancePalette.down());
            y += ROW;
        }

        // 中间是最新价格，颜色跟随相对上一个行情步的涨跌
        graphics.horizontalLine(left, right - 1, y, BinancePalette.DIVIDER);
        int midY = GuiText.centeredY(font, y, y + LAST_PRICE_HEIGHT);
        graphics.text(font, MarketFormat.price(snapshot.lastPrice()), innerLeft, midY,
                BinancePalette.direction(ClientMarket.lastPriceDirection()), false);
        drawRight(graphics, MarketFormat.price(snapshot.markPrice()), innerRight, midY, BinancePalette.TEXT_SECONDARY);
        graphics.horizontalLine(left, right - 1, y + LAST_PRICE_HEIGHT, BinancePalette.DIVIDER);
        y += LAST_PRICE_HEIGHT + 1;

        // 买盘：买一在最上面
        for (int i = 0; i < levels; i++) {
            extractLevel(graphics, bids.get(i), (i + 1) * curve.levelLots(), totalLots, decimals, innerLeft, innerRight, quantityRight,
                    y, BinancePalette.up());
            y += ROW;
        }
    }

    private void extractLevel(GuiGraphicsExtractor graphics, LiquidityCurve.Level level, long cumulativeLots, long totalLots,
            int decimals, int innerLeft, int innerRight, int quantityRight, int y, int color) {
        int barWidth = (int) Math.round((innerRight - innerLeft) * (double) cumulativeLots / totalLots);
        graphics.fill(innerRight - barWidth, y, innerRight + PAD, y + ROW, BinancePalette.withAlpha(color, BAR_ALPHA));
        int textY = y + (ROW - font.lineHeight) / 2 + 1;
        graphics.text(font, MarketFormat.price(level.price()), innerLeft, textY, color, false);
        drawRight(graphics, Quantities.format(level.lots(), decimals), quantityRight, textY, BinancePalette.TEXT_PRIMARY);
        drawRight(graphics, Quantities.format(cumulativeLots, decimals), innerRight, textY, BinancePalette.TEXT_PRIMARY);
    }

    private void drawRight(GuiGraphicsExtractor graphics, String text, int right, int y, int color) {
        graphics.text(font, text, right - font.width(text), y, color, false);
    }

    private void drawRight(GuiGraphicsExtractor graphics, Component text, int right, int y, int color) {
        graphics.text(font, text, right - font.width(text), y, color, false);
    }
}
