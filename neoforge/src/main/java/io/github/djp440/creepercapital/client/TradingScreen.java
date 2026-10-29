package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 交易界面（深色主题）：深色底上一块块卡片——标题栏、行情栏，下面左侧是 K 线图卡片（右边并排盘口栏 {@link MarketColumn}）
 * 和底部表格区（资产、仓位、当前委托、历史委托、成交历史、资金费记录六页），右侧是通栏的下单面板（{@link OrderPanel}）。
 * 各卡片的位置由 {@link TradingLayout} 按屏幕尺寸算出，屏幕小时按那里写明的顺序降级（省掉标题栏、盘口栏并进图表页签等）；
 * 每张卡片的内容都裁在卡片范围内，不会画到别的卡片上。
 * <p>
 * 行情栏左侧是当前合约的交易代码，市场里有多个合约时带下拉箭头，点开列表可以切换合约（各合约的行情、K 线互相独立）；
 * 其后是最新价格、24h 涨跌、标记价格、指数价格、资金费率与结算倒计时、24h 最高 / 最低 / 成交量与市场时间，放不下时折行，
 * 行数到上限后按优先级丢掉次要数据。行情栏右端是涨跌色切换按钮（「绿涨红跌」/「红涨绿跌」），点一下立即生效并写回客户端配置。
 * 界面每帧读取客户端缓存的行情，服务端每个行情步同步一次，所以数值每个行情步刷新一次。
 * K 线图见 {@link KlineChart}，周期切换后向服务端拉取该周期的历史，之后随行情步增量更新。
 */
public final class TradingScreen extends Screen {
    /** 卡片的内边距。 */
    private static final int PAD = 8;
    private static final int STAT_GAP = 16;
    private static final int LINE_GAP = 2;
    /** 图表卡片顶部的页签行（K 线周期；盘口栏并进来时还有 K 线 / 盘口 / 最新成交）。 */
    private static final int TAB_HEIGHT = 14;
    private static final int TABLE_TAB_HEIGHT = 16;
    private static final int MENU_ROW_HEIGHT = 14;
    private static final int MENU_PADDING = 8;
    /** 交易代码与下拉箭头的间距；箭头是 4 行逐行收窄的小三角。 */
    private static final int CARET_GAP = 5;
    private static final int CARET_HALF_WIDTH = 3;
    /** 涨跌色切换按钮：涨色块、跌色块加方案名。 */
    private static final int SWATCH = 5;
    private static final int TOGGLE_HEIGHT = 14;
    private static final int TOGGLE_PADDING = 5;

    /** 底部表格区的页签：全名和窄屏用的短名。 */
    private enum TableTab {
        ASSETS("screen.creepercapital.trading.table.assets", "screen.creepercapital.trading.table.assets.short"),
        POSITIONS("screen.creepercapital.trading.table.positions", "screen.creepercapital.trading.table.positions.short"),
        OPEN_ORDERS("screen.creepercapital.trading.table.open_orders", "screen.creepercapital.trading.table.open_orders.short"),
        ORDER_HISTORY("screen.creepercapital.trading.table.order_history", "screen.creepercapital.trading.table.order_history.short"),
        TRADES("screen.creepercapital.trading.table.trades", "screen.creepercapital.trading.table.trades.short"),
        FUNDING("screen.creepercapital.trading.table.funding", "screen.creepercapital.trading.table.funding.short");

        private final String key;
        private final String shortKey;

        TableTab(String key, String shortKey) {
            this.key = key;
            this.shortKey = shortKey;
        }

        Component label(boolean shortLabel) {
            return Component.translatable(shortLabel ? shortKey : key);
        }
    }

    /** 一个可点击的区域（页签、切换按钮等），每帧渲染时重新登记。 */
    private record Hit(int left, int top, int right, int bottom, Runnable action) {
        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    /** 一项「名称在上、数值在下」的数据；{@code priority} 越大越晚被丢掉。 */
    private record Stat(Component label, String value, int valueColor, int priority) {
    }

    /** 行情栏的排版：每项的位置、实际行数，以及交易代码块和切换按钮的宽度。 */
    private record TickerPlan(List<Stat> stats, List<LayoutFit.Slot> slots, int rows, int symbolWidth, int toggleWidth) {
    }

    private final KlineChart chart;
    private final MarketColumn marketColumn;
    private final OrderPanel orderPanel;
    private final TradeTables tables;
    private TableTab tableTab = TableTab.ASSETS;
    /** 盘口栏并进图表卡片时，图表卡片正显示盘口栏的页（而不是 K 线）。 */
    private boolean chartShowsColumn;
    /** 上一帧的布局，滚轮判定要用。 */
    private TradingLayout.Layout layout;
    /** 上一帧行情栏用了几行（拖拽时重算布局要用）。 */
    private int tickerRows = 1;
    /** 正在被拖拽的边框（鼠标按下到松开之间）；没有拖拽时为空。 */
    private TradingLayout.Seam draggingSeam;
    /** 拖拽期间的区域比例（松开才写回客户端配置，免得每帧写文件）；没有拖拽时为空。 */
    private TradingLayout.Ratios dragRatios;
    private final List<Hit> hits = new ArrayList<>();
    /** 交易代码按钮（点开合约列表）的区域；每帧渲染时更新。 */
    private TradingLayout.Rect symbolButton = TradingLayout.Rect.EMPTY;
    private boolean contractMenuOpen;

    private TradingScreen() {
        super(Component.translatable("screen.creepercapital.trading.title"));
        this.chart = new KlineChart(Minecraft.getInstance().font);
        this.marketColumn = new MarketColumn(Minecraft.getInstance().font);
        this.orderPanel = new OrderPanel(Minecraft.getInstance().font);
        this.tables = new TradeTables(Minecraft.getInstance().font);
    }

    /** 开发用（自动截图）：打开交易界面，切到某个表格页，并在下单面板里填好数量。 */
    public static void openForTrading(String tab, String quantity) {
        open();
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.tableTab = TableTab.valueOf(tab);
            screen.orderPanel.setQuantity(quantity);
        }
    }

    /** 开发用（自动截图）：打开交易界面，下单面板切到限价并填好委托价和数量，底部表格切到某页。 */
    public static void openForLimitOrder(String tab, String quantity, String price) {
        openForTrading(tab, quantity);
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.orderPanel.setLimit(price);
        }
    }

    /** 开发用（自动截图）：下单面板切到止盈止损页并填好止盈价、止损价（可选限价模式），底部表格切到某页。 */
    public static void openForProtection(String tab, String quantity, String takeProfit, String stopLoss) {
        openForTrading(tab, quantity);
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.orderPanel.setProtection(takeProfit, stopLoss);
        }
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setScreen(new TradingScreen());
        }
    }

    /** 开发用（自动截图）：打开交易界面，盘口栏切到「最新成交」页（盘口栏并进图表卡片时，图表卡片也切到这一页）。 */
    public static void openWithLatestTrades() {
        open();
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.marketColumn.showLatestTrades();
            screen.chartShowsColumn = true;
        }
    }

    /** 开发用（自动截图）：打开交易界面，盘口栏在「盘口」页（盘口栏并进图表卡片时，图表卡片也切到这一页）。 */
    public static void openWithOrderBook() {
        open();
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.showColumnPage(MarketColumn.Page.ORDER_BOOK);
        }
    }

    /** 开发用（自动截图）：打开交易界面，图表带着指定的缩放（每根 K 线的像素宽）和平移（往回看多少根）；{@code edgeSide} 非 0 时显示到头反馈。 */
    public static void openWithChartView(double slotWidth, double offset, int edgeSide) {
        open();
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.chart.devSetView(new ChartViewport(slotWidth, offset), edgeSide);
        }
    }

    /** 开发用（自动截图）：打开交易界面并展开图表的指标设置浮层，可同时展开某一行的色盘、在某一行输入框里填一段文字。 */
    public static void openWithIndicatorSettings(int paletteRow, int typedRow, String typedText) {
        open();
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.chart.devOpenSettings(paletteRow, typedRow, typedText);
        }
    }

    /** 开发用（自动截图）：打开交易界面，图表上从持仓线拖出一条触发价 = 最新价格 × {@code factor} 的止盈止损线（{@code dragging} 为 false 时是松开后的 ✓ / ✕ 确认态）。 */
    public static void openWithChartEdit(double factor, boolean dragging) {
        open();
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.chart.devEdit(factor, dragging);
        }
    }

    /** 开发用（自动截图）：打开交易界面并展开合约下拉列表。 */
    public static void openWithContractMenu() {
        open();
        if (Minecraft.getInstance().screen instanceof TradingScreen screen) {
            screen.contractMenuOpen = true;
        }
    }

    @Override
    protected void init() {
        ClientKlines.open();
        // 输入框只登记为子部件（接收键盘和点击），不放进可渲染列表：它要画在下单面板的底色之上，由面板自己渲染
        addWidget(orderPanel.quantityBox());
        addWidget(orderPanel.priceBox());
        addWidget(orderPanel.takeProfitBox());
        addWidget(orderPanel.stopLossBox());
        // 图表指标设置浮层里的周期输入框：同样由浮层自己渲染
        chart.inputs().forEach(this::addWidget);
    }

    /** 单人游戏里打开交易界面不暂停游戏，市场照常推进。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 再按一次打开界面的快捷键就关闭界面。 */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (contractMenuOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            contractMenuOpen = false;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && chart.cancelEdit()) {
            return true; // Esc 先取消图上正在拖 / 等确认的止盈止损，不关界面
        }
        if (chart.settingsOpen() && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            chart.closeSettings();
            setFocused(null);
            return true;
        }
        if ((orderPanel.anyInputFocused() || chart.anyInputFocused()) && event.key() != GLFW.GLFW_KEY_ESCAPE) {
            // 在数量 / 委托价 / 止盈止损价 / 均线周期输入框里打字时，快捷键字母不能关掉界面
            return super.keyPressed(event);
        }
        if (CreeperCapitalClient.OPEN_TRADING_KEY.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (chart.mouseScrolled(x, y, scrollY)) {
            return true;
        }
        if (layout != null && layout.table().contains(x, y)) {
            tables.mouseScrolled(scrollY);
            return true;
        }
        return marketColumn.mouseScrolled(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && clickedContractControls(event.x(), event.y())) {
            clearInputFocus();
            return true;
        }
        if (event.button() == 0 && layout != null) {
            TradingLayout.Seam seam = TradingLayout.seamAt(layout, event.x(), event.y());
            if (seam != null) {
                draggingSeam = seam;
                dragRatios = layoutRatios();
                return true;
            }
        }
        EditBox chartInput = chart.mouseClickedInput(event, doubleClick);
        if (chartInput != null) {
            setFocused(null);
            setFocused(chartInput);
            setDragging(true);
            return true;
        }
        if (chart.mouseClicked(event.x(), event.y(), event.button(), doubleClick)) {
            clearInputFocus();
            return true;
        }
        if (event.button() == 0) {
            // 输入框按「画出来的区域」判定点击，且先于各处登记的点击区域，不会被它们抢走（#39）
            EditBox input = orderPanel.mouseClickedInput(event, doubleClick);
            if (input != null) {
                // 先把界面记着的焦点清掉：别处点击清焦点时若只清了输入框自己的标记，界面仍记着它，
                // 再点同一个输入框时 setFocused 会因为「焦点没变」而不重新聚焦
                setFocused(null);
                setFocused(input);
                setDragging(true);
                return true;
            }
            String symbol = ClientMarket.selectedSymbol().orElse(null);
            if (symbol != null && orderPanel.mouseClicked(event.x(), event.y(), symbol)) {
                clearInputFocus();
                return true;
            }
            for (Hit hit : List.copyOf(hits)) {
                if (hit.contains(event.x(), event.y())) {
                    hit.action().run();
                    return true;
                }
            }
            if (marketColumn.mouseClicked(event.x(), event.y()) || tables.mouseClicked(event.x(), event.y())) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** 拖拽边框：边框中心跟着鼠标，比例受各区域最小尺寸约束。 */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == 0 && chart.isDragging()) {
            return chart.mouseDragged(event.y(), dx);
        }
        if (draggingSeam != null && event.button() == 0) {
            dragRatios = TradingLayout.dragged(width, height, tickerRows, dragRatios, draggingSeam, event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    /** 松开鼠标结束拖拽，把比例写回客户端配置。 */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (chart.mouseReleased(event.button())) {
            return true;
        }
        if (draggingSeam != null && event.button() == 0) {
            TradingLayout.Ratios ratios = dragRatios;
            draggingSeam = null;
            dragRatios = null;
            if (ratios != null) {
                ClientConfig.setLayoutRatios(ratios);
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    /** 清掉所有输入框的焦点，同时清掉界面自己记的焦点子部件，两边保持一致。 */
    private void clearInputFocus() {
        orderPanel.unfocusInputs();
        chart.unfocusInputs();
        setFocused(null);
    }

    /** 点在交易代码按钮上就展开/收起合约列表；列表展开时点列表项切换合约，点别处只收起列表。返回是否已处理这次点击。 */
    private boolean clickedContractControls(double x, double y) {
        List<String> symbols = ClientMarket.symbols();
        if (symbols.size() > 1 && symbolButton.contains(x, y)) {
            contractMenuOpen = !contractMenuOpen;
            return true;
        }
        if (!contractMenuOpen) {
            return false;
        }
        int index = menuIndexAt(symbols, x, y);
        if (index >= 0) {
            ClientMarket.select(symbols.get(index));
        }
        contractMenuOpen = false;
        return true;
    }

    private int menuWidth(List<String> symbols) {
        int widest = 0;
        for (String symbol : symbols) {
            widest = Math.max(widest, font.width(symbol));
        }
        return widest + MENU_PADDING * 2;
    }

    /** 鼠标所在的列表项下标；不在列表里返回 -1。 */
    private int menuIndexAt(List<String> symbols, double x, double y) {
        int left = symbolButton.left();
        int top = symbolButton.bottom();
        if (x < left || x >= left + menuWidth(symbols) || y < top || y >= top + symbols.size() * MENU_ROW_HEIGHT) {
            return -1;
        }
        return (int) ((y - top) / MENU_ROW_HEIGHT);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, TradingPalette.BACKGROUND);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        hits.clear();
        MarketSnapshot snapshot = ClientMarket.latest().orElse(null);
        if (snapshot == null) {
            layout = TradingLayout.compute(width, height, 1, layoutRatios());
            extractHeader(graphics, mouseX, mouseY);
            graphics.centeredText(font, Component.translatable("screen.creepercapital.trading.waiting"), width / 2,
                    GuiText.centeredY(font, 0, height), TradingPalette.TEXT_SECONDARY);
            return;
        }
        TickerPlan ticker = planTicker(snapshot);
        tickerRows = ticker.rows();
        layout = TradingLayout.compute(width, height, tickerRows, layoutRatios());
        extractHeader(graphics, mouseX, mouseY);
        extractTicker(graphics, snapshot, ticker, mouseX, mouseY);
        extractChartCard(graphics, snapshot, mouseX, mouseY, partialTick);
        if (layout.columnMode() == TradingLayout.ColumnMode.SIDE) {
            TradingLayout.Rect column = layout.marketColumn();
            graphics.enableScissor(column.left(), column.top(), column.right(), column.bottom());
            marketColumn.extract(graphics, column, snapshot.symbol(), snapshot, mouseX, mouseY);
            graphics.disableScissor();
        } else {
            marketColumn.forgetTabs();
        }
        TradingLayout.Rect panel = layout.orderPanel();
        graphics.enableScissor(panel.left(), panel.top(), panel.right(), panel.bottom());
        orderPanel.extract(graphics, panel.left(), panel.top(), panel.right(), panel.bottom(), snapshot.symbol(), snapshot,
                mouseX, mouseY, partialTick);
        graphics.disableScissor();
        extractTable(graphics, mouseX, mouseY);
        extractSeams(graphics, mouseX, mouseY);
        if (contractMenuOpen) {
            extractContractMenu(graphics, snapshot.symbol(), mouseX, mouseY);
        }
    }

    /** 当前生效的区域比例：拖拽中用拖拽的值，否则读客户端配置。 */
    private TradingLayout.Ratios layoutRatios() {
        return dragRatios != null ? dragRatios : ClientConfig.layoutRatios();
    }

    /** 可拖拽边框的提示：鼠标悬停（或正在拖拽）时边框高亮，光标变成调整大小的样子。 */
    private void extractSeams(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        TradingLayout.Seam seam = draggingSeam != null ? draggingSeam : TradingLayout.seamAt(layout, mouseX, mouseY);
        if (seam == null) {
            return;
        }
        TradingLayout.Rect strip = TradingLayout.seam(layout, seam);
        if (strip.isEmpty()) {
            return;
        }
        graphics.fill(strip.left(), strip.top(), strip.right(), strip.bottom(),
                draggingSeam != null ? TradingPalette.BRAND_YELLOW : TradingPalette.TEXT_SECONDARY);
        graphics.requestCursor(seam == TradingLayout.Seam.TABLE ? CursorTypes.RESIZE_NS : CursorTypes.RESIZE_EW);
    }

    /** 合约下拉列表：挂在交易代码按钮下方，盖在图表之上，当前合约黄字，鼠标悬停的一项底色变亮。 */
    private void extractContractMenu(GuiGraphicsExtractor graphics, String current, int mouseX, int mouseY) {
        List<String> symbols = ClientMarket.symbols();
        int left = symbolButton.left();
        int top = symbolButton.bottom();
        int right = left + menuWidth(symbols);
        int bottom = top + symbols.size() * MENU_ROW_HEIGHT;
        graphics.fill(left, top, right, bottom, TradingPalette.PANEL);
        graphics.outline(left, top, right - left, bottom - top, TradingPalette.DIVIDER);
        int hovered = menuIndexAt(symbols, mouseX, mouseY);
        for (int i = 0; i < symbols.size(); i++) {
            int rowTop = top + i * MENU_ROW_HEIGHT;
            if (i == hovered) {
                graphics.fill(left + 1, rowTop, right - 1, rowTop + MENU_ROW_HEIGHT, TradingPalette.HOVER);
            }
            String symbol = symbols.get(i);
            int color = symbol.equals(current) ? TradingPalette.BRAND_YELLOW
                    : i == hovered ? TradingPalette.TEXT_PRIMARY : TradingPalette.TEXT_SECONDARY;
            graphics.text(font, symbol, left + MENU_PADDING, GuiText.centeredY(font, rowTop, rowTop + MENU_ROW_HEIGHT), color, false);
        }
    }

    /** 标题栏（矮屏时省掉）：品牌黄的模组名，右端是「恢复默认布局」按钮（布局还是缺省时灰掉）。 */
    private void extractHeader(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        TradingLayout.Rect header = layout.header();
        if (header.isEmpty()) {
            return;
        }
        graphics.fill(header.left(), header.top(), header.right(), header.bottom(), TradingPalette.PANEL);
        graphics.text(font, title, TradingLayout.GAP + PAD, GuiText.centeredY(font, header.top(), header.bottom()),
                TradingPalette.BRAND_YELLOW, false);
        Component label = Component.translatable("screen.creepercapital.trading.reset_layout");
        int buttonWidth = font.width(label) + TOGGLE_PADDING * 2;
        int left = header.right() - TradingLayout.GAP - PAD - buttonWidth;
        boolean enabled = !layoutRatios().isDefault();
        Hit hit = new Hit(left, header.top() + 1, left + buttonWidth, header.bottom() - 1, this::resetLayout);
        graphics.fill(hit.left(), hit.top(), hit.right(), hit.bottom(),
                enabled && hit.contains(mouseX, mouseY) ? TradingPalette.BUTTON_HOVER : TradingPalette.BUTTON);
        graphics.text(font, label, left + TOGGLE_PADDING, GuiText.centeredY(font, hit.top(), hit.bottom()),
                enabled ? TradingPalette.TEXT_PRIMARY : TradingPalette.DISABLED, false);
        if (enabled) {
            hits.add(hit);
        }
    }

    /** 恢复默认布局：清掉保存的区域比例。 */
    private void resetLayout() {
        dragRatios = null;
        draggingSeam = null;
        ClientConfig.setLayoutRatios(TradingLayout.Ratios.DEFAULT);
    }

    /** 行情栏各项的排版：交易代码块之后依次排，放不下折行，超过行数上限时丢掉优先级低的项。 */
    private TickerPlan planTicker(MarketSnapshot snapshot) {
        List<Stat> stats = stats(snapshot);
        int symbolWidth = Math.max(font.width(snapshot.symbol()) + caretSpace(),
                font.width(Component.translatable("screen.creepercapital.trading.perpetual")));
        int toggleWidth = toggleWidth();
        int left = TradingLayout.GAP + PAD;
        int right = width - TradingLayout.GAP - PAD - toggleWidth - STAT_GAP;
        int[] widths = new int[stats.size()];
        int[] priority = new int[stats.size()];
        for (int i = 0; i < stats.size(); i++) {
            widths[i] = Math.max(font.width(stats.get(i).label()), font.width(stats.get(i).value()));
            priority[i] = stats.get(i).priority();
        }
        List<LayoutFit.Slot> slots = LayoutFit.ticker(left + symbolWidth + STAT_GAP, left, right, STAT_GAP, widths, priority,
                TradingLayout.maxTickerRows(height));
        return new TickerPlan(stats, slots, LayoutFit.rowsUsed(slots), symbolWidth, toggleWidth);
    }

    private int caretSpace() {
        return ClientMarket.symbols().size() > 1 ? CARET_GAP + CARET_HALF_WIDTH * 2 + 1 : 0;
    }

    private int toggleWidth() {
        return TOGGLE_PADDING * 2 + SWATCH * 2 + 1 + 4 + font.width(ClientConfig.colorScheme().label());
    }

    /** 行情栏卡片：交易代码（可下拉切换合约）、各项行情数据，右端是涨跌色切换按钮。 */
    private void extractTicker(GuiGraphicsExtractor graphics, MarketSnapshot snapshot, TickerPlan plan, int mouseX, int mouseY) {
        TradingLayout.Rect card = layout.ticker();
        int rowHeight = TradingLayout.tickerRowHeight(height);
        graphics.fill(card.left(), card.top(), card.right(), card.bottom(), TradingPalette.PANEL);
        graphics.enableScissor(card.left(), card.top(), card.right(), card.bottom());
        int left = card.left() + PAD;
        int upperOffset = (rowHeight - font.lineHeight * 2 - LINE_GAP) / 2 + 1;

        boolean switchable = ClientMarket.symbols().size() > 1;
        symbolButton = new TradingLayout.Rect(card.left(), card.top(), left + plan.symbolWidth() + STAT_GAP / 2, card.top() + rowHeight);
        if (switchable && (contractMenuOpen || symbolButton.contains(mouseX, mouseY))) {
            graphics.fill(symbolButton.left(), symbolButton.top(), symbolButton.right(), symbolButton.bottom(), TradingPalette.HOVER);
        }
        int upperY = card.top() + upperOffset;
        graphics.text(font, snapshot.symbol(), left, upperY, TradingPalette.TEXT_PRIMARY, false);
        if (switchable) {
            int caretColor = contractMenuOpen ? TradingPalette.BRAND_YELLOW : TradingPalette.TEXT_SECONDARY;
            int caretCenter = left + font.width(snapshot.symbol()) + CARET_GAP + CARET_HALF_WIDTH;
            for (int line = 0; line <= CARET_HALF_WIDTH; line++) {
                int half = CARET_HALF_WIDTH - line;
                int y = upperY + 2 + line;
                graphics.fill(caretCenter - half, y, caretCenter + half + 1, y + 1, caretColor);
            }
        }
        graphics.text(font, Component.translatable("screen.creepercapital.trading.perpetual"), left, upperY + font.lineHeight + LINE_GAP,
                TradingPalette.TEXT_SECONDARY, false);

        for (int i = 0; i < plan.stats().size(); i++) {
            LayoutFit.Slot slot = plan.slots().get(i);
            if (slot.dropped()) {
                continue;
            }
            Stat stat = plan.stats().get(i);
            int y = card.top() + slot.row() * rowHeight + upperOffset;
            graphics.text(font, stat.label(), slot.x(), y, TradingPalette.TEXT_SECONDARY, false);
            graphics.text(font, stat.value(), slot.x(), y + font.lineHeight + LINE_GAP, stat.valueColor(), false);
        }
        extractSchemeToggle(graphics, card.right() - PAD - plan.toggleWidth(), card.top() + (rowHeight - TOGGLE_HEIGHT) / 2,
                plan.toggleWidth(), mouseX, mouseY);
        graphics.disableScissor();
    }

    /** 涨跌色切换按钮：涨色块、跌色块和当前方案名；点一下换成另一种方案，立即生效并写回客户端配置。 */
    private void extractSchemeToggle(GuiGraphicsExtractor graphics, int left, int top, int toggleWidth, int mouseX, int mouseY) {
        Hit hit = new Hit(left, top, left + toggleWidth, top + TOGGLE_HEIGHT,
                () -> ClientConfig.setColorScheme(ClientConfig.colorScheme().toggled()));
        graphics.fill(hit.left(), hit.top(), hit.right(), hit.bottom(),
                hit.contains(mouseX, mouseY) ? TradingPalette.BUTTON_HOVER : TradingPalette.BUTTON);
        int swatchTop = top + (TOGGLE_HEIGHT - SWATCH) / 2;
        int x = left + TOGGLE_PADDING;
        graphics.fill(x, swatchTop, x + SWATCH, swatchTop + SWATCH, TradingPalette.up());
        graphics.fill(x + SWATCH + 1, swatchTop, x + SWATCH * 2 + 1, swatchTop + SWATCH, TradingPalette.down());
        graphics.text(font, ClientConfig.colorScheme().label(), x + SWATCH * 2 + 5, GuiText.centeredY(font, top, top + TOGGLE_HEIGHT),
                TradingPalette.TEXT_PRIMARY, false);
        hits.add(hit);
    }

    /** 行情栏的数据，按显示顺序；优先级决定行数不够时先丢哪项（最新价格最后丢）。 */
    private static List<Stat> stats(MarketSnapshot snapshot) {
        List<Stat> stats = new ArrayList<>();
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.last_price"),
                MarketFormat.price(snapshot.lastPrice()), TradingPalette.direction(ClientMarket.lastPriceDirection()), 10));
        ClientMarket.stats24h().ifPresent(stats24h -> {
            double change = stats24h.close() - stats24h.open();
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.change_24h"),
                    MarketFormat.signedPrice(change) + " " + MarketFormat.signedPercent(stats24h.changeRatio()),
                    TradingPalette.signed(change), 9));
        });
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.mark_price"),
                MarketFormat.price(snapshot.markPrice()), TradingPalette.TEXT_PRIMARY, 8));
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.index_price"),
                MarketFormat.price(snapshot.indexPrice()), TradingPalette.TEXT_PRIMARY, 5));
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.funding"),
                MarketFormat.fundingRate(snapshot.fundingRate()) + " / " + MarketFormat.countdown(snapshot.secondsUntilFunding()),
                TradingPalette.BRAND_YELLOW, 7));
        ClientMarket.stats24h().ifPresent(stats24h -> {
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.high_24h"),
                    MarketFormat.price(stats24h.high()), TradingPalette.TEXT_PRIMARY, 4));
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.low_24h"),
                    MarketFormat.price(stats24h.low()), TradingPalette.TEXT_PRIMARY, 3));
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.volume_24h"),
                    MarketFormat.volume(stats24h.volume()), TradingPalette.TEXT_PRIMARY, 2));
        });
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.market_time"),
                MarketFormat.marketTime(snapshot.time()), TradingPalette.TEXT_PRIMARY, 6));
        return stats;
    }

    /**
     * 图表卡片：顶部页签行（K 线周期；盘口栏并进来时前面还有 K 线 / 盘口 / 最新成交三页，一行放不下就把周期挪到第二行），
     * 下面是 K 线图或盘口栏的当前页。
     */
    private void extractChartCard(GuiGraphicsExtractor graphics, MarketSnapshot snapshot, int mouseX, int mouseY, float partialTick) {
        TradingLayout.Rect card = layout.chart();
        graphics.fill(card.left(), card.top(), card.right(), card.bottom(), TradingPalette.PANEL);
        graphics.enableScissor(card.left(), card.top(), card.right(), card.bottom());
        boolean tabbed = layout.columnMode() == TradingLayout.ColumnMode.TABBED;
        boolean showKline = !tabbed || !chartShowsColumn;
        int x = card.left() + 4;
        int rowTop = card.top();
        int contentTop = card.top() + TAB_HEIGHT;
        if (tabbed) {
            Component[] views = {Component.translatable("screen.creepercapital.trading.chart.kline"),
                    MarketColumn.Page.ORDER_BOOK.label(), MarketColumn.Page.LATEST_TRADES.label()};
            int selected = showKline ? 0 : marketColumn.page() == MarketColumn.Page.ORDER_BOOK ? 1 : 2;
            int[][] boxes = TabStrip.extract(graphics, font, x, rowTop, TAB_HEIGHT, views, selected, 6, mouseX, mouseY);
            register(boxes[0], () -> chartShowsColumn = false);
            register(boxes[1], () -> showColumnPage(MarketColumn.Page.ORDER_BOOK));
            register(boxes[2], () -> showColumnPage(MarketColumn.Page.LATEST_TRADES));
            x = boxes[2][2] + 4;
        }
        if (showKline) {
            CandlePeriod[] periods = CandlePeriod.values();
            Component[] labels = new Component[periods.length];
            int selected = 0;
            for (int i = 0; i < periods.length; i++) {
                labels[i] = Component.literal(periods[i].label());
                if (periods[i] == ClientKlines.selected()) {
                    selected = i;
                }
            }
            if (tabbed) {
                if (x + 4 + TabStrip.width(font, labels, 4) > card.right() - 4) {
                    // 一行放不下：周期挪到第二行
                    graphics.horizontalLine(card.left(), card.right() - 1, contentTop, TradingPalette.DIVIDER);
                    x = card.left() + 4;
                    rowTop = contentTop + 1;
                    contentTop = rowTop + TAB_HEIGHT;
                } else {
                    graphics.verticalLine(x, rowTop + 3, rowTop + TAB_HEIGHT - 4, TradingPalette.DIVIDER);
                    x += 4;
                }
            }
            int padding = x + TabStrip.width(font, labels, 6) <= card.right() - 4 ? 6 : 4;
            int[][] boxes = TabStrip.extract(graphics, font, x, rowTop, TAB_HEIGHT, labels, selected, padding, mouseX, mouseY);
            for (int i = 0; i < periods.length; i++) {
                CandlePeriod period = periods[i];
                register(boxes[i], () -> ClientKlines.select(period));
            }
            extractChartTypeToggle(graphics, card, boxes[periods.length - 1][2], rowTop, mouseX, mouseY);
        }
        graphics.horizontalLine(card.left(), card.right() - 1, contentTop, TradingPalette.DIVIDER);
        contentTop += 1;
        if (showKline) {
            if (tabbed) {
                // 盘口栏的页这一帧没画：滚轮不该再翻最新成交
                marketColumn.forgetPage();
            }
            List<Candle> candles = ClientKlines.candles();
            if (candles == null || candles.isEmpty()) {
                chart.forget();
                graphics.centeredText(font, Component.translatable("screen.creepercapital.trading.kline.loading"),
                        (card.left() + card.right()) / 2, GuiText.centeredY(font, contentTop, card.bottom()), TradingPalette.TEXT_SECONDARY);
            } else {
                chart.extract(graphics, card.left() + 6, contentTop + 3, card.right() - 4, card.bottom() - 2, ClientKlines.selected(),
                        candles, mouseX, mouseY, partialTick);
            }
        } else {
            chart.forget();
            marketColumn.extractPage(graphics, card.left(), contentTop, card.right(), card.bottom(), snapshot.symbol(), snapshot);
        }
        graphics.disableScissor();
    }

    /**
     * 周期页签右侧、卡片右端的图表类型切换（K 线 / 线形）：先试正常间距，再试收紧间距，
     * 都放不下就退成一个显示当前类型、点一下切到另一种的按钮；连这个也放不下就不画。
     */
    private void extractChartTypeToggle(GuiGraphicsExtractor graphics, TradingLayout.Rect card, int periodsEnd, int rowTop,
            int mouseX, int mouseY) {
        ChartType current = ClientConfig.chartType();
        ChartType[] types = ChartType.values();
        Component[] labels = {types[0].label(), types[1].label()};
        int limit = periodsEnd + 8;
        int right = card.right() - 4;
        for (int padding : new int[] {6, 4, 2}) {
            int total = TabStrip.width(font, labels, padding);
            if (right - total >= limit) {
                int left = right - total;
                graphics.verticalLine(left - 3, rowTop + 3, rowTop + TAB_HEIGHT - 4, TradingPalette.DIVIDER);
                int[][] boxes = TabStrip.extract(graphics, font, left, rowTop, TAB_HEIGHT, labels, current.ordinal(), padding,
                        mouseX, mouseY);
                for (int i = 0; i < types.length; i++) {
                    ChartType type = types[i];
                    register(boxes[i], () -> ClientConfig.setChartType(type));
                }
                return;
            }
        }
        Component[] single = {current.label()};
        int total = TabStrip.width(font, single, 3);
        if (right - total >= limit) {
            int[][] boxes = TabStrip.extract(graphics, font, right - total, rowTop, TAB_HEIGHT, single, 0, 3, mouseX, mouseY);
            register(boxes[0], () -> ClientConfig.setChartType(current.next()));
        }
    }

    private void showColumnPage(MarketColumn.Page page) {
        chartShowsColumn = true;
        marketColumn.setPage(page);
    }

    private void register(int[] box, Runnable action) {
        hits.add(new Hit(box[0], box[1], box[2], box[3], action));
    }

    /** 底部表格区卡片：一排页签（放不下时收紧间距、换短名称）和当前页的数据。 */
    private void extractTable(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        TradingLayout.Rect card = layout.table();
        graphics.fill(card.left(), card.top(), card.right(), card.bottom(), TradingPalette.PANEL);
        graphics.enableScissor(card.left(), card.top(), card.right(), card.bottom());
        TableTab[] tabs = TableTab.values();
        int[] full = new int[tabs.length];
        int[] shortened = new int[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            full[i] = font.width(tabs[i].label(false));
            shortened[i] = font.width(tabs[i].label(true));
        }
        LayoutFit.TabStyle style = LayoutFit.tabs(card.width() - 8, full, shortened);
        Component[] labels = new Component[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            labels[i] = tabs[i].label(style.shortLabels());
        }
        int[][] boxes = TabStrip.extract(graphics, font, card.left() + 4, card.top(), TABLE_TAB_HEIGHT, labels, tableTab.ordinal(),
                style.padding(), mouseX, mouseY);
        for (int i = 0; i < tabs.length; i++) {
            TableTab tab = tabs[i];
            register(boxes[i], () -> {
                if (tableTab != tab) {
                    tableTab = tab;
                    tables.reset();
                }
            });
        }
        graphics.horizontalLine(card.left(), card.right() - 1, card.top() + TABLE_TAB_HEIGHT, TradingPalette.DIVIDER);

        int left = card.left() + PAD;
        int right = card.right() - PAD;
        int top = card.top() + TABLE_TAB_HEIGHT + 3;
        int bottom = card.bottom() - 2;
        switch (tableTab) {
            case ASSETS -> {
                tables.forgetButtons();
                extractAssets(graphics, left, top, right);
            }
            case POSITIONS -> tables.extractPositions(graphics, left, top, right, bottom, mouseX, mouseY);
            case OPEN_ORDERS -> tables.extractOpenOrders(graphics, left, top, right, bottom, mouseX, mouseY);
            case ORDER_HISTORY -> tables.extractOrderHistory(graphics, left, top, right, bottom, mouseX, mouseY);
            case TRADES -> tables.extractTrades(graphics, left, top, right, bottom, mouseX, mouseY);
            case FUNDING -> tables.extractFunding(graphics, left, top, right, bottom, mouseX, mouseY);
        }
        graphics.disableScissor();
    }

    /** 资产页：钱包余额、未实现盈亏、保证金余额、可用余额，名称在上、数值在下，等宽排开，一行放不下就折行。 */
    private void extractAssets(GuiGraphicsExtractor graphics, int left, int top, int right) {
        Assets assets = ClientAccount.assets();
        List<Stat> stats = List.of(
                new Stat(Component.translatable("screen.creepercapital.trading.assets.wallet"),
                        MarketFormat.balance(assets.walletBalance()) + " CRP", TradingPalette.TEXT_PRIMARY, 0),
                new Stat(Component.translatable("screen.creepercapital.trading.assets.unrealized_pnl"),
                        MarketFormat.signedBalance(assets.unrealizedPnl()) + " CRP", TradingPalette.signed(assets.unrealizedPnl().units()), 0),
                new Stat(Component.translatable("screen.creepercapital.trading.assets.margin_balance"),
                        MarketFormat.balance(assets.marginBalance()) + " CRP", TradingPalette.TEXT_PRIMARY, 0),
                new Stat(Component.translatable("screen.creepercapital.trading.assets.available_balance"),
                        MarketFormat.balance(assets.availableBalance()) + " CRP", TradingPalette.TEXT_PRIMARY, 0));
        int cell = 0;
        for (Stat stat : stats) {
            cell = Math.max(cell, Math.max(font.width(stat.label()), font.width(stat.value())));
        }
        cell += STAT_GAP * 2;
        // 一行四项放不下就排成两列两行（比三加一整齐），再不行一项一行
        int fits = (right - left + STAT_GAP * 2) / cell;
        int perRow = fits >= stats.size() ? stats.size() : fits >= 2 ? 2 : 1;
        for (int i = 0; i < stats.size(); i++) {
            Stat stat = stats.get(i);
            int x = left + (i % perRow) * cell;
            int y = top + 2 + (i / perRow) * (font.lineHeight * 2 + LINE_GAP + 6);
            graphics.text(font, stat.label(), x, y, TradingPalette.TEXT_SECONDARY, false);
            graphics.text(font, stat.value(), x, y + font.lineHeight + LINE_GAP, stat.valueColor(), false);
        }
    }
}
