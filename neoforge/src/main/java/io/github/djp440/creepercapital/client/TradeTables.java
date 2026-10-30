package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.client.sound.Cue;
import io.github.djp440.creepercapital.client.sound.CuePlayer;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.MarketTime;
import io.github.djp440.creepercapital.core.trading.AdlRanking;
import io.github.djp440.creepercapital.core.trading.CancelReason;
import io.github.djp440.creepercapital.core.trading.FundingRecord;
import io.github.djp440.creepercapital.core.trading.MarginMath;
import io.github.djp440.creepercapital.core.trading.MarginMode;
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
 * 底部表格区里的「仓位」「当前委托」「历史委托」「成交历史」「资金费记录」页。
 * <p>
 * 仓位页每个行情步按标记价格重算每个仓位的未实现盈亏和收益率（收益率 = 盈亏 / 保证金）、保证金率和预估强平价，
 * 每行末尾有「市价平仓」按钮；成交历史页列出最近的玩家成交（最新的在前），含手续费和已实现盈亏。
 * 当前委托页列出挂着的委托，每行末尾有「撤单」按钮；历史委托页列出已成交和已撤销的委托（最新的在前）。
 * 资金费记录页列出每一次资金费结算（最新的在前），金额按收付着色。
 * <p>
 * 列宽按表头与内容实际需要的宽度排（{@link LayoutFit#columns}）：放不下时先隐藏优先级低的列，必须显示的列只会缩窄，
 * 文字超出列宽时截断加省略号，所以各列永远不会互相压住。数据多于可见行数时可以用滚轮翻。
 */
final class TradeTables {
    static final int ROW_HEIGHT = 12;
    static final int HEADER_HEIGHT = 12;
    private static final int COLUMN_GAP = 8;
    private static final int LIGHT_WIDTH = 3;
    private static final int LIGHTS_WIDTH = AdlRanking.LIGHTS * (LIGHT_WIDTH + 1) - 1;
    private static final int BUTTON_PADDING = 4;
    private static final int R = LayoutFit.REQUIRED;
    /*
     * 各页的列优先级（与表头顺序一一对应）：数值越大越晚被隐藏，R 为必须显示。
     * 仓位：合约 模式 数量 开仓均价 标记价格 强平价 保证金率 保证金 盈亏 收益率 ADL 操作
     */
    private static final int[] POSITION_PRIORITY = {R, 2, R, 7, 4, 8, 9, 3, R, 6, 5, 10};
    /** 成交历史：时间 合约 方向 成交价 数量 手续费 已实现盈亏。 */
    private static final int[] TRADE_PRIORITY = {4, R, R, R, 6, 3, 7};
    /** 资金费：时间 合约 方向 资金费率 标记价格 名义价值 资金费 未收付。 */
    private static final int[] FUNDING_PRIORITY = {5, R, 6, 4, 2, 3, R, 1};
    /** 当前委托：时间 合约 方向 类型 委托价 数量 冻结 操作。 */
    private static final int[] OPEN_ORDER_PRIORITY = {3, R, R, 5, R, 6, 2, 9};
    /** 历史委托：时间 合约 方向 类型 委托价 成交价 数量 手续费 状态。 */
    private static final int[] ORDER_HISTORY_PRIORITY = {4, R, R, 5, 6, 7, 3, 2, R};

    private final Font font;
    /** 每帧渲染时记下各行按钮（「市价平仓」「撤单」）的区域与点击动作。 */
    private final List<Button> buttons = new ArrayList<>();
    /** 从第几行开始显示（滚轮往下翻）；换页时归零，每帧按实际行数收敛。 */
    private int scroll;

    private record Button(int left, int top, int right, int bottom, Runnable action) {
        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    /** 一个单元格：文字（带颜色）、ADL 指示灯（{@code lit >= 0}）或按钮（{@code action} 非空）。 */
    private record Cell(String text, int color, int lit, @Nullable Runnable action) {
        static Cell text(String text, int color) {
            return new Cell(text, color, -1, null);
        }

        static Cell lights(int lit) {
            return new Cell("", 0, lit, null);
        }

        static Cell button(String label, Runnable action) {
            return new Cell(label, TradingPalette.TEXT_PRIMARY, -1, action);
        }
    }

    /** 一页表格的内容：先收齐各行单元格，量好各列需要的宽度再排列。 */
    private static final class Grid {
        private final int left;
        private final int right;
        private final int[] priority;
        private final List<Cell[]> rows = new ArrayList<>();

        Grid(int left, int right, int[] priority) {
            this.left = left;
            this.right = right;
            this.priority = priority;
        }

        void add(Cell... cells) {
            rows.add(cells);
        }
    }

    TradeTables(Font font) {
        this.font = font;
    }

    /** 换页时调用：滚动位置归零、清掉上一页的按钮。 */
    void reset() {
        scroll = 0;
        forgetButtons();
    }

    /** 滚轮：向下翻到更早的数据。 */
    void mouseScrolled(double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
    }

    void extractPositions(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        forgetButtons();
        Grid columns = new Grid(left, right, POSITION_PRIORITY);
        // 保证金率与预估强平价由 core 的 MarginMath 算（服务端强平用同一份），全仓仓位共用一个保证金率
        Map<String, MarginMath.PositionRisk> risks = ClientTrading.risks();
        String close = Component.translatable("screen.creepercapital.trading.positions.close").getString();
        for (Position position : ClientTrading.positions()) {
            MarketSnapshot snapshot = ClientMarket.snapshot(position.symbol()).orElse(null);
            double mark = snapshot == null ? position.entryPrice() : snapshot.markPrice();
            Money pnl = position.pnlAt(mark);
            double roe = position.returnOnEquity(mark);
            int sideColor = TradingPalette.side(position.side() == Side.LONG);
            String mode = Component.translatable(position.mode() == MarginMode.CROSS
                    ? "screen.creepercapital.trading.order.cross" : "screen.creepercapital.trading.order.isolated").getString();
            MarginMath.PositionRisk risk = risks.get(position.symbol());
            double ratio = risk == null ? 0 : risk.marginRatio();
            // 保证金率越高越危险：50% 起品牌黄提醒，80% 起用亏损色（跟随涨跌色方案）
            int ratioColor = ratio >= 0.8 ? TradingPalette.down() : ratio >= 0.5 ? TradingPalette.BRAND_YELLOW : TradingPalette.TEXT_PRIMARY;
            String liquidation = risk == null || risk.liquidationPrice().isEmpty() ? "--"
                    : MarketFormat.price(risk.liquidationPrice().getAsDouble());
            String symbol = ContractSpec.displaySymbol(position.symbol());
            columns.add(
                    Cell.text(symbol, sideColor),
                    Cell.text(mode + " " + position.leverage() + "x", TradingPalette.TEXT_SECONDARY),
                    Cell.text(Quantities.format(position.lots(), position.quantityDecimals()), sideColor),
                    Cell.text(MarketFormat.price(position.entryPrice()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(MarketFormat.price(mark), TradingPalette.TEXT_PRIMARY),
                    Cell.text(liquidation, TradingPalette.BRAND_YELLOW),
                    Cell.text(MarginMath.formatRatio(ratio), ratioColor),
                    Cell.text(MarketFormat.money(position.margin()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(MarketFormat.signedMoney(pnl), TradingPalette.signed(pnl.units())),
                    Cell.text(MarketFormat.signedPercent(roe), TradingPalette.signed(roe)),
                    Cell.lights(ClientTrading.adlLights(symbol)),
                    Cell.button(close, () -> ClientTrading.position(symbol)
                            .ifPresent(held -> ClientTrading.send(OrderPayload.Action.CLOSE, symbol, held.lots()))));
        }
        LayoutFit.Columns fit = header(graphics, top, columns, "symbol", "mode", "size", "entry_price", "mark_price", "liq_price",
                "margin_ratio", "margin", "pnl", "roe", "adl", "action");
        body(graphics, top + HEADER_HEIGHT, bottom, columns, fit, "screen.creepercapital.trading.positions.empty", mouseX, mouseY);
    }

    void extractTrades(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        forgetButtons();
        Grid columns = new Grid(left, right, TRADE_PRIORITY);
        for (PlayerTrade trade : ClientTrading.tradesNewestFirst()) {
            int decimals = ClientTrading.params(trade.symbol()).map(p -> p.quantityDecimals()).orElse(0);
            boolean opensLong = (trade.side() == Side.LONG) == (trade.kind() == TradeKind.OPEN);
            // 强平和自动减仓用品牌黄色，一眼和主动平仓区分开
            int sideColor = trade.kind() == TradeKind.LIQUIDATION || trade.kind() == TradeKind.ADL ? TradingPalette.BRAND_YELLOW
                    : TradingPalette.side(opensLong);
            columns.add(
                    Cell.text(MarketFormat.marketTime(new MarketTime(trade.marketSeconds())), TradingPalette.TEXT_SECONDARY),
                    Cell.text(ContractSpec.displaySymbol(trade.symbol()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(Component.translatable("screen.creepercapital.trading.trade_side."
                            + trade.kind().name().toLowerCase(Locale.ROOT) + "_" + trade.side().name().toLowerCase(Locale.ROOT))
                            .getString(), sideColor),
                    Cell.text(MarketFormat.price(trade.price()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(Quantities.format(trade.lots(), decimals), TradingPalette.TEXT_PRIMARY),
                    Cell.text(MarketFormat.fee(trade.fee()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(trade.kind() == TradeKind.OPEN ? "--" : MarketFormat.signedMoney(trade.realizedPnl()),
                            trade.kind() == TradeKind.OPEN ? TradingPalette.TEXT_SECONDARY
                                    : TradingPalette.signed(trade.realizedPnl().units())));
        }
        LayoutFit.Columns fit = header(graphics, top, columns, "time", "symbol", "side", "price", "quantity", "fee", "realized_pnl");
        body(graphics, top + HEADER_HEIGHT, bottom, columns, fit, "screen.creepercapital.trading.trades.empty", mouseX, mouseY);
    }

    /** 资金费记录页：每一次结算一行（最新在前）；金额站在玩家一侧，收到涨色、支付跌色。 */
    void extractFunding(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        forgetButtons();
        Grid columns = new Grid(left, right, FUNDING_PRIORITY);
        for (FundingRecord record : ClientTrading.fundingNewestFirst()) {
            boolean shortfall = record.shortfall().isPositive();
            columns.add(
                    Cell.text(MarketFormat.marketTime(new MarketTime(record.marketSeconds())), TradingPalette.TEXT_SECONDARY),
                    Cell.text(ContractSpec.displaySymbol(record.symbol()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(Component.translatable("screen.creepercapital.trading.side."
                            + record.side().name().toLowerCase(Locale.ROOT)).getString(), TradingPalette.side(record.side() == Side.LONG)),
                    Cell.text(MarketFormat.fundingRate(record.rate()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(MarketFormat.price(record.markPrice()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(MarketFormat.money(record.notional()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(MarketFormat.signedMoney(record.amount()), TradingPalette.signed(record.amount().units())),
                    Cell.text(shortfall ? MarketFormat.money(record.shortfall()) : "--",
                            shortfall ? TradingPalette.BRAND_YELLOW : TradingPalette.TEXT_SECONDARY));
        }
        LayoutFit.Columns fit = header(graphics, top, columns, "time", "symbol", "side", "funding_rate", "mark_price", "notional",
                "funding_amount", "funding_short");
        body(graphics, top + HEADER_HEIGHT, bottom, columns, fit, "screen.creepercapital.trading.funding.empty", mouseX, mouseY);
    }

    void extractOpenOrders(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        forgetButtons();
        Grid columns = new Grid(left, right, OPEN_ORDER_PRIORITY);
        String cancel = Component.translatable("screen.creepercapital.trading.orders.cancel").getString();
        for (Order order : ClientTrading.openOrdersNewestFirst()) {
            long id = order.id();
            Cell[] head = orderCells(order, order.createdSeconds());
            columns.add(head[0], head[1], head[2], head[3],
                    Cell.text(MarketFormat.price(order.price()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(Quantities.format(order.lots(), decimalsOf(order)), TradingPalette.TEXT_PRIMARY),
                    Cell.text(order.frozen().isPositive() ? MarketFormat.money(order.frozen()) : "--", TradingPalette.TEXT_PRIMARY),
                    Cell.button(cancel, () -> ClientTrading.cancel(id)));
        }
        LayoutFit.Columns fit = header(graphics, top, columns, "time", "symbol", "side", "type", "order_price", "quantity", "frozen",
                "action");
        body(graphics, top + HEADER_HEIGHT, bottom, columns, fit, "screen.creepercapital.trading.orders.open_empty", mouseX, mouseY);
    }

    void extractOrderHistory(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        forgetButtons();
        Grid columns = new Grid(left, right, ORDER_HISTORY_PRIORITY);
        for (Order order : ClientTrading.orderHistoryNewestFirst()) {
            boolean filled = order.status() == OrderStatus.FILLED;
            String statusKey = filled ? "filled" : order.cancelReason() == CancelReason.FILL_REJECTED ? "fill_rejected"
                    : order.cancelReason() == CancelReason.POSITION_CLOSED ? "position_closed"
                    : order.cancelReason() == CancelReason.LIQUIDATED ? "liquidated" : "cancelled";
            Cell[] head = orderCells(order, order.closedSeconds());
            columns.add(head[0], head[1], head[2], head[3],
                    Cell.text(MarketFormat.price(order.price()), TradingPalette.TEXT_PRIMARY),
                    Cell.text(filled ? MarketFormat.price(order.fillPrice()) : "--", TradingPalette.TEXT_PRIMARY),
                    Cell.text(Quantities.format(order.lots(), decimalsOf(order)), TradingPalette.TEXT_PRIMARY),
                    Cell.text(filled ? MarketFormat.fee(order.fee()) : "--", TradingPalette.TEXT_PRIMARY),
                    Cell.text(Component.translatable("screen.creepercapital.trading.order_status." + statusKey).getString(),
                            filled ? TradingPalette.TEXT_PRIMARY : TradingPalette.TEXT_SECONDARY));
        }
        LayoutFit.Columns fit = header(graphics, top, columns, "time", "symbol", "side", "type", "order_price", "fill_price", "quantity",
                "fee", "status");
        body(graphics, top + HEADER_HEIGHT, bottom, columns, fit, "screen.creepercapital.trading.orders.history_empty", mouseX, mouseY);
    }

    /**
     * 委托行前四列：时间、合约、方向、类型。开仓委托的方向是开多 / 开空；平仓类委托（只减仓、止盈、止损）是平多 / 平空，
     * 颜色跟随所平仓位的方向。类型列：限价、限价只减仓、止盈 / 止损带触发价类型（最新价 / 标记价）。
     */
    private static Cell[] orderCells(Order order, long marketSeconds) {
        String kind = order.reduceOnly() ? "close_" : "open_";
        return new Cell[] {
                Cell.text(MarketFormat.marketTime(new MarketTime(marketSeconds)), TradingPalette.TEXT_SECONDARY),
                Cell.text(ContractSpec.displaySymbol(order.symbol()), TradingPalette.TEXT_PRIMARY),
                Cell.text(Component.translatable("screen.creepercapital.trading.trade_side." + kind
                        + order.side().name().toLowerCase(Locale.ROOT)).getString(), TradingPalette.side(order.side() == Side.LONG)),
                Cell.text(typeLabel(order), TradingPalette.TEXT_PRIMARY)};
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
        for (Button button : buttons) {
            if (button.contains(x, y)) {
                CuePlayer.play(Cue.BUTTON_CLICK);
                button.action().run();
                return true;
            }
        }
        return false;
    }

    /** 清掉上一帧记下的按钮区域（不在带按钮的页时调用）。 */
    void forgetButtons() {
        buttons.clear();
    }

    /** 按表头与各行内容量出每列需要的宽度，排好列、画表头（灰字，放不下截断），返回各列位置。 */
    private LayoutFit.Columns header(GuiGraphicsExtractor graphics, int top, Grid columns, String... keys) {
        String[] titles = new String[keys.length];
        int[] natural = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            titles[i] = Component.translatable("screen.creepercapital.trading.column." + keys[i]).getString();
            natural[i] = font.width(titles[i]);
        }
        for (Cell[] row : columns.rows) {
            for (int i = 0; i < keys.length; i++) {
                natural[i] = Math.max(natural[i], cellWidth(row[i]));
            }
        }
        LayoutFit.Columns fit = LayoutFit.columns(columns.left, columns.right, COLUMN_GAP, natural, columns.priority);
        for (int i = 0; i < keys.length; i++) {
            if (fit.visible(i)) {
                graphics.text(font, GuiText.fit(font, titles[i], fit.width()[i]), fit.x()[i], top + 2, TradingPalette.TEXT_SECONDARY,
                        false);
            }
        }
        return fit;
    }

    private int cellWidth(Cell cell) {
        if (cell.lit() >= 0) {
            return LIGHTS_WIDTH;
        }
        return font.width(cell.text()) + (cell.action() != null ? BUTTON_PADDING * 2 : 0);
    }

    /** 表体：从 {@link #scroll} 行起画到放不下为止；鼠标所在行整行变亮；没有数据时居中显示提示。 */
    private void body(GuiGraphicsExtractor graphics, int top, int bottom, Grid grid, LayoutFit.Columns fit, String emptyKey,
            int mouseX, int mouseY) {
        if (grid.rows.isEmpty()) {
            graphics.centeredText(font, Component.translatable(emptyKey), (grid.left + grid.right) / 2,
                    GuiText.centeredY(font, top, bottom), TradingPalette.TEXT_SECONDARY);
            return;
        }
        int visibleRows = Math.max(1, (bottom - top) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, grid.rows.size() - visibleRows));
        for (int i = 0; i < visibleRows && scroll + i < grid.rows.size(); i++) {
            Cell[] row = grid.rows.get(scroll + i);
            int y = top + i * ROW_HEIGHT;
            if (mouseX >= grid.left && mouseX < grid.right && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                graphics.fill(grid.left - 4, y, grid.right + 4, y + ROW_HEIGHT, TradingPalette.HOVER);
            }
            for (int c = 0; c < row.length; c++) {
                if (fit.visible(c)) {
                    extractCell(graphics, row[c], fit.x()[c], y, fit.width()[c], mouseX, mouseY);
                }
            }
        }
    }

    private void extractCell(GuiGraphicsExtractor graphics, Cell cell, int x, int y, int width, int mouseX, int mouseY) {
        if (cell.lit() >= 0) {
            lights(graphics, x, y, cell.lit());
            return;
        }
        if (cell.action() != null) {
            Button button = new Button(x, y, x + Math.min(width, font.width(cell.text()) + BUTTON_PADDING * 2), y + ROW_HEIGHT - 1,
                    cell.action());
            boolean hovered = button.contains(mouseX, mouseY);
            graphics.fill(button.left(), button.top(), button.right(), button.bottom(),
                    hovered ? TradingPalette.BUTTON_HOVER : TradingPalette.BUTTON);
            graphics.text(font, GuiText.fit(font, cell.text(), button.right() - button.left() - BUTTON_PADDING * 2),
                    x + BUTTON_PADDING, y + 2, TradingPalette.TEXT_PRIMARY, false);
            buttons.add(button);
            return;
        }
        graphics.text(font, GuiText.fit(font, cell.text(), width), x, y + 2, cell.color(), false);
    }

    /**
     * ADL 指示灯（#17）：五根逐渐增高的小柱，亮的用品牌黄、没亮的用分隔线灰；亮得越多越先被自动减仓。
     * 格数由服务端按全服排名算好同步过来，这里只画。品牌黄不随涨跌色方案变化。
     */
    private static void lights(GuiGraphicsExtractor graphics, int x, int rowTop, int lit) {
        int bottom = rowTop + ROW_HEIGHT - 1;
        for (int i = 0; i < AdlRanking.LIGHTS; i++) {
            int left = x + i * (LIGHT_WIDTH + 1);
            graphics.fill(left, bottom - (2 + 2 * i), left + LIGHT_WIDTH, bottom,
                    i < lit ? TradingPalette.BRAND_YELLOW : TradingPalette.DIVIDER);
        }
    }
}
