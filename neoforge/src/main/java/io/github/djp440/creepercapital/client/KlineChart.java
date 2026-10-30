package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.djp440.creepercapital.client.sound.Cue;
import io.github.djp440.creepercapital.client.sound.CuePlayer;
import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.Indicators;
import io.github.djp440.creepercapital.core.market.MarketTime;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * K 线图（深色配色，涨跌色跟随 {@link ColorScheme}）：上方是蜡烛图（或线形图，见 {@link ChartType}）加均线，下方是成交量柱，右侧是价格轴，底部是市场时间轴，
 * 缺省最新一根贴着右边，往左依次是更早的 K 线。鼠标悬停某根时图例显示它的开高低收、成交量和均线值，时间轴上标出它的时间。
 * 图例逐项排，放不下的项不画；价格轴刻度避开最新价格标签，不与成交量刻度相碰。
 * <p>
 * 按住左键左右拖动平移、滚轮以鼠标为锚点缩放、双击回到「最新 + 缺省缩放」（视口见 {@link ChartViewport}，只在客户端已缓存的 K 线里移动）；
 * 价格轴随可见区间自动缩放。拖到缓存的最旧 / 最新一根时，边缘泛出黄光并提示。
 * <p>
 * 图例第二行：齿轮（展开指标设置浮层，见 {@link IndicatorPanel}）、各条均线、成交量；点图例项切换显示 / 隐藏，隐藏后图例变灰，
 * 不计算、不绘制，也不参与价格轴范围；显示状态与均线参数见 {@link IndicatorSettings}，保存在客户端配置里。
 * <p>
 * 图上叠加本人在当前合约上的买卖点、持仓线、强平价与当前委托（见 {@link ChartOverlay}），价格轴同步标出这些价格。
 * 持有的状态是视口、拖拽、到头反馈和浮层，K 线数据由调用方每帧传入。
 */
final class KlineChart {
    /** 每根 K 线占的横向像素，实体宽 {@link #BODY}，影线在正中间 1 像素。 */
    private static final int SLOT = 7;
    private static final int BODY = 5;
    /** 价格轴最窄的宽度；实际宽度按最高价的文字宽度算。 */
    private static final int MIN_AXIS_WIDTH = 36;
    private static final int GRID_LINES = 4;
    private static final int VOLUME_GAP = 6;
    private static final int MIN_VOLUME_HEIGHT = 24;
    private static final int LEGEND_GAP = 6;
    /** 成交量柱的半透明度，叠在网格上不抢眼。 */
    private static final int VOLUME_ALPHA = 0xB3000000;

    /** 到头反馈（边缘黄光和提示）持续的毫秒数。 */
    private static final long EDGE_FEEDBACK_MILLIS = 900;
    private static final int EDGE_GLOW_WIDTH = 12;
    /** 线形图面积填充在曲线处的最大不透明度。 */
    private static final int AREA_ALPHA = 0x66;

    private record LegendHit(int left, int top, int right, int bottom, Runnable action) {
        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    private final Font font;
    private final IndicatorPanel panel;
    /** 本人的买卖点、持仓线、强平价与当前委托。 */
    private final ChartOverlay overlay;
    private final List<LegendHit> legendHits = new ArrayList<>();

    private ChartViewport viewport = ChartViewport.latest();
    /** 换了 K 线数据时复位到的视口；平时就是缺省，只有开发截图会改。 */
    private ChartViewport home = ChartViewport.latest();
    /** 视口对应的那份 K 线（换了合约 / 周期 / 重新拉取后是另一个对象，视口复位）。 */
    private List<Candle> trackedCandles;
    /** 上一帧最后一根的开始时间，用来发现追加了新 K 线（平移着看历史时保持不动）。 */
    private long trackedLastStart;
    /** 上一帧画出的绘图区（蜡烛 + 成交量，不含价格轴）；没画时为 null，鼠标事件据此判断。 */
    private int plotLeft;
    private int plotRight;
    private int plotTop;
    private int plotBottom;
    private boolean plotValid;
    private boolean dragging;
    /** 最近一次拖到头的时刻与方向（1 = 到了最旧一根，-1 = 到了最新一根）。 */
    private long edgeMillis = Long.MIN_VALUE / 2;
    private int edgeSide;
    /** 开发截图用：到头反馈固定显示，不随时间淡出。 */
    private boolean edgeHold;

    KlineChart(Font font) {
        this.font = font;
        this.panel = new IndicatorPanel(font);
        this.overlay = new ChartOverlay(font);
    }

    /** 指标设置浮层里的周期输入框；交易界面要把它们登记为子部件，才能收到键盘输入。 */
    List<EditBox> inputs() {
        return panel.inputs();
    }

    boolean anyInputFocused() {
        return panel.anyInputFocused();
    }

    void unfocusInputs() {
        panel.unfocusInputs();
    }

    boolean settingsOpen() {
        return panel.isOpen();
    }

    void closeSettings() {
        panel.close();
    }

    /** 点在指标设置浮层的周期输入框上：返回被点中的输入框（交给界面设焦点）；没点中返回 {@code null}。 */
    EditBox mouseClickedInput(MouseButtonEvent event, boolean doubleClick) {
        return panel.mouseClickedInput(event, doubleClick);
    }

    /** 开发用（自动截图）：展开指标设置浮层，并展开第 {@code paletteRow} 行的色盘（-1 不展开）；{@code typedText} 非空时在第 {@code typedRow} 行输入框里填入这段文字。 */
    void devOpenSettings(int paletteRow, int typedRow, String typedText) {
        panel.devOpen(paletteRow, typedRow, typedText);
    }

    // ===== 鼠标交互：由交易界面在点击 / 拖动 / 滚轮时调用 =====

    private boolean inPlot(double x, double y) {
        return plotValid && x >= plotLeft && x < plotRight && y >= plotTop && y < plotBottom;
    }

    /**
     * 点击：指标设置浮层打开时点在浮层里由浮层处理，点在浮层外先收起浮层；点图例项切换显示 / 隐藏、点齿轮展开浮层；
     * 左键点在绘图区开始拖动平移，双击复位。返回是否由图表处理。
     */
    boolean mouseClicked(double x, double y, int button, boolean doubleClick) {
        if (panel.isOpen()) {
            if (panel.mouseClicked(x, y, button)) {
                return true;
            }
            panel.close();
            if (inPlot(x, y)) {
                return true; // 这一下只用来收起浮层，不当作拖动的开始
            }
        }
        if (button == 0 && plotValid) {
            for (LegendHit hit : List.copyOf(legendHits)) {
                if (hit.contains(x, y)) {
                    CuePlayer.play(Cue.TAB_SWITCH);
                    hit.action().run();
                    return true;
                }
            }
        }
        if (button == 0 && overlay.mouseClicked(x, y)) {
            return true; // 点在持仓线 / 委托的 tag 上：拖拽设止盈止损或点按钮
        }
        if (button != 0 || !inPlot(x, y)) {
            return false;
        }
        if (doubleClick) {
            viewport = ChartViewport.latest();
            dragging = false;
        } else {
            dragging = true;
        }
        return true;
    }

    /** 正在拖动：平移图表，或拖着止盈止损线。 */
    boolean isDragging() {
        return dragging || overlay.isDragging();
    }

    /**
     * 拖动到 {@code y}、横向移动 {@code dx} 像素：拖着止盈止损线时线跟着鼠标纵坐标；否则平移图表（向右为正，回看更早），
     * 到头时记下方向，下一帧起泛黄光提示。
     */
    boolean mouseDragged(double y, double dx) {
        if (overlay.isDragging()) {
            overlay.mouseDragged(y);
            return true;
        }
        if (!dragging || trackedCandles == null) {
            return false;
        }
        int count = trackedCandles.size();
        double width = plotRight - plotLeft;
        double over = viewport.overscroll(dx, count, width);
        viewport = viewport.panned(dx, count, width);
        if (Math.abs(over) > 0.5) {
            edgeSide = over > 0 ? 1 : -1;
            edgeMillis = System.currentTimeMillis();
        }
        return true;
    }

    boolean mouseReleased(int button) {
        if (button == 0 && overlay.isDragging()) {
            overlay.mouseReleased();
            return true;
        }
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return false;
    }

    /** 滚轮在绘图区：以鼠标位置为锚点缩放（向上滚放大）。 */
    boolean mouseScrolled(double x, double y, double scrollY) {
        if (panel.contains(x, y)) {
            return true; // 浮层上滚轮不缩放图表
        }
        if (!inPlot(x, y) || trackedCandles == null || scrollY == 0) {
            return false;
        }
        viewport = viewport.zoomed(scrollY, x, plotLeft, plotRight, trackedCandles.size());
        return true;
    }

    /** 这一帧没画图表（切到了别的页、没有数据）：取消进行中的拖动，鼠标事件不再响应。 */
    void forget() {
        plotValid = false;
        dragging = false;
        panel.close();
        overlay.forget();
    }

    /** 按 Esc：取消正在拖或等确认的止盈止损；返回是否有东西被取消。 */
    boolean cancelEdit() {
        return overlay.cancelEdit();
    }

    /** 开发用（自动截图）：从持仓线拖出一条触发价 = 最新价格 × {@code factor} 的止盈止损线；{@code dragging} 为 false 时是等确认态。 */
    void devEdit(double factor, boolean dragging) {
        overlay.devEdit(factor, dragging);
    }

    /**
     * 开发用（自动截图）：直接设置视口，等同于玩家缩放 / 拖动之后的状态（K 线数据重新到达时也回到它）；
     * {@code edgeSide} 非 0 时把到头反馈固定在画面上（1 = 最旧一根，-1 = 最新一根）。
     */
    void devSetView(ChartViewport view, int edgeSide) {
        this.home = view;
        this.viewport = view;
        this.edgeSide = edgeSide;
        this.edgeHold = edgeSide != 0;
    }

    /** 换了 K 线数据就复位视口；追加了新 K 线时，回看着历史的视口跟着挪，让正在看的那几根不动。 */
    private void sync(List<Candle> candles) {
        if (candles != trackedCandles) {
            viewport = home;
        } else if (viewport.offset() > 0) {
            long lastStart = candles.get(candles.size() - 1).start();
            if (lastStart != trackedLastStart) {
                int previous = indexOfStart(candles, trackedLastStart);
                viewport = previous < 0 ? ChartViewport.latest()
                        : new ChartViewport(viewport.slotWidth(), viewport.offset() + candles.size() - 1 - previous);
            }
        }
        trackedCandles = candles;
        trackedLastStart = candles.get(candles.size() - 1).start();
    }

    private static int indexOfStart(List<Candle> candles, long start) {
        int low = 0;
        int high = candles.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            long value = candles.get(middle).start();
            if (value == start) {
                return middle;
            }
            if (value < start) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return -1;
    }

    /**
     * @param left   图表区域左边界（含图例和价格轴）
     * @param candles 从旧到新，不能为空，最后一根是正在走的
     */
    void extract(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, CandlePeriod period,
            List<Candle> candles, int mouseX, int mouseY, float partialTick) {
        plotValid = false;
        legendHits.clear();
        IndicatorSettings settings = ClientConfig.indicators();
        int legendHeight = font.lineHeight + 2;
        int timeAxisTop = bottom - font.lineHeight - 2;
        int mainTop = top + legendHeight * 2 + 2;
        // 成交量副图隐藏后，主图占满它的位置
        boolean showVolume = settings.volumeVisible();
        int gap = showVolume ? VOLUME_GAP : 0;
        int available = timeAxisTop - 2 - mainTop - gap;
        int volumeHeight = showVolume ? Math.max(MIN_VOLUME_HEIGHT, available / 5) : 0;
        int mainHeight = available - volumeHeight;
        if (mainHeight < 16) {
            return;
        }
        int volumeTop = mainTop + mainHeight + gap;
        int volumeBottom = volumeTop + volumeHeight;
        double widest = 0;
        for (Candle candle : candles) {
            widest = Math.max(widest, candle.high());
        }
        int axisLeft = right - Math.max(MIN_AXIS_WIDTH, font.width(MarketFormat.price(widest)) + 6);
        int plotRight = axisLeft - 4;
        if (plotRight - left < 1) {
            return; // 窗口窄到绘图区没有宽度
        }

        sync(candles);
        viewport = viewport.clamped(candles.size(), plotRight - left);
        ChartViewport.Projection view = viewport.project(left, plotRight, candles.size());
        int first = view.firstVisible();
        int last = view.lastVisible();
        if (last < first) {
            return;
        }
        this.plotLeft = left;
        this.plotRight = plotRight;
        this.plotTop = mainTop;
        this.plotBottom = volumeBottom;
        this.plotValid = true;

        // 只计算显示着的均线：隐藏的既不画，也不参与价格轴范围
        List<IndicatorSettings.MaLine> maLines = settings.lines();
        double[][] ma = new double[maLines.size()][];
        List<double[]> shownLines = new ArrayList<>();
        for (int k = 0; k < ma.length; k++) {
            if (maLines.get(k).visible()) {
                ma[k] = Indicators.movingAverage(candles, maLines.get(k).period());
                shownLines.add(ma[k]);
            }
        }

        double maxVolume = 0;
        for (int i = first; i <= last; i++) {
            maxVolume = Math.max(maxVolume, candles.get(i).volume());
        }
        ChartType type = ClientConfig.chartType();
        PriceScale scale = PriceScale.fit(mainTop, mainHeight, candles, first, last, type == ChartType.LINE, shownLines);

        int lastPriceY = lastPriceLabelY(scale, scale.y(candles.get(candles.size() - 1).close()));
        // 本人的买卖点、持仓线、强平价与当前委托（只看当前合约）；价格轴刻度避开它们在轴上的标记
        String symbol = ClientMarket.selectedSymbol().orElse(null);
        List<ChartOverlay.Line> overlayLines = symbol == null ? List.of() : overlay.lines(symbol);
        List<Integer> axisTops = overlay.axisMarkTops(overlayLines, scale, lastPriceY);
        List<Integer> markTops = axisTops.subList(0, overlayLines.size());
        lastPriceY = axisTops.get(overlayLines.size()) + overlay.axisMarkHeight() / 2; // 最新价格标签与价格标记一起错开
        extractGrid(graphics, left, plotRight, axisLeft, right, mainTop, mainHeight, showVolume, volumeTop, volumeBottom, scale, lastPriceY,
                markTops, overlay.axisMarkHeight());
        extractTimeAxis(graphics, period, candles, left, view, right, mainTop, volumeBottom, timeAxisTop);

        // 缩放 / 平移时最左、最右的 K 线只露出一部分：裁在绘图区之内
        graphics.enableScissor(left, mainTop - 1, plotRight, volumeBottom + 1);
        int bodyWidth = Math.max(1, (int) Math.round(view.slotWidth() * BODY / SLOT));
        if (type == ChartType.LINE) {
            extractLine(graphics, candles, first, last, view, scale, mainTop + mainHeight);
        }
        for (int i = first; i <= last; i++) {
            Candle candle = candles.get(i);
            int color = directionColor(candle);
            int wickX = (int) Math.floor(view.centerX(i));
            int bodyLeft = wickX - bodyWidth / 2;
            if (type == ChartType.CANDLES) {
                graphics.fill(wickX, scale.y(candle.high()), wickX + 1, scale.y(candle.low()) + 1, color);
                int bodyTop = scale.y(Math.max(candle.open(), candle.close()));
                int bodyBottom = Math.max(bodyTop + 1, scale.y(Math.min(candle.open(), candle.close())) + 1);
                graphics.fill(bodyLeft, bodyTop, bodyLeft + bodyWidth, bodyBottom, color);
            }
            if (showVolume && maxVolume > 0) {
                int barHeight = Math.max(1, (int) Math.round(candle.volume() / maxVolume * (volumeHeight - 1)));
                graphics.fill(bodyLeft, volumeBottom - barHeight, bodyLeft + bodyWidth, volumeBottom,
                        (color & 0x00FFFFFF) | VOLUME_ALPHA);
            }
        }
        for (int k = 0; k < ma.length; k++) {
            if (ma[k] != null) {
                extractAverage(graphics, ma[k], first, last, view, scale, maLines.get(k).color());
            }
        }
        if (symbol != null) {
            overlay.extractMarkers(graphics, symbol, candles, period.seconds(), view, scale);
            overlay.extractLines(graphics, overlayLines, left, plotRight, scale);
        }
        graphics.disableScissor();
        if (showVolume) {
            graphics.text(font, MarketFormat.volume(maxVolume), axisLeft + 2, volumeTop + 1, TradingPalette.TEXT_SECONDARY, false);
        }
        overlay.extractTags(graphics, overlayLines, left, plotRight, scale, mouseX, mouseY);
        overlay.extractAxisMarks(graphics, overlayLines, markTops, axisLeft, right);
        extractEdgeFeedback(graphics, left, plotRight, mainTop, mainHeight);
        if (dragging) {
            graphics.requestCursor(CursorTypes.RESIZE_EW);
        }

        // 十字线先画，最新价格标签后画：两个价格标签重叠时，最新价格盖在上面。
        // 鼠标在没有 K 线的位置（最新一根右侧的留白）也画十字线，竖线吸附到那里的 K 线槽位，时间按周期外推
        int hovered = -1;
        boolean crosshair = !isDragging() && !panel.contains(mouseX, mouseY) && mouseX >= left && mouseX < plotRight
                && mouseY >= mainTop && mouseY < volumeBottom;
        if (crosshair) {
            int slot = (int) Math.floor(view.indexAt(mouseX));
            hovered = slot >= 0 && slot < candles.size() ? slot : -1;
            int centerX = (int) Math.floor(view.centerX(slot));
            extractCrosshair(graphics, left, plotRight, axisLeft, right, mainTop, mainHeight, volumeBottom, centerX, mouseY, scale);
            long start = hovered >= 0 ? candles.get(hovered).start()
                    : candles.get(candles.size() - 1).start() + (long) (slot - (candles.size() - 1)) * period.seconds();
            String time = MarketFormat.marketTime(new MarketTime(start));
            int timeWidth = font.width(time) + 6;
            int timeLeft = Math.max(left, Math.min(centerX - timeWidth / 2, plotRight - timeWidth));
            graphics.fill(timeLeft, timeAxisTop, timeLeft + timeWidth, bottom, TradingPalette.DIVIDER);
            graphics.text(font, time, timeLeft + 3, timeAxisTop + 1, TradingPalette.TEXT_PRIMARY, false);
        }
        extractLastPrice(graphics, candles.get(candles.size() - 1), left, plotRight, axisLeft, right, scale, lastPriceY);
        extractLegend(graphics, settings, left, top, right, legendHeight, candles, ma, hovered < 0 ? last : hovered, mouseX, mouseY);
        panel.extract(graphics, left, top, right, mouseX, mouseY, partialTick);
    }

    /** 拖到缓存边缘的反馈：那一侧泛一层渐隐的黄光，并提示没有更多了。 */
    private void extractEdgeFeedback(GuiGraphicsExtractor graphics, int left, int plotRight, int mainTop, int mainHeight) {
        long age = edgeHold ? EDGE_FEEDBACK_MILLIS / 4 : System.currentTimeMillis() - edgeMillis;
        if (age < 0 || age >= EDGE_FEEDBACK_MILLIS) {
            return;
        }
        int alpha = (int) (0x80 * (1 - (double) age / EDGE_FEEDBACK_MILLIS));
        int bottom = mainTop + mainHeight;
        Component text = Component.translatable(edgeSide > 0 ? "screen.creepercapital.trading.kline.edge_oldest"
                : "screen.creepercapital.trading.kline.edge_latest");
        int textWidth = font.width(text) + 8;
        int textLeft;
        // 横向渐隐：一列一列填，越靠近边缘越亮
        for (int column = 0; column < EDGE_GLOW_WIDTH; column++) {
            int color = TradingPalette.withAlpha(TradingPalette.BRAND_YELLOW, alpha * (EDGE_GLOW_WIDTH - column) / EDGE_GLOW_WIDTH);
            int x = edgeSide > 0 ? left + column : plotRight - 1 - column;
            graphics.fill(x, mainTop, x + 1, bottom, color);
        }
        if (edgeSide > 0) {
            textLeft = left + EDGE_GLOW_WIDTH;
        } else {
            textLeft = plotRight - EDGE_GLOW_WIDTH - textWidth;
        }
        graphics.fill(textLeft, mainTop + 3, textLeft + textWidth, mainTop + 5 + font.lineHeight, TradingPalette.DIVIDER);
        graphics.text(font, text, textLeft + 4, mainTop + 4, TradingPalette.BRAND_YELLOW, false);
    }

    /** 网格线与价格轴刻度：刻度文字不出主图区域，离最新价格标签太近、与持仓 / 委托的价格标记重叠的刻度不画（免得叠在一起）。 */
    private void extractGrid(GuiGraphicsExtractor graphics, int left, int plotRight, int axisLeft, int right,
            int mainTop, int mainHeight, boolean showVolume, int volumeTop, int volumeBottom, PriceScale scale, int lastPriceY,
            List<Integer> markTops, int markHeight) {
        grid:
        for (int line = 0; line <= GRID_LINES; line++) {
            int y = mainTop + line * (mainHeight - 1) / GRID_LINES;
            graphics.horizontalLine(left, plotRight, y, TradingPalette.GRID);
            int labelTop = Math.max(mainTop, Math.min(y - font.lineHeight / 2, mainTop + mainHeight - font.lineHeight));
            if (Math.abs(labelTop + font.lineHeight / 2 - lastPriceY) <= font.lineHeight) {
                continue;
            }
            for (int markTop : markTops) {
                if (labelTop < markTop + markHeight && markTop < labelTop + font.lineHeight) {
                    continue grid; // 与持仓 / 委托的价格标记重叠
                }
            }
            graphics.text(font, MarketFormat.price(scale.priceAt(y)), axisLeft + 2, labelTop, TradingPalette.TEXT_SECONDARY, false);
        }
        if (showVolume) {
            graphics.horizontalLine(left, plotRight, volumeTop - 1, TradingPalette.GRID);
        }
        graphics.horizontalLine(left, plotRight, volumeBottom, TradingPalette.GRID);
    }

    /**
     * 时间轴：在固定间隔的 K 线下标注市场时间，并画一条竖向网格线。间隔按绝对序号对齐（平移时标注不会跟着滚动），
     * 缩放后 K 线挤得太密时间隔成倍加大，让相邻标注至少隔一个标注宽度。
     */
    private void extractTimeAxis(GuiGraphicsExtractor graphics, CandlePeriod period, List<Candle> candles, int left,
            ChartViewport.Projection view, int right, int mainTop, int volumeBottom, int timeAxisTop) {
        int first = view.firstVisible();
        int last = view.lastVisible();
        int labelEvery = labelEvery(period);
        int labelWidth = font.width(timeLabel(period, candles.get(first).start())) + 10;
        while (labelEvery * view.slotWidth() < labelWidth) {
            labelEvery *= 2;
        }
        int lastLabelEnd = Integer.MIN_VALUE;
        for (int i = first; i <= last; i++) {
            long start = candles.get(i).start();
            if (Math.floorMod(Math.floorDiv(start, period.seconds()), labelEvery) != 0) {
                continue;
            }
            int centerX = (int) Math.floor(view.centerX(i));
            String label = timeLabel(period, start);
            int width = font.width(label);
            int labelX = centerX - width / 2;
            if (labelX < Math.max(left, lastLabelEnd + 4) || labelX + width > right) {
                continue;
            }
            graphics.verticalLine(centerX, mainTop, volumeBottom, TradingPalette.GRID);
            graphics.text(font, label, labelX, timeAxisTop + 1, TradingPalette.TEXT_SECONDARY, false);
            lastLabelEnd = labelX + width;
        }
    }

    /** 一根线上相邻两点之间的某一列：横坐标和这一列线条占的竖向像素区间 {@code [top, bottom]}（含）。 */
    @FunctionalInterface
    private interface ColumnSink {
        void column(int x, int top, int bottom);
    }

    /**
     * 折线：相邻两点之间按列补满竖向像素，线才是连续的；两端多取一根，露出一部分的 K 线处线也不断；NaN 点断开。
     */
    private static void traceColumns(double[] values, int first, int last, ChartViewport.Projection view, PriceScale scale,
            ColumnSink sink) {
        int previousX = 0;
        int previousY = 0;
        boolean hasPrevious = false;
        for (int i = Math.max(0, first - 1); i <= Math.min(values.length - 1, last + 1); i++) {
            double value = values[i];
            if (Double.isNaN(value)) {
                hasPrevious = false;
                continue;
            }
            int x = (int) Math.floor(view.centerX(i));
            int y = scale.y(value);
            if (hasPrevious && x > previousX) {
                for (int column = previousX; column < x; column++) {
                    int from = previousY + Math.round((float) (y - previousY) * (column - previousX) / (x - previousX));
                    int to = previousY + Math.round((float) (y - previousY) * (column + 1 - previousX) / (x - previousX));
                    sink.column(column, Math.min(from, to), Math.max(from, to));
                }
            }
            previousX = x;
            previousY = y;
            hasPrevious = true;
        }
    }

    private static void extractAverage(GuiGraphicsExtractor graphics, double[] average, int first, int last,
            ChartViewport.Projection view, PriceScale scale, int color) {
        traceColumns(average, first, last, view, scale, (x, top, bottom) -> graphics.fill(x, top, x + 1, bottom + 1, color));
    }

    /** 线形图：收盘价连成 2 像素粗的线，线下是自上而下渐隐的面积填充；线色随可见区间的涨跌（{@link ChartTrend}）。 */
    private static void extractLine(GuiGraphicsExtractor graphics, List<Candle> candles, int first, int last,
            ChartViewport.Projection view, PriceScale scale, int mainBottom) {
        double[] closes = new double[candles.size()];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = candles.get(i).close();
        }
        int color = ChartType.lineColor(candles, first, last);
        int mainTop = scale.top();
        traceColumns(closes, first, last, view, scale, (x, top, bottom) -> {
            // 面积填充：透明度只由纵坐标决定（越靠上越浓，到主图区下沿为 0），相邻列才衔接得上
            int alpha = (int) (AREA_ALPHA * (mainBottom - top) / (double) Math.max(1, mainBottom - mainTop));
            if (bottom + 2 < mainBottom) {
                graphics.fillGradient(x, bottom + 2, x + 1, mainBottom, TradingPalette.withAlpha(color, Math.max(0, alpha)),
                        TradingPalette.withAlpha(color, 0));
            }
            graphics.fill(x, top, x + 1, bottom + 2, color);
        });
    }

    /**
     * 最新价格线：虚线横穿图表，价格轴上用涨跌色底标出价格。平移后最新价格可能落在可见价格范围之外，
     * 这时不画虚线，标签贴在主图区的上沿 / 下沿，免得压到图例或成交量。标签与持仓 / 委托的价格标记一起错开，
     * 中心在 {@code labelY}（挤在一起时可能离虚线几个像素）。
     */
    private void extractLastPrice(GuiGraphicsExtractor graphics, Candle latest, int left, int plotRight, int axisLeft,
            int right, PriceScale scale, int labelY) {
        int color = directionColor(latest);
        int y = scale.y(latest.close());
        if (lastPriceLabelY(scale, y) == y) {
            for (int x = left; x < plotRight; x += 6) {
                graphics.horizontalLine(x, Math.min(x + 2, plotRight), y, color);
            }
        }
        graphics.fill(axisLeft, labelY - font.lineHeight / 2 - 1, right, labelY + font.lineHeight / 2 + 1, color);
        graphics.text(font, MarketFormat.price(latest.close()), axisLeft + 2, labelY - font.lineHeight / 2,
                TradingPalette.TEXT_PRIMARY, false);
    }

    /** 最新价格标签的纵坐标：在主图区之内原样返回，之外贴到上沿 / 下沿（标签整块都在主图区内）。 */
    private int lastPriceLabelY(PriceScale scale, int y) {
        int half = font.lineHeight / 2 + 1;
        return Math.max(scale.top() + half, Math.min(y, scale.top() + scale.height() - 1 - half));
    }

    private void extractCrosshair(GuiGraphicsExtractor graphics, int left, int plotRight, int axisLeft, int right,
            int mainTop, int mainHeight, int volumeBottom, int centerX, int mouseY, PriceScale scale) {
        graphics.verticalLine(centerX, mainTop, volumeBottom - 1, TradingPalette.CROSSHAIR);
        if (mouseY < mainTop + mainHeight) {
            graphics.horizontalLine(left, plotRight, mouseY, TradingPalette.CROSSHAIR);
            graphics.fill(axisLeft, mouseY - font.lineHeight / 2 - 1, right, mouseY + font.lineHeight / 2 + 1,
                    TradingPalette.DIVIDER);
            graphics.text(font, MarketFormat.price(scale.priceAt(mouseY)), axisLeft + 2, mouseY - font.lineHeight / 2,
                    TradingPalette.TEXT_PRIMARY, false);
        }
    }

    /**
     * 图例两行：第一行是选中那根的开高低收（时间标在时间轴上）；第二行是齿轮、各条均线在那一根的值和成交量，
     * 均线与成交量都能点击切换显示，隐藏后整项变灰、不再显示数值。逐项往右排，排到放不下的项就停（不越出图表右边界）。
     */
    private void extractLegend(GuiGraphicsExtractor graphics, IndicatorSettings settings, int left, int top, int right,
            int lineHeight, List<Candle> candles, double[][] ma, int index, int mouseX, int mouseY) {
        Candle candle = candles.get(index);
        int color = directionColor(candle);
        int y = top + 1;
        int x = left;
        x = extractLegendValue(graphics, x, y, right, "O", MarketFormat.price(candle.open()), color);
        x = extractLegendValue(graphics, x, y, right, "H", MarketFormat.price(candle.high()), color);
        x = extractLegendValue(graphics, x, y, right, "L", MarketFormat.price(candle.low()), color);
        extractLegendValue(graphics, x, y, right, "C", MarketFormat.price(candle.close()), color);

        y += lineHeight;
        x = left;
        // 齿轮：展开 / 收起指标设置浮层
        boolean hoveredGear = mouseX >= x && mouseX < x + GEAR_SIZE + 2 && mouseY >= y - 1 && mouseY < y + lineHeight - 1;
        extractGear(graphics, x, y, panel.isOpen() ? TradingPalette.BRAND_YELLOW
                : hoveredGear ? TradingPalette.TEXT_PRIMARY : TradingPalette.TEXT_SECONDARY);
        legendHits.add(new LegendHit(x - 1, y - 1, x + GEAR_SIZE + 3, y + lineHeight - 1, panel::toggle));
        if (hoveredGear) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        x += GEAR_SIZE + LEGEND_GAP;

        List<IndicatorSettings.MaLine> lines = settings.lines();
        for (int k = 0; k < lines.size(); k++) {
            IndicatorSettings.MaLine line = lines.get(k);
            String text = "MA(" + line.period() + ")";
            if (line.visible()) {
                double value = ma[k][index];
                text += " " + (Double.isNaN(value) ? "--" : MarketFormat.price(value));
            }
            int toggled = k;
            x = extractLegendToggle(graphics, x, y, right, lineHeight, text, line.visible() ? line.color() : TradingPalette.DISABLED,
                    mouseX, mouseY, () -> ClientConfig.setIndicators(ClientConfig.indicators().toggledLine(toggled)));
        }
        String volume = Component.translatable("screen.creepercapital.trading.kline.volume").getString();
        if (settings.volumeVisible()) {
            volume += " " + MarketFormat.volume(candle.volume());
        }
        extractLegendToggle(graphics, x, y, right, lineHeight, volume,
                settings.volumeVisible() ? color : TradingPalette.DISABLED, mouseX, mouseY,
                () -> ClientConfig.setIndicators(ClientConfig.indicators().toggledVolume()));
    }

    /** 图例里可点击的一项：放不下（超出右边界）不画、不可点；悬停时加下划线并显示手形光标。返回下一项的起点。 */
    private int extractLegendToggle(GuiGraphicsExtractor graphics, int x, int y, int right, int lineHeight, String text, int color,
            int mouseX, int mouseY, Runnable toggle) {
        int width = font.width(text);
        if (x + width > right) {
            return Integer.MAX_VALUE / 2;
        }
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y - 1 && mouseY < y + lineHeight - 1;
        graphics.text(font, text, x, y, color, false);
        if (hovered) {
            graphics.horizontalLine(x, x + width - 1, y + font.lineHeight, color);
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        legendHits.add(new LegendHit(x - 1, y - 1, x + width + 1, y + lineHeight - 1, toggle));
        return x + width + LEGEND_GAP;
    }

    /** 齿轮图标的边长。 */
    private static final int GEAR_SIZE = 9;

    /** 画一个 9x9 的齿轮（环 + 中空 + 八个齿），左上角在 {@code (x, y)}；图例和浮层标题共用。 */
    static void extractGear(GuiGraphicsExtractor graphics, int x, int y, int color) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int distance = dx * dx + dy * dy;
                boolean ring = distance >= 4 && distance <= 10;
                boolean tooth = (Math.abs(dx) == 4 && dy == 0) || (dx == 0 && Math.abs(dy) == 4)
                        || (Math.abs(dx) == 3 && Math.abs(dy) == 3);
                if (ring || tooth) {
                    graphics.fill(x + 4 + dx, y + 4 + dy, x + 5 + dx, y + 5 + dy, color);
                }
            }
        }
    }

    /** 图例里的一项「名称 数值」；放不下时不画，并返回超出右边界的位置，让后面的项也都不画。 */
    private int extractLegendValue(GuiGraphicsExtractor graphics, int x, int y, int right, String label, String value, int color) {
        int valueX = x + font.width(label) + 2;
        int end = valueX + font.width(value);
        if (end > right) {
            return Integer.MAX_VALUE / 2;
        }
        graphics.text(font, label, x, y, TradingPalette.TEXT_SECONDARY, false);
        graphics.text(font, value, valueX, y, color, false);
        return end + LEGEND_GAP;
    }

    /** 收盘不低于开盘为涨，否则为跌（颜色按涨跌色方案）；正在走的那根按当前价格相对开盘算。 */
    private static int directionColor(Candle candle) {
        return candle.close() >= candle.open() ? TradingPalette.up() : TradingPalette.down();
    }

    /** 时间轴上每隔多少根 K 线标一次，按绝对序号对齐，所以新 K 线出现时标注不会跟着滚动。 */
    private static int labelEvery(CandlePeriod period) {
        return switch (period) {
            case M1 -> 15;
            case M5 -> 12;
            case M15 -> 16;
            case H1 -> 12;
            case H4 -> 12;
            case D1 -> 14;
            case W1 -> 8;
        };
    }

    private static String timeLabel(CandlePeriod period, long start) {
        MarketTime time = new MarketTime(start);
        if (period.seconds() >= CandlePeriod.D1.seconds()) {
            return Component.translatable("screen.creepercapital.trading.kline.axis_day", time.day()).getString();
        }
        return Component.translatable("screen.creepercapital.trading.kline.axis_time", time.day(),
                String.format(Locale.ROOT, "%02d:%02d", time.hour(), time.minute())).getString();
    }
}
