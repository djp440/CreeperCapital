package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
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
 * 最新成交页（币安样式）：每行三列，价格（买入涨色、卖出跌色）、数量、市场时间，最新的在最上面。
 * 玩家成交整行加黄色底色和左侧色条，并多占一行标出玩家名（全服可见）；模拟成交只是一行普通文字。
 * <p>
 * 只保留最近 {@link ClientLatestTrades#KEEP} 笔，新成交从顶部进来、旧的往下推；鼠标滚轮可以往回翻。
 * 刚成交的玩家成交先置顶在表头下方（{@link PlayerTradePins}，最多占页面一半高度），到期后回到下面的列表里，
 * 不然模拟成交每个行情步来好几笔，玩家成交两三秒就被挤出可见区域。
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
        // 置顶区：只放得下页面一半高度以内的几笔，下面画一条分隔线
        List<MarketTrade> pinned = new ArrayList<>();
        int pinLimit = y + (bottom - y) / 2;
        for (MarketTrade trade : ClientLatestTrades.pinned(symbol)) {
            if (y + ROW * 2 > pinLimit) {
                break;
            }
            extractTrade(graphics, trade, left, right, innerLeft, quantityRight, timeRight, y, decimals);
            pinned.add(trade);
            y += ROW * 2;
        }
        if (!pinned.isEmpty()) {
            graphics.horizontalLine(left, right - 1, y, BinancePalette.DIVIDER);
            y += 2;
        }
        List<MarketTrade> rest = new ArrayList<>(trades.size());
        for (MarketTrade trade : trades) {
            if (pinned.stream().noneMatch(pin -> pin == trade)) {
                rest.add(trade);
            }
        }
        scroll = Math.max(0, Math.min(scroll, rest.size() - 1));
        for (int i = scroll; i < rest.size(); i++) {
            MarketTrade trade = rest.get(i);
            int height = trade.isPlayer() ? ROW * 2 : ROW;
            if (y + height > bottom - 1) {
                break;
            }
            extractTrade(graphics, trade, left, right, innerLeft, quantityRight, timeRight, y, decimals);
            y += height;
        }
    }

    /** 一笔成交：价格、数量、时间；玩家成交加黄色底色和左侧色条，第二行是玩家名。 */
    private void extractTrade(GuiGraphicsExtractor graphics, MarketTrade trade, int left, int right, int innerLeft, int quantityRight,
            int timeRight, int y, int decimals) {
        int height = trade.isPlayer() ? ROW * 2 : ROW;
        int textY = y + (ROW - font.lineHeight) / 2 + 1;
        if (trade.isPlayer()) {
            graphics.fill(left, y, right, y + height, BinancePalette.withAlpha(BinancePalette.BRAND_YELLOW, PLAYER_ALPHA));
            graphics.fill(left, y, left + PLAYER_BAR_WIDTH, y + height, BinancePalette.BRAND_YELLOW);
        }
        graphics.text(font, MarketFormat.price(trade.price()), innerLeft, textY, BinancePalette.side(trade.buy()), false);
        drawRight(graphics, Quantities.format(trade.lots(), decimals), quantityRight, textY, BinancePalette.TEXT_PRIMARY);
        drawRight(graphics, time(trade), timeRight, textY, BinancePalette.TEXT_SECONDARY);
        if (trade.isPlayer()) {
            String name = Component.translatable("screen.creepercapital.trading.latest.player", trade.player()).getString();
            graphics.text(font, GuiText.fit(font, name, right - innerLeft - 4), innerLeft, textY + ROW, BinancePalette.BRAND_YELLOW, false);
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
