package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.trading.MarginMath;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Quantities;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.network.OrderPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * K 线图上叠加的本人交易信息（只看当前合约）：
 * <ul>
 * <li>买卖点：自己的成交标在对应 K 线上（买在最低价之下、卖在最高价之上），同一根同一方向多笔合并并显示笔数，见 {@link TradeMarkers}；</li>
 * <li>持仓线：开仓均价处一条实线，tag 显示方向、数量、未实现盈亏（按标记价格）；</li>
 * <li>强平价：品牌黄虚线，与仓位页的「预估强平价」同源（{@link ClientTrading#risks()}，即 {@link MarginMath}）；</li>
 * <li>当前委托：每张限价委托 / 止盈 / 止损一条虚线，tag 显示类型、数量、委托价或触发价。</li>
 * </ul>
 * 线与 tag 都按价格定位，随图表平移缩放；tag 靠绘图区左侧排，挤在一起时错开（{@link ChartTagLayout}），
 * 价格超出可见区间时 tag 钳在上沿 / 下沿并画方向箭头，线不画。价格轴上同步标出这些价格。
 * <p>
 * 拖拽设止盈止损（#36）：按住持仓线 tag 上下拖，参考价的盈利方向拖出止盈线、亏损方向拖出止损线（{@link ProtectionDrag}），
 * 拖动中 tag 实时显示类型、目标价与预计盈亏；松开后虚线停留，tag 右端出现 ✓ / ✕，点 ✓ 提交（数量是整个仓位，
 * 走现有的补设止盈止损通道），点 ✕ 或按 Esc 取消。已有的止盈 / 止损 tag 同样可以拖：确认后先挂新单、成功后撤旧单
 * （{@link ChartOrderActions}）。拖回原处（离原来那条线不到 3 像素）松开即取消；价格不合法（与服务端同一条规则）时提示原因并还原。
 * <p>
 * 快捷撤销（#37）：每张当前委托的 tag 右端有一个「×」，点一下直接撤单（不二次确认），撤销中 tag 置灰、「×」点不动，
 * 成功后线与 tag 随同步消失，失败在图表顶部提示（{@link ChartOrderActions}）。
 * tag 的命中区域见 {@link ChartTagHits}：文字区拖拽，按钮（✓ / ✕ / ×）在右端，中间隔一段不触发任何操作的缝，拖拽与撤销互不误触。
 */
final class ChartOverlay {
    private static final int TAG_PAD = 3;
    private static final int TAG_GAP = 1;
    private static final int TAG_INSET = 2;
    private static final int ARROW_WIDTH = 7;

    enum Kind {
        POSITION,
        LIQUIDATION,
        ORDER,
        /** 正在拖或等确认的止盈 / 止损。 */
        EDIT,
        /** 已提交、等回报的止盈 / 止损。 */
        PENDING
    }

    /**
     * 图上一条线。
     *
     * @param kind      线的种类
     * @param price     价格
     * @param color     线色（tag 边框、文字、价格轴标记的底色）
     * @param text      tag 的文字
     * @param dashed    虚线
     * @param order     对应的当前委托；不是委托线为 null
     * @param draggable tag 的文字区能否拖动
     * @param buttons   tag 右端的按钮个数
     */
    record Line(Kind kind, double price, int color, String text, boolean dashed, @Nullable Order order, boolean draggable,
            int buttons) {
        Line(Kind kind, double price, int color, String text, boolean dashed, @Nullable Order order) {
            this(kind, price, color, text, dashed, order, false, 0);
        }
    }

    /** 正在拖或等确认的止盈 / 止损；{@code original} 非空时是在改这张已有的委托。 */
    private static final class Edit {
        final String symbol;
        final @Nullable Order original;
        double price;
        boolean dragging = true;

        Edit(String symbol, @Nullable Order original, double price) {
            this.symbol = symbol;
            this.original = original;
            this.price = price;
        }
    }

    /** 某一刻编辑的解读：类型、参考价、是否合法、数量与预计盈亏。 */
    private record EditInfo(Position position, @Nullable OrderType type, TriggerSource source, double reference, boolean valid,
            long lots) {
    }

    private final Font font;
    private @Nullable Edit edit;
    /** 上一帧画出的线、tag 位置与价格轴，鼠标事件据此判断。 */
    private List<Line> frameLines = List.of();
    private List<ChartTagHits.Tag> frameTags = List.of();
    private @Nullable PriceScale frameScale;

    ChartOverlay(Font font) {
        this.font = font;
    }

    int tagHeight() {
        return font.lineHeight + TAG_PAD;
    }

    // ===== 拖拽设止盈止损 =====

    /** 点在某个 tag 上：按钮执行对应操作，文字区开始拖拽；点在 tag 上的都由这里吃掉（不会开始平移图表）。 */
    boolean mouseClicked(double x, double y) {
        ChartTagHits.Hit hit = ChartTagHits.hit(frameTags, x, y);
        if (hit.tag() < 0) {
            return false;
        }
        Line line = frameLines.get(hit.tag());
        String symbol = ClientMarket.selectedSymbol().orElse(null);
        if (hit.zone() == ChartTagHits.Zone.BUTTON) {
            if (line.kind() == Kind.EDIT) {
                if (hit.button() == 0) {
                    confirmEdit();
                } else {
                    edit = null;
                }
            } else if (line.kind() == Kind.ORDER && line.order() != null && !ChartOrderActions.cancelling(line.order().id())) {
                ChartOrderActions.cancel(line.order().id());
            }
        } else if (hit.zone() == ChartTagHits.Zone.DRAG && symbol != null) {
            if (line.kind() == Kind.EDIT && edit != null) {
                edit.dragging = true;
            } else if (line.kind() == Kind.POSITION) {
                edit = new Edit(symbol, null, line.price());
            } else if (line.kind() == Kind.ORDER && line.order() != null) {
                edit = new Edit(symbol, line.order(), line.order().price());
            }
        }
        return true;
    }

    boolean isDragging() {
        return edit != null && edit.dragging;
    }

    /** 拖动中：线跟着鼠标（纵坐标夹在主图区之内）。 */
    void mouseDragged(double y) {
        if (edit == null || !edit.dragging || frameScale == null) {
            return;
        }
        double clamped = Math.max(frameScale.top(), Math.min(frameScale.top() + frameScale.height() - 1, y));
        edit.price = ProtectionDrag.priceAt(frameScale, clamped);
    }

    /** 松开：拖回原处则取消，价格不合法则提示并还原，否则停留等确认。 */
    void mouseReleased() {
        if (edit == null || !edit.dragging) {
            return;
        }
        edit.dragging = false;
        EditInfo info = info(edit);
        if (info == null) {
            edit = null;
            return;
        }
        double origin = edit.original != null ? edit.original.price() : info.position().entryPrice();
        if (frameScale != null && (Double.isNaN(edit.price)
                || ProtectionDrag.draggedBack(yOf(frameScale, origin), yOf(frameScale, edit.price)))) {
            if (Double.isNaN(edit.price)) {
                rejectInvalid(info);
            }
            edit = null;
            return;
        }
        if (!info.valid()) {
            rejectInvalid(info);
            edit = null;
        }
    }

    /** 按 Esc：取消正在拖或等确认的止盈止损；返回是否有东西被取消。 */
    boolean cancelEdit() {
        if (edit == null) {
            return false;
        }
        edit = null;
        return true;
    }

    /** 图表这一帧没画：放弃编辑。 */
    void forget() {
        edit = null;
        frameTags = List.of();
        frameLines = List.of();
        frameScale = null;
    }

    /** 点 ✓：提交前按此刻的参考价再校验一次（价格可能已经走过去了），合法才发出。 */
    private void confirmEdit() {
        Edit current = edit;
        edit = null;
        if (current == null) {
            return;
        }
        EditInfo info = info(current);
        if (info == null) {
            return;
        }
        if (!info.valid() || info.type() == null) {
            rejectInvalid(info);
            return;
        }
        OrderPayload payload = OrderPayload.protect(current.symbol, info.lots(), info.type() == OrderType.TAKE_PROFIT,
                current.price, info.source());
        ChartOrderActions.submit(payload, info.type(), current.price, current.original != null ? current.original.id() : 0);
    }

    /** 不合法：用服务端拒单的同一句文案提示（带此刻的参考价），线还原。 */
    private static void rejectInvalid(EditInfo info) {
        ChartOrderActions.showNotice(Component.translatable("message.creepercapital.order.invalid_trigger_price",
                MarketFormat.price(info.reference())), true);
    }

    /** 解读编辑：仓位没了、改的那张委托已不在、换了合约时为 null（编辑作废）。 */
    private @Nullable EditInfo info(Edit current) {
        Optional<Position> found = ClientTrading.position(current.symbol);
        if (found.isEmpty() || !ClientMarket.selectedSymbol().map(current.symbol::equals).orElse(false)) {
            return null;
        }
        Position position = found.get();
        Order original = current.original;
        if (original != null && ClientTrading.openOrdersNewestFirst().stream().noneMatch(o -> o.id() == original.id())) {
            return null;
        }
        TriggerSource source = original != null ? original.triggerSource() : ClientTrading.triggerSource();
        double reference = ClientMarket.snapshot(current.symbol)
                .map(s -> source == TriggerSource.MARK ? s.markPrice() : s.lastPrice()).orElse(Double.NaN);
        OrderType type = original != null ? original.type() : ProtectionDrag.typeFor(position.side(), reference, current.price);
        boolean valid = type != null && ProtectionDrag.valid(position.side(), type, current.price, reference);
        long lots = original != null ? Math.min(original.lots(), position.lots()) : position.lots();
        return new EditInfo(position, type, source, reference, valid, lots);
    }

    /** 开发用（自动截图）：从当前合约的持仓线拖出一条触发价 = 参考价 × {@code factor} 的线，{@code dragging} 为 false 时是松开后的等确认态。 */
    void devEdit(double factor, boolean dragging) {
        String symbol = ClientMarket.selectedSymbol().orElse(null);
        if (symbol == null) {
            return;
        }
        double reference = ClientMarket.snapshot(symbol).map(MarketSnapshot::lastPrice).orElse(Double.NaN);
        Edit created = new Edit(symbol, null, Math.round(reference * factor * 100) / 100.0);
        created.dragging = dragging;
        edit = created;
    }

    // ===== 线 =====

    /** 这一帧要画的线：持仓线、强平价、当前委托（按委托编号）、正在编辑 / 等回报的止盈止损，都只看 {@code symbol}。 */
    List<Line> lines(String symbol) {
        List<Line> lines = new ArrayList<>();
        Optional<Position> position = ClientTrading.position(symbol);
        if (position.isPresent()) {
            Position p = position.get();
            boolean isLong = p.side() == Side.LONG;
            double mark = ClientMarket.snapshot(symbol).map(MarketSnapshot::markPrice).orElse(p.entryPrice());
            String pnl = MarketFormat.signedMoney(p.pnlAt(mark));
            String text = Component.translatable("screen.creepercapital.trading.chart.position",
                    Component.translatable("screen.creepercapital.trading.side." + (isLong ? "long" : "short")),
                    Quantities.format(p.lots(), p.quantityDecimals()), pnl).getString();
            lines.add(new Line(Kind.POSITION, p.entryPrice(), TradingPalette.side(isLong), text, false, null, true, 0));
            MarginMath.PositionRisk risk = ClientTrading.risks().get(symbol);
            if (risk != null && risk.liquidationPrice().isPresent()) {
                double liquidation = risk.liquidationPrice().getAsDouble();
                lines.add(new Line(Kind.LIQUIDATION, liquidation, TradingPalette.BRAND_YELLOW,
                        Component.translatable("screen.creepercapital.trading.chart.liquidation",
                                MarketFormat.price(liquidation)).getString(), true, null));
            }
        }
        Order editing = edit != null && edit.symbol.equals(symbol) ? edit.original : null;
        List<Order> orders = new ArrayList<>(ClientTrading.openOrdersNewestFirst());
        java.util.Collections.reverse(orders);
        for (Order order : orders) {
            if (!order.symbol().equals(symbol) || ChartOrderActions.replacing(order.id())
                    || (editing != null && editing.id() == order.id())) {
                continue; // 正在改的 / 改单后正在撤的旧单：图上只画新位置
            }
            if (ChartOrderActions.cancelling(order.id())) {
                // 撤销中：置灰，不能拖，「×」点不动
                lines.add(new Line(Kind.ORDER, order.price(), TradingPalette.DISABLED,
                        Component.translatable("screen.creepercapital.trading.chart.cancelling", orderText(order)).getString(),
                        true, order, false, 1));
                continue;
            }
            boolean protection = order.type().isTrigger() && position.isPresent();
            lines.add(new Line(Kind.ORDER, order.price(), orderColor(order), orderText(order), true, order, protection, 1));
        }
        ChartOrderActions.Pending pending = ChartOrderActions.pending();
        if (pending != null && pending.symbol().equals(symbol)) {
            lines.add(new Line(Kind.PENDING, pending.price(), TradingPalette.TEXT_SECONDARY,
                    Component.translatable("screen.creepercapital.trading.chart.submitting", typeName(pending.type()),
                            MarketFormat.price(pending.price())).getString(), true, null));
        }
        Line editLine = editLine(symbol);
        if (editLine != null) {
            lines.add(editLine);
        }
        return lines;
    }

    /** 正在编辑的那条线（没有、或已作废时为 null）：类型与合法性按此刻的参考价算，tag 显示类型、目标价与预计盈亏。 */
    private @Nullable Line editLine(String symbol) {
        if (edit == null) {
            return null;
        }
        EditInfo info = edit.symbol.equals(symbol) ? info(edit) : null;
        if (info == null) {
            edit = null;
            return null;
        }
        String price = Double.isNaN(edit.price) ? "--" : MarketFormat.price(edit.price);
        String type = info.type() == null ? Component.translatable("screen.creepercapital.trading.chart.protection").getString()
                : typeName(info.type());
        String text;
        int color;
        if (info.valid()) {
            Position p = info.position();
            Position portion = new Position(p.symbol(), p.side(), info.lots(), p.entryPrice(), p.leverage(), p.margin(),
                    p.quantityDecimals(), p.mode());
            text = Component.translatable("screen.creepercapital.trading.chart.edit", type, price,
                    MarketFormat.signedMoney(portion.pnlAt(edit.price))).getString();
            color = info.type() == OrderType.TAKE_PROFIT ? TradingPalette.up() : TradingPalette.down();
        } else {
            text = Component.translatable("screen.creepercapital.trading.chart.edit_invalid", type, price).getString();
            color = TradingPalette.TEXT_SECONDARY;
        }
        return new Line(Kind.EDIT, Double.isNaN(edit.price) ? 0 : edit.price, color, text, true, edit.original, true,
                edit.dragging ? 0 : 2);
    }

    private static String typeName(OrderType type) {
        return Component.translatable(type == OrderType.TAKE_PROFIT ? "screen.creepercapital.trading.chart.take_profit"
                : "screen.creepercapital.trading.chart.stop_loss").getString();
    }

    /** 委托线的颜色：限价委托按买卖（买涨色、卖跌色），止盈取盈利色（涨色）、止损取亏损色（跌色）。 */
    static int orderColor(Order order) {
        return switch (order.type()) {
            case TAKE_PROFIT -> TradingPalette.up();
            case STOP_LOSS -> TradingPalette.down();
            case LIMIT -> TradingPalette.side(isBuy(order));
        };
    }

    /** 限价委托是买入还是卖出：开多、平空是买。 */
    private static boolean isBuy(Order order) {
        return order.reduceOnly() != (order.side() == Side.LONG);
    }

    /** 委托 tag 的文字：类型（限价带开平方向，止盈止损带触发价来源）、数量、委托价 / 触发价。 */
    static String orderText(Order order) {
        String type;
        if (order.type() == OrderType.LIMIT) {
            String side = (order.reduceOnly() ? "close_" : "open_") + (order.side() == Side.LONG ? "long" : "short");
            type = Component.translatable("screen.creepercapital.trading.trade_side." + side).getString() + " "
                    + TradeTables.typeLabel(order);
        } else {
            type = TradeTables.typeLabel(order);
        }
        int decimals = ClientTrading.params(order.symbol()).map(p -> p.quantityDecimals()).orElse(0);
        return Component.translatable("screen.creepercapital.trading.chart.order", type,
                Quantities.format(order.lots(), decimals), MarketFormat.price(order.price())).getString();
    }

    // ===== 绘制 =====

    /** 买卖点：画在绘图区的裁剪范围之内（由调用方开裁剪）。 */
    void extractMarkers(GuiGraphicsExtractor graphics, String symbol, List<Candle> candles, long periodSeconds,
            ChartViewport.Projection view, PriceScale scale) {
        int first = view.firstVisible();
        int last = view.lastVisible();
        int mainTop = scale.top();
        int mainBottom = scale.top() + scale.height();
        for (TradeMarkers.Marker marker : TradeMarkers.group(ClientTrading.trades(), symbol, candles, periodSeconds)) {
            if (marker.candle() < first || marker.candle() > last) {
                continue;
            }
            Candle candle = candles.get(marker.candle());
            int x = (int) Math.floor(view.centerX(marker.candle()));
            int color = TradingPalette.side(marker.buy());
            String label = Component.translatable(marker.buy() ? "screen.creepercapital.trading.chart.buy"
                    : "screen.creepercapital.trading.chart.sell").getString();
            if (marker.count() > 1) {
                label = Component.translatable("screen.creepercapital.trading.chart.marker_count", label, marker.count()).getString();
            }
            int width = font.width(label) + 4;
            int height = font.lineHeight + 2;
            // 标签不出绘图区左右边缘（最新一根贴着右缘时整块往左挪，三角仍对着 K 线）
            int labelLeft = Math.max((int) view.plotLeft(), Math.min(x - width / 2, (int) view.plotRight() - width));
            int markerHeight = 3 + 1 + height;
            int top;
            if (marker.buy()) {
                // 最低价之下：先是指向 K 线的三角，再是标签
                top = Math.min(scale.y(candle.low()) + 2, mainBottom - markerHeight);
                extractTriangle(graphics, x, top, true, color);
                extractMarkerLabel(graphics, labelLeft, top + 4, width, height, label, color);
            } else {
                top = Math.max(scale.y(candle.high()) - 2 - markerHeight, mainTop);
                extractMarkerLabel(graphics, labelLeft, top, width, height, label, color);
                extractTriangle(graphics, x, top + height + 1, false, color);
            }
        }
    }

    private void extractMarkerLabel(GuiGraphicsExtractor graphics, int left, int top, int width, int height, String label, int color) {
        graphics.fill(left, top, left + width, top + height, color);
        graphics.text(font, label, left + 2, top + 1, TradingPalette.TEXT_PRIMARY, false);
    }

    /** 5 像素宽、3 像素高的三角，{@code up} 时尖朝上；{@code top} 是三角的上沿，{@code x} 是中线。 */
    private static void extractTriangle(GuiGraphicsExtractor graphics, int x, int top, boolean up, int color) {
        for (int row = 0; row < 3; row++) {
            int half = up ? row : 2 - row;
            graphics.fill(x - half, top + row, x + half + 1, top + row + 1, color);
        }
    }

    /** 线：在可见价格区间之内才画（画在绘图区裁剪范围之内）。 */
    void extractLines(GuiGraphicsExtractor graphics, List<Line> lines, int plotLeft, int plotRight, PriceScale scale) {
        int mainTop = scale.top();
        int mainBottom = scale.top() + scale.height();
        for (Line line : lines) {
            int y = yOf(scale, line.price());
            if (y < mainTop || y >= mainBottom) {
                continue;
            }
            if (line.dashed()) {
                for (int x = plotLeft; x < plotRight; x += 6) {
                    graphics.horizontalLine(x, Math.min(x + 3, plotRight - 1), y, line.color());
                }
            } else {
                graphics.horizontalLine(plotLeft, plotRight - 1, y, line.color());
            }
        }
    }

    /** 价格的纵坐标（远超区间的价格夹到一个足够远的值，免得整数溢出）。 */
    static int yOf(PriceScale scale, double price) {
        double span = scale.high() - scale.low();
        double clamped = Math.max(scale.low() - span * 100, Math.min(scale.high() + span * 100, price));
        return scale.y(clamped);
    }

    /**
     * 画所有 tag（靠绘图区左侧，错开不重叠）并记下各 tag 的命中区域；悬停在可拖的文字区时光标变上下箭头，悬停按钮时变手形。
     * 最后在主图区顶部画图表操作的结果提示。
     */
    void extractTags(GuiGraphicsExtractor graphics, List<Line> lines, int plotLeft, int plotRight, PriceScale scale,
            int mouseX, int mouseY) {
        int height = tagHeight();
        List<ChartTagLayout.Request> requests = new ArrayList<>(lines.size());
        for (Line line : lines) {
            requests.add(new ChartTagLayout.Request(yOf(scale, line.price()), height));
        }
        List<ChartTagLayout.Placement> placements = ChartTagLayout.layout(requests, scale.top(), scale.top() + scale.height(), TAG_GAP);
        List<ChartTagHits.Tag> tags = new ArrayList<>(lines.size());
        int maxWidth = Math.max(20, plotRight - plotLeft - TAG_INSET * 2);
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            ChartTagLayout.Placement placement = placements.get(i);
            int left = plotLeft + TAG_INSET;
            int arrow = placement.edge() != 0 ? ARROW_WIDTH : 0;
            int buttonsWidth = ChartTagHits.width(0, line.buttons());
            String text = font.plainSubstrByWidth(line.text(), maxWidth - arrow - 6 - buttonsWidth);
            int bodyWidth = arrow + font.width(text) + 6;
            int top = placement.top();
            ChartTagHits.Tag tag = new ChartTagHits.Tag(left, top, left + ChartTagHits.width(bodyWidth, line.buttons()),
                    top + height, line.buttons(), line.draggable());
            extractTagFrame(graphics, tag, line.color());
            int textLeft = left + 3;
            if (placement.edge() != 0) {
                extractTriangle(graphics, left + 4, top + (height - 3) / 2, placement.edge() < 0, line.color());
                textLeft += arrow;
            }
            graphics.text(font, text, textLeft, top + 2, line.color(), false);
            extractButtons(graphics, line, tag, mouseX, mouseY);
            tags.add(tag);
        }
        frameLines = List.copyOf(lines);
        frameTags = List.copyOf(tags);
        frameScale = scale;
        ChartTagHits.Hit hover = ChartTagHits.hit(tags, mouseX, mouseY);
        if (isDragging() || hover.zone() == ChartTagHits.Zone.DRAG) {
            graphics.requestCursor(CursorTypes.RESIZE_NS);
        } else if (hover.zone() == ChartTagHits.Zone.BUTTON && !cancelling(lines.get(hover.tag()))) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        extractNotice(graphics, plotLeft, plotRight, scale.top());
    }

    /** tag 右端的按钮：等确认的编辑是 ✓（提交）与 ✕（取消），当前委托是「×」（撤销，撤销中置灰）；按钮与文字区之间有一道分隔线。 */
    private void extractButtons(GuiGraphicsExtractor graphics, Line line, ChartTagHits.Tag tag, int mouseX, int mouseY) {
        if (line.buttons() == 0) {
            return;
        }
        graphics.verticalLine(ChartTagHits.bodyRight(tag) + ChartTagHits.DEAD_ZONE / 2, tag.top(), tag.bottom() - 1, line.color());
        for (int b = 0; b < line.buttons(); b++) {
            int left = ChartTagHits.buttonLeft(tag, b);
            boolean disabled = cancelling(line);
            boolean hovered = !disabled && mouseX >= left && mouseX < left + ChartTagHits.BUTTON_WIDTH && mouseY >= tag.top()
                    && mouseY < tag.bottom();
            graphics.fill(left, tag.top() + 1, left + ChartTagHits.BUTTON_WIDTH - 1, tag.bottom() - 1,
                    hovered ? TradingPalette.BUTTON_HOVER : TradingPalette.BUTTON);
            int iconLeft = left + (ChartTagHits.BUTTON_WIDTH - 1 - 7) / 2;
            int iconTop = tag.top() + (tag.bottom() - tag.top() - 7) / 2;
            if (line.kind() == Kind.EDIT && b == 0) {
                extractCheck(graphics, iconLeft, iconTop, TradingPalette.up());
            } else {
                extractCross(graphics, iconLeft, iconTop, disabled ? TradingPalette.TEXT_SECONDARY : TradingPalette.TEXT_PRIMARY);
            }
        }
    }

    /** 这条线是不是正在撤销的当前委托（「×」置灰、点不动）。 */
    private static boolean cancelling(Line line) {
        return line.kind() == Kind.ORDER && line.order() != null && ChartOrderActions.cancelling(line.order().id());
    }

    /** 7×7 的对勾，左上角在 {@code (x, y)}。 */
    private static void extractCheck(GuiGraphicsExtractor graphics, int x, int y, int color) {
        int[][] points = {{0, 3}, {1, 4}, {2, 5}, {3, 4}, {4, 3}, {5, 2}, {6, 1}};
        for (int[] point : points) {
            graphics.fill(x + point[0], y + point[1], x + point[0] + 1, y + point[1] + 2, color);
        }
    }

    /** 7×7 的叉，左上角在 {@code (x, y)}（画在中间 5×5）。 */
    private static void extractCross(GuiGraphicsExtractor graphics, int x, int y, int color) {
        for (int i = 0; i < 5; i++) {
            graphics.fill(x + 1 + i, y + 1 + i, x + 2 + i, y + 2 + i, color);
            graphics.fill(x + 5 - i, y + 1 + i, x + 6 - i, y + 2 + i, color);
        }
    }

    /** 图表操作的结果提示（成功白字、失败红字），在主图区顶部居中，几秒后消失。 */
    private void extractNotice(GuiGraphicsExtractor graphics, int plotLeft, int plotRight, int mainTop) {
        ChartOrderActions.Notice notice = ChartOrderActions.notice(System.currentTimeMillis());
        if (notice == null) {
            return;
        }
        String text = font.plainSubstrByWidth(notice.text().getString(), Math.max(20, plotRight - plotLeft - 16));
        int width = font.width(text) + 8;
        int left = (plotLeft + plotRight - width) / 2;
        graphics.fill(left, mainTop + 2, left + width, mainTop + 4 + font.lineHeight, TradingPalette.DIVIDER);
        graphics.text(font, text, left + 4, mainTop + 3, notice.error() ? TradingPalette.ERROR : TradingPalette.TEXT_PRIMARY, false);
    }

    /** tag 的底与边框：深色底、线色 1 像素边框。 */
    private static void extractTagFrame(GuiGraphicsExtractor graphics, ChartTagHits.Tag box, int color) {
        graphics.fill(box.left(), box.top(), box.right(), box.bottom(), TradingPalette.BACKGROUND);
        graphics.horizontalLine(box.left(), box.right() - 1, box.top(), color);
        graphics.horizontalLine(box.left(), box.right() - 1, box.bottom() - 1, color);
        graphics.verticalLine(box.left(), box.top(), box.bottom() - 1, color);
        graphics.verticalLine(box.right() - 1, box.top(), box.bottom() - 1, color);
    }

    /** 价格轴标记的高度。 */
    int axisMarkHeight() {
        return font.lineHeight + 2;
    }

    /**
     * 价格轴标记的上边缘：前 {@code lines.size()} 项与 {@code lines} 一一对应，最后一项是最新价格标签（原本中心在 {@code lastPriceY}）。
     * 两者一起按价格顺序错开、不互相遮挡，出界的贴在上沿 / 下沿。
     */
    List<Integer> axisMarkTops(List<Line> lines, PriceScale scale, int lastPriceY) {
        List<ChartTagLayout.Request> requests = new ArrayList<>(lines.size() + 1);
        for (Line line : lines) {
            requests.add(new ChartTagLayout.Request(yOf(scale, line.price()), axisMarkHeight()));
        }
        requests.add(new ChartTagLayout.Request(lastPriceY, axisMarkHeight()));
        return ChartTagLayout.layout(requests, scale.top(), scale.top() + scale.height(), 0).stream()
                .map(ChartTagLayout.Placement::top).toList();
    }

    /** 价格轴上的价格标记：线色底（强平价黄底深色字），位置见 {@link #axisMarkTops}。 */
    void extractAxisMarks(GuiGraphicsExtractor graphics, List<Line> lines, List<Integer> tops, int axisLeft, int right) {
        int height = axisMarkHeight();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            int top = tops.get(i);
            graphics.fill(axisLeft, top, right, top + height, line.color());
            int textColor = line.color() == TradingPalette.BRAND_YELLOW ? TradingPalette.BACKGROUND : TradingPalette.TEXT_PRIMARY;
            graphics.text(font, MarketFormat.price(line.price()), axisLeft + 2, top + 1, textColor, false);
        }
    }
}
