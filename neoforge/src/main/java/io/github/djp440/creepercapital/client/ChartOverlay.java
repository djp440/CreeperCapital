package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.trading.MarginMath;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Quantities;
import io.github.djp440.creepercapital.core.trading.Side;
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
 */
final class ChartOverlay {
    private static final int TAG_PAD = 3;
    private static final int TAG_GAP = 1;
    private static final int TAG_INSET = 2;
    private static final int ARROW_WIDTH = 7;

    enum Kind {
        POSITION,
        LIQUIDATION,
        ORDER
    }

    /**
     * 图上一条线。
     *
     * @param kind  线的种类
     * @param price 价格
     * @param color 线色（tag 边框、文字、价格轴标记的底色）
     * @param text  tag 的文字
     * @param dashed 虚线
     * @param order 对应的当前委托；不是委托线为 null
     */
    record Line(Kind kind, double price, int color, String text, boolean dashed, Order order) {
    }

    private final Font font;

    ChartOverlay(Font font) {
        this.font = font;
    }

    int tagHeight() {
        return font.lineHeight + TAG_PAD;
    }

    /** 这一帧要画的线：持仓线、强平价、当前委托（按委托编号），都只看 {@code symbol}。 */
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
            lines.add(new Line(Kind.POSITION, p.entryPrice(), TradingPalette.side(isLong), text, false, null));
            MarginMath.PositionRisk risk = ClientTrading.risks().get(symbol);
            if (risk != null && risk.liquidationPrice().isPresent()) {
                double liquidation = risk.liquidationPrice().getAsDouble();
                lines.add(new Line(Kind.LIQUIDATION, liquidation, TradingPalette.BRAND_YELLOW,
                        Component.translatable("screen.creepercapital.trading.chart.liquidation",
                                MarketFormat.price(liquidation)).getString(), true, null));
            }
        }
        List<Order> orders = new ArrayList<>(ClientTrading.openOrdersNewestFirst());
        java.util.Collections.reverse(orders);
        for (Order order : orders) {
            if (order.symbol().equals(symbol)) {
                lines.add(new Line(Kind.ORDER, order.price(), orderColor(order), orderText(order), true, order));
            }
        }
        return lines;
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

    /** 一个 tag 画在哪：边框、文字与按钮的位置由它决定（命中测试用同一份）。 */
    record TagBox(int left, int top, int right, int bottom, int edge) {
    }

    /** 画所有 tag（靠绘图区左侧，错开不重叠），返回各 tag 的位置（与 {@code lines} 一一对应）。 */
    List<TagBox> extractTags(GuiGraphicsExtractor graphics, List<Line> lines, int plotLeft, int plotRight, PriceScale scale) {
        int height = tagHeight();
        List<ChartTagLayout.Request> requests = new ArrayList<>(lines.size());
        for (Line line : lines) {
            requests.add(new ChartTagLayout.Request(yOf(scale, line.price()), height));
        }
        List<ChartTagLayout.Placement> placements = ChartTagLayout.layout(requests, scale.top(), scale.top() + scale.height(), TAG_GAP);
        List<TagBox> boxes = new ArrayList<>(lines.size());
        int maxWidth = Math.max(20, plotRight - plotLeft - TAG_INSET * 2);
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            ChartTagLayout.Placement placement = placements.get(i);
            int left = plotLeft + TAG_INSET;
            int arrow = placement.edge() != 0 ? ARROW_WIDTH : 0;
            String text = font.plainSubstrByWidth(line.text(), maxWidth - arrow - 6);
            int right = left + arrow + font.width(text) + 6;
            int top = placement.top();
            TagBox box = new TagBox(left, top, right, top + height, placement.edge());
            extractTagFrame(graphics, box, line.color());
            int textLeft = left + 3;
            if (placement.edge() != 0) {
                extractTriangle(graphics, left + 4, top + (height - 3) / 2, placement.edge() < 0, line.color());
                textLeft += arrow;
            }
            graphics.text(font, text, textLeft, top + 2, line.color(), false);
            boxes.add(box);
        }
        return boxes;
    }

    /** tag 的底与边框：深色底、线色 1 像素边框。 */
    static void extractTagFrame(GuiGraphicsExtractor graphics, TagBox box, int color) {
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

    /** 价格轴标记的上边缘（与 {@code lines} 一一对应）：错开不重叠，出界的贴在上沿 / 下沿。 */
    List<Integer> axisMarkTops(List<Line> lines, PriceScale scale) {
        List<ChartTagLayout.Request> requests = new ArrayList<>(lines.size());
        for (Line line : lines) {
            requests.add(new ChartTagLayout.Request(yOf(scale, line.price()), axisMarkHeight()));
        }
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
