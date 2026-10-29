package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.MarketTime;
import io.github.djp440.creepercapital.core.trading.MarginMath;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Quantities;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.network.OrderPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 底部表格区里的「仓位」页和「成交历史」页。
 * <p>
 * 仓位页每个行情步按标记价格重算每个仓位的未实现盈亏和收益率（收益率 = 盈亏 / 保证金）、保证金率和预估强平价，
 * 每行末尾有「市价平仓」按钮；
 * 成交历史页列出最近的玩家成交（最新的在前），含手续费和已实现盈亏。
 */
final class TradeTables {
    static final int ROW_HEIGHT = 12;
    static final int HEADER_HEIGHT = 12;
    private static final int[] POSITION_WEIGHTS = {24, 8, 10, 10, 10, 8, 13, 21, 9};
    private static final int[] TRADE_WEIGHTS = {19, 14, 9, 13, 11, 12, 16};
    private static final int BUTTON_BACKGROUND = 0xFF2B3139;
    private static final int BUTTON_HOVER = 0xFF3C4552;

    private final Font font;
    /** 每帧渲染时记下各行的「市价平仓」按钮：{左, 上, 右, 下}，与 {@link #closeSymbols} 一一对应。 */
    private final List<int[]> closeBoxes = new ArrayList<>();
    private final List<String> closeSymbols = new ArrayList<>();

    TradeTables(Font font) {
        this.font = font;
    }

    /** 表格区一页最多能放几行数据。 */
    static int visibleRows(int tableHeight, int tabHeight) {
        return Math.max(1, (tableHeight - tabHeight - HEADER_HEIGHT - 4) / ROW_HEIGHT);
    }

    void extractPositions(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        closeBoxes.clear();
        closeSymbols.clear();
        int[] columns = columns(left, right, POSITION_WEIGHTS);
        header(graphics, top, columns, "symbol", "size", "entry_price", "mark_price", "liq_price", "margin_ratio", "margin",
                "pnl", "action");
        List<Position> positions = ClientTrading.positions();
        if (positions.isEmpty()) {
            empty(graphics, left, right, top + HEADER_HEIGHT, bottom, "screen.creepercapital.trading.positions.empty");
            return;
        }
        // 保证金率与预估强平价由 core 的 MarginMath 算（服务端强平用同一份），全仓仓位共用一个保证金率
        Map<String, MarginMath.PositionRisk> risks = ClientTrading.risks();
        int rows = Math.max(1, (bottom - top - HEADER_HEIGHT) / ROW_HEIGHT);
        for (int i = 0; i < Math.min(rows, positions.size()); i++) {
            Position position = positions.get(i);
            int y = top + HEADER_HEIGHT + i * ROW_HEIGHT;
            MarketSnapshot snapshot = ClientMarket.snapshot(position.symbol()).orElse(null);
            double mark = snapshot == null ? position.entryPrice() : snapshot.markPrice();
            Money pnl = position.pnlAt(mark);
            double roe = position.returnOnEquity(mark);
            int sideColor = position.side() == Side.LONG ? BinancePalette.UP : BinancePalette.DOWN;
            int pnlColor = pnl.units() > 0 ? BinancePalette.UP : pnl.units() < 0 ? BinancePalette.DOWN : BinancePalette.TEXT_PRIMARY;
            String mode = Component.translatable(position.mode() == MarginMode.CROSS
                    ? "screen.creepercapital.trading.order.cross" : "screen.creepercapital.trading.order.isolated").getString();
            MarginMath.PositionRisk risk = risks.get(position.symbol());
            double ratio = risk == null ? 0 : risk.marginRatio();
            int ratioColor = ratio >= 0.8 ? BinancePalette.DOWN : ratio >= 0.5 ? BinancePalette.BRAND_YELLOW : BinancePalette.TEXT_PRIMARY;
            String liquidation = risk == null || risk.liquidationPrice().isEmpty() ? "--"
                    : MarketFormat.price(risk.liquidationPrice().getAsDouble());
            cell(graphics, columns[0], y, position.symbol() + " " + mode + " " + position.leverage() + "x", sideColor);
            cell(graphics, columns[1], y, Quantities.format(position.lots(), position.quantityDecimals()), sideColor);
            cell(graphics, columns[2], y, MarketFormat.price(position.entryPrice()), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[3], y, MarketFormat.price(mark), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[4], y, liquidation, BinancePalette.BRAND_YELLOW);
            cell(graphics, columns[5], y, MarginMath.formatRatio(ratio), ratioColor);
            cell(graphics, columns[6], y, position.margin().toDisplayString(), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[7], y, signedMoney(pnl) + " (" + MarketFormat.signedPercent(roe) + ")", pnlColor);
            Component label = Component.translatable("screen.creepercapital.trading.positions.close");
            int[] box = {columns[8], y, columns[8] + font.width(label) + 8, y + ROW_HEIGHT - 1};
            boolean hovered = mouseX >= box[0] && mouseX < box[2] && mouseY >= box[1] && mouseY < box[3];
            graphics.fill(box[0], box[1], box[2], box[3], hovered ? BUTTON_HOVER : BUTTON_BACKGROUND);
            graphics.text(font, label, box[0] + 4, y + 2, BinancePalette.TEXT_PRIMARY, false);
            closeBoxes.add(box);
            closeSymbols.add(position.symbol());
        }
    }

    void extractTrades(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        int[] columns = columns(left, right, TRADE_WEIGHTS);
        header(graphics, top, columns, "time", "symbol", "side", "price", "quantity", "fee", "realized_pnl");
        List<PlayerTrade> trades = ClientTrading.tradesNewestFirst();
        if (trades.isEmpty()) {
            empty(graphics, left, right, top + HEADER_HEIGHT, bottom, "screen.creepercapital.trading.trades.empty");
            return;
        }
        int rows = Math.max(1, (bottom - top - HEADER_HEIGHT) / ROW_HEIGHT);
        for (int i = 0; i < Math.min(rows, trades.size()); i++) {
            PlayerTrade trade = trades.get(i);
            int y = top + HEADER_HEIGHT + i * ROW_HEIGHT;
            int decimals = ClientTrading.params(trade.symbol()).map(p -> p.quantityDecimals()).orElse(0);
            boolean opensLong = (trade.side() == Side.LONG) == (trade.kind() == TradeKind.OPEN);
            int sideColor = opensLong ? BinancePalette.UP : BinancePalette.DOWN;
            int pnlColor = trade.realizedPnl().units() > 0 ? BinancePalette.UP
                    : trade.realizedPnl().units() < 0 ? BinancePalette.DOWN : BinancePalette.TEXT_PRIMARY;
            cell(graphics, columns[0], y, MarketFormat.marketTime(new MarketTime(trade.marketSeconds())), BinancePalette.TEXT_SECONDARY);
            cell(graphics, columns[1], y, trade.symbol(), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[2], y, Component.translatable("screen.creepercapital.trading.trade_side."
                    + trade.kind().name().toLowerCase(Locale.ROOT) + "_" + trade.side().name().toLowerCase(Locale.ROOT)).getString(),
                    sideColor);
            cell(graphics, columns[3], y, MarketFormat.price(trade.price()), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[4], y, Quantities.format(trade.lots(), decimals), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[5], y, trade.fee().toDisplayString(), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[6], y, trade.kind() == TradeKind.OPEN ? "--" : signedMoney(trade.realizedPnl()), pnlColor);
        }
    }

    /** 点中某行的「市价平仓」就全平这个仓位；返回点击是否被处理。 */
    boolean mouseClicked(double x, double y) {
        for (int i = 0; i < closeBoxes.size(); i++) {
            int[] box = closeBoxes.get(i);
            if (x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) {
                String symbol = closeSymbols.get(i);
                ClientTrading.position(symbol)
                        .ifPresent(position -> ClientTrading.send(OrderPayload.Action.CLOSE, symbol, position.lots()));
                return true;
            }
        }
        return false;
    }

    /** 点击不在仓位页时清掉上一帧记下的按钮区域。 */
    void forgetButtons() {
        closeBoxes.clear();
        closeSymbols.clear();
    }

    private static int[] columns(int left, int right, int[] weights) {
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        int[] xs = new int[weights.length];
        int x = left;
        for (int i = 0; i < weights.length; i++) {
            xs[i] = x;
            x += (right - left) * weights[i] / total;
        }
        return xs;
    }

    private void header(GuiGraphicsExtractor graphics, int top, int[] columns, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            graphics.text(font, Component.translatable("screen.creepercapital.trading.column." + keys[i]), columns[i], top + 2,
                    BinancePalette.TEXT_SECONDARY, false);
        }
    }

    private void cell(GuiGraphicsExtractor graphics, int x, int y, String text, int color) {
        graphics.text(font, text, x, y + 2, color, false);
    }

    private void empty(GuiGraphicsExtractor graphics, int left, int right, int top, int bottom, String key) {
        graphics.centeredText(font, Component.translatable(key), (left + right) / 2,
                top + (bottom - top - font.lineHeight) / 2, BinancePalette.TEXT_SECONDARY);
    }

    /** 带正负号的金额，例如 {@code +199.00}。 */
    private static String signedMoney(Money money) {
        return (money.units() > 0 ? "+" : "") + money.toDisplayString();
    }
}
