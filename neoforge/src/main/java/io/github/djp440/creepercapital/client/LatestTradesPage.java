package io.github.djp440.creepercapital.client;

import java.util.List;
import java.util.Locale;

import io.github.djp440.creepercapital.core.market.MarketTime;
import io.github.djp440.creepercapital.core.market.MarketTrade;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.Quantities;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 最新成交页（币安样式）：每行三列，价格（买入绿、卖出红）、数量、市场时间，最新的在最上面。
 * 玩家成交整行加黄色底色和左侧色条，并多占一行标出玩家名（全服可见）；模拟成交只是一行普通文字。
 * <p>
 * 只保留最近 {@link ClientLatestTrades#KEEP} 笔，新成交从顶部进来、旧的往下推；鼠标滚轮可以往回翻。
 */
final class LatestTradesPage {
    private static final int PAD = 6;
    private static final int ROW = 10;
    private static final int HEADER_HEIGHT = 12;
    private static final int PLAYER_ALPHA = 0x30;
    private static final int PLAYER_BAR_WIDTH = 2;

    private final Font font;
    /** 从最新一笔起跳过多少笔（滚轮往回翻）；每帧渲染时按实际笔数收敛。 */
    private int scroll;

    LatestTradesPage(Font font) {
        this.font = font;
    }

    void extract(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, String symbol) {
        int decimals = ClientTrading.params(symbol).map(TradingParams::quantityDecimals).orElse(0);
        int innerLeft = left + PAD;
        int innerRight = right - PAD;
        int timeRight = innerRight;
        int timeWidth = font.width("00:00:00");
        int quantityRight = timeRight - timeWidth - 6;

        int y = top + 2;
        graphics.text(font, Component.translatable("screen.creepercapital.trading.latest.price"), innerLeft, y,
                BinancePalette.TEXT_SECONDARY, false);
        drawRight(graphics, Component.translatable("screen.creepercapital.trading.latest.quantity"), quantityRight, y,
                BinancePalette.TEXT_SECONDARY);
        drawRight(graphics, Component.translatable("screen.creepercapital.trading.latest.time"), timeRight, y,
                BinancePalette.TEXT_SECONDARY);
        y += HEADER_HEIGHT;

        List<MarketTrade> trades = ClientLatestTrades.newestFirst(symbol);
        if (trades.isEmpty()) {
            graphics.centeredText(font, Component.translatable("screen.creepercapital.trading.latest.empty"), (left + right) / 2,
                    y + 8, BinancePalette.TEXT_SECONDARY);
            return;
        }
        scroll = Math.max(0, Math.min(scroll, trades.size() - 1));
        for (int i = scroll; i < trades.size(); i++) {
            MarketTrade trade = trades.get(i);
            int height = trade.isPlayer() ? ROW * 2 : ROW;
            if (y + height > bottom - 1) {
                break;
            }
            int textY = y + (ROW - font.lineHeight) / 2 + 1;
            if (trade.isPlayer()) {
                graphics.fill(left + 1, y, right - 1, y + height, (BinancePalette.BRAND_YELLOW & 0x00FFFFFF) | (PLAYER_ALPHA << 24));
                graphics.fill(left + 1, y, left + 1 + PLAYER_BAR_WIDTH, y + height, BinancePalette.BRAND_YELLOW);
            }
            graphics.text(font, MarketFormat.price(trade.price()), innerLeft, textY,
                    trade.buy() ? BinancePalette.UP : BinancePalette.DOWN, false);
            drawRight(graphics, Quantities.format(trade.lots(), decimals), quantityRight, textY, BinancePalette.TEXT_PRIMARY);
            drawRight(graphics, time(trade), timeRight, textY, BinancePalette.TEXT_SECONDARY);
            if (trade.isPlayer()) {
                graphics.text(font, Component.translatable("screen.creepercapital.trading.latest.player", trade.player()), innerLeft,
                        textY + ROW, BinancePalette.BRAND_YELLOW, false);
            }
            y += height;
        }
    }

    /** 滚轮：向下滚翻到更旧的成交，向上滚回到最新（最新的在列表顶部）。 */
    void mouseScrolled(double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 2);
    }

    private static String time(MarketTrade trade) {
        MarketTime time = new MarketTime(trade.marketSeconds());
        return String.format(Locale.ROOT, "%02d:%02d:%02d", time.hour(), time.minute(), time.second());
    }

    private void drawRight(GuiGraphicsExtractor graphics, String text, int right, int y, int color) {
        graphics.text(font, text, right - font.width(text), y, color, false);
    }

    private void drawRight(GuiGraphicsExtractor graphics, Component text, int right, int y, int color) {
        graphics.text(font, text, right - font.width(text), y, color, false);
    }
}
