package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.MarketTime;
import io.github.djp440.creepercapital.core.trading.AdlRanking;
import io.github.djp440.creepercapital.core.trading.FundingRecord;
import io.github.djp440.creepercapital.core.trading.MarginMath;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.CancelReason;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderStatus;
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
 * 底部表格区里的「仓位」「当前委托」「历史委托」「成交历史」页。
 * <p>
 * 仓位页每个行情步按标记价格重算每个仓位的未实现盈亏和收益率（收益率 = 盈亏 / 保证金）、保证金率和预估强平价，
 * 每行末尾有「市价平仓」按钮；
 * 成交历史页列出最近的玩家成交（最新的在前），含手续费和已实现盈亏。
 * 当前委托页列出挂着的限价委托，每行末尾有「撤单」按钮；历史委托页列出已成交和已撤销的委托（最新的在前）。
 * 资金费记录页列出每一次资金费结算（最新的在前），金额按收付着色。
 */
final class TradeTables {
    static final int ROW_HEIGHT = 12;
    static final int HEADER_HEIGHT = 12;
    private static final int[] POSITION_WEIGHTS = {21, 7, 8, 8, 9, 8, 13, 22, 7, 9};
    private static final int LIGHT_WIDTH = 3;
    private static final int[] TRADE_WEIGHTS = {19, 14, 9, 13, 11, 12, 16};
    private static final int[] FUNDING_WEIGHTS = {16, 14, 8, 12, 12, 15, 14, 9};
    private static final int[] OPEN_ORDER_WEIGHTS = {14, 13, 8, 13, 13, 11, 12, 8};
    private static final int[] ORDER_HISTORY_WEIGHTS = {15, 13, 8, 12, 10, 10, 9, 9, 13};
    private static final int BUTTON_BACKGROUND = 0xFF2B3139;
    private static final int BUTTON_HOVER = 0xFF3C4552;

    private final Font font;
    /** 每帧渲染时记下各行的「市价平仓」按钮：{左, 上, 右, 下}，与 {@link #closeSymbols} 一一对应。 */
    private final List<int[]> closeBoxes = new ArrayList<>();
    private final List<String> closeSymbols = new ArrayList<>();
    /** 每帧渲染时记下当前委托页各行的「撤单」按钮，与 {@link #cancelIds} 一一对应。 */
    private final List<int[]> cancelBoxes = new ArrayList<>();
    private final List<Long> cancelIds = new ArrayList<>();

    TradeTables(Font font) {
        this.font = font;
    }

    /** 表格区一页最多能放几行数据。 */
    static int visibleRows(int tableHeight, int tabHeight) {
        return Math.max(1, (tableHeight - tabHeight - HEADER_HEIGHT - 4) / ROW_HEIGHT);
    }

    void extractPositions(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        forgetButtons();
        int[] columns = columns(left, right, POSITION_WEIGHTS);
        header(graphics, top, columns, "symbol", "size", "entry_price", "mark_price", "liq_price", "margin_ratio", "margin",
                "pnl", "adl", "action");
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
            lights(graphics, columns[8], y, ClientTrading.adlLights(position.symbol()));
            Component label = Component.translatable("screen.creepercapital.trading.positions.close");
            int[] box = {columns[9], y, columns[9] + font.width(label) + 8, y + ROW_HEIGHT - 1};
            boolean hovered = mouseX >= box[0] && mouseX < box[2] && mouseY >= box[1] && mouseY < box[3];
            graphics.fill(box[0], box[1], box[2], box[3], hovered ? BUTTON_HOVER : BUTTON_BACKGROUND);
            graphics.text(font, label, box[0] + 4, y + 2, BinancePalette.TEXT_PRIMARY, false);
            closeBoxes.add(box);
            closeSymbols.add(position.symbol());
        }
    }

    /**
     * ADL 指示灯（#17）：五根逐渐增高的小柱，亮的用品牌黄、没亮的用分隔线灰；亮得越多越先被自动减仓。
     * 格数由服务端按全服排名算好同步过来，这里只画。
     */
    private static void lights(GuiGraphicsExtractor graphics, int x, int rowTop, int lit) {
        int bottom = rowTop + ROW_HEIGHT - 1;
        for (int i = 0; i < AdlRanking.LIGHTS; i++) {
            int left = x + i * (LIGHT_WIDTH + 1);
            graphics.fill(left, bottom - (2 + 2 * i), left + LIGHT_WIDTH, bottom,
                    i < lit ? BinancePalette.BRAND_YELLOW : BinancePalette.DIVIDER);
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
            // 强平和自动减仓用品牌黄色，一眼和主动平仓区分开
            int sideColor = trade.kind() == TradeKind.LIQUIDATION || trade.kind() == TradeKind.ADL ? BinancePalette.BRAND_YELLOW
                    : opensLong ? BinancePalette.UP : BinancePalette.DOWN;
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

    /** 资金费记录页：每一次结算一行（最新在前）；金额站在玩家一侧，收到涨色、支付跌色。 */
    void extractFunding(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        int[] columns = columns(left, right, FUNDING_WEIGHTS);
        header(graphics, top, columns, "time", "symbol", "side", "funding_rate", "mark_price", "notional",
                "funding_amount", "funding_short");
        List<FundingRecord> records = ClientTrading.fundingNewestFirst();
        if (records.isEmpty()) {
            empty(graphics, left, right, top + HEADER_HEIGHT, bottom, "screen.creepercapital.trading.funding.empty");
            return;
        }
        int rows = Math.max(1, (bottom - top - HEADER_HEIGHT) / ROW_HEIGHT);
        for (int i = 0; i < Math.min(rows, records.size()); i++) {
            FundingRecord record = records.get(i);
            int y = top + HEADER_HEIGHT + i * ROW_HEIGHT;
            int sideColor = record.side() == Side.LONG ? BinancePalette.UP : BinancePalette.DOWN;
            int amountColor = record.amount().units() > 0 ? BinancePalette.UP
                    : record.amount().units() < 0 ? BinancePalette.DOWN : BinancePalette.TEXT_PRIMARY;
            cell(graphics, columns[0], y, MarketFormat.marketTime(new MarketTime(record.marketSeconds())), BinancePalette.TEXT_SECONDARY);
            cell(graphics, columns[1], y, record.symbol(), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[2], y, Component.translatable("screen.creepercapital.trading.side."
                    + record.side().name().toLowerCase(Locale.ROOT)).getString(), sideColor);
            cell(graphics, columns[3], y, MarketFormat.fundingRate(record.rate()), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[4], y, MarketFormat.price(record.markPrice()), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[5], y, record.notional().toDisplayString(), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[6], y, signedMoney(record.amount()), amountColor);
            cell(graphics, columns[7], y, record.shortfall().isPositive() ? record.shortfall().toDisplayString() : "--",
                    record.shortfall().isPositive() ? BinancePalette.BRAND_YELLOW : BinancePalette.TEXT_SECONDARY);
        }
    }

    void extractOpenOrders(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        forgetButtons();
        int[] columns = columns(left, right, OPEN_ORDER_WEIGHTS);
        header(graphics, top, columns, "time", "symbol", "side", "type", "order_price", "quantity", "frozen", "action");
        List<Order> orders = ClientTrading.openOrdersNewestFirst();
        if (orders.isEmpty()) {
            empty(graphics, left, right, top + HEADER_HEIGHT, bottom, "screen.creepercapital.trading.orders.open_empty");
            return;
        }
        int rows = Math.max(1, (bottom - top - HEADER_HEIGHT) / ROW_HEIGHT);
        for (int i = 0; i < Math.min(rows, orders.size()); i++) {
            Order order = orders.get(i);
            int y = top + HEADER_HEIGHT + i * ROW_HEIGHT;
            orderCells(graphics, columns, y, order, order.createdSeconds());
            cell(graphics, columns[4], y, MarketFormat.price(order.price()), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[5], y, Quantities.format(order.lots(), decimalsOf(order)), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[6], y, order.frozen().isPositive() ? order.frozen().toDisplayString() : "--",
                    BinancePalette.TEXT_PRIMARY);
            Component label = Component.translatable("screen.creepercapital.trading.orders.cancel");
            int[] box = {columns[7], y, columns[7] + font.width(label) + 8, y + ROW_HEIGHT - 1};
            boolean hovered = mouseX >= box[0] && mouseX < box[2] && mouseY >= box[1] && mouseY < box[3];
            graphics.fill(box[0], box[1], box[2], box[3], hovered ? BUTTON_HOVER : BUTTON_BACKGROUND);
            graphics.text(font, label, box[0] + 4, y + 2, BinancePalette.TEXT_PRIMARY, false);
            cancelBoxes.add(box);
            cancelIds.add(order.id());
        }
    }

    void extractOrderHistory(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        int[] columns = columns(left, right, ORDER_HISTORY_WEIGHTS);
        header(graphics, top, columns, "time", "symbol", "side", "type", "order_price", "fill_price", "quantity", "fee", "status");
        List<Order> orders = ClientTrading.orderHistoryNewestFirst();
        if (orders.isEmpty()) {
            empty(graphics, left, right, top + HEADER_HEIGHT, bottom, "screen.creepercapital.trading.orders.history_empty");
            return;
        }
        int rows = Math.max(1, (bottom - top - HEADER_HEIGHT) / ROW_HEIGHT);
        for (int i = 0; i < Math.min(rows, orders.size()); i++) {
            Order order = orders.get(i);
            int y = top + HEADER_HEIGHT + i * ROW_HEIGHT;
            boolean filled = order.status() == OrderStatus.FILLED;
            orderCells(graphics, columns, y, order, order.closedSeconds());
            cell(graphics, columns[4], y, MarketFormat.price(order.price()), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[5], y, filled ? MarketFormat.price(order.fillPrice()) : "--", BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[6], y, Quantities.format(order.lots(), decimalsOf(order)), BinancePalette.TEXT_PRIMARY);
            cell(graphics, columns[7], y, filled ? order.fee().toDisplayString() : "--", BinancePalette.TEXT_PRIMARY);
            String statusKey = filled ? "filled" : order.cancelReason() == CancelReason.FILL_REJECTED ? "fill_rejected"
                    : order.cancelReason() == CancelReason.POSITION_CLOSED ? "position_closed"
                    : order.cancelReason() == CancelReason.LIQUIDATED ? "liquidated" : "cancelled";
            cell(graphics, columns[8], y, Component.translatable("screen.creepercapital.trading.order_status." + statusKey).getString(),
                    filled ? BinancePalette.UP : BinancePalette.TEXT_SECONDARY);
        }
    }

    /**
     * 委托行前四列：时间、合约、方向、类型。开仓委托的方向是开多绿 / 开空红；平仓类委托（只减仓、止盈、止损）是平多 / 平空，
     * 颜色跟随所平仓位的方向。类型列：限价、限价只减仓、止盈 / 止损带触发价类型（最新价 / 标记价）。
     */
    private void orderCells(GuiGraphicsExtractor graphics, int[] columns, int y, Order order, long marketSeconds) {
        cell(graphics, columns[0], y, MarketFormat.marketTime(new MarketTime(marketSeconds)), BinancePalette.TEXT_SECONDARY);
        cell(graphics, columns[1], y, order.symbol(), BinancePalette.TEXT_PRIMARY);
        String kind = order.reduceOnly() ? "close_" : "open_";
        cell(graphics, columns[2], y, Component.translatable("screen.creepercapital.trading.trade_side." + kind
                + order.side().name().toLowerCase(Locale.ROOT)).getString(),
                order.side() == Side.LONG ? BinancePalette.UP : BinancePalette.DOWN);
        cell(graphics, columns[3], y, typeLabel(order), BinancePalette.TEXT_PRIMARY);
    }

    static String typeLabel(Order order) {
        if (order.type().isTrigger()) {
            return Component.translatable("screen.creepercapital.trading.order_type." + order.type().name().toLowerCase(Locale.ROOT)
                    + "_" + order.triggerSource().name().toLowerCase(Locale.ROOT)).getString();
        }
        return Component.translatable("screen.creepercapital.trading.order_type."
                + (order.reduceOnly() ? "limit_reduce" : "limit")).getString();
    }

    private static int decimalsOf(Order order) {
        return ClientTrading.params(order.symbol()).map(p -> p.quantityDecimals()).orElse(0);
    }

    /** 点中某行的「市价平仓」就全平这个仓位，点中「撤单」就撤销这张委托；返回点击是否被处理。 */
    boolean mouseClicked(double x, double y) {
        for (int i = 0; i < cancelBoxes.size(); i++) {
            int[] box = cancelBoxes.get(i);
            if (x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) {
                ClientTrading.cancel(cancelIds.get(i));
                return true;
            }
        }
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

    /** 点击不在带按钮的页时清掉上一帧记下的按钮区域。 */
    void forgetButtons() {
        closeBoxes.clear();
        closeSymbols.clear();
        cancelBoxes.clear();
        cancelIds.clear();
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
