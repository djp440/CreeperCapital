package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 交易界面（币安合约网页的深色主题）：深色底上一块块卡片——标题栏、行情栏，下面左侧是 K 线图卡片（右边并排盘口栏 {@link MarketColumn}）
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
        if (orderPanel.anyInputFocused() && event.key() != GLFW.GLFW_KEY_ESCAPE) {
            // 在数量 / 委托价 / 止盈止损价输入框里打字时，快捷键字母不能关掉界面
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
        if (layout != null && layout.table().contains(x, y)) {
            tables.mouseScrolled(scrollY);
            return true;
        }
        return marketColumn.mouseScrolled(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && clickedContractControls(event.x(), event.y())) {
            orderPanel.unfocusInputs();
            return true;
        }
        if (event.button() == 0) {
            String symbol = ClientMarket.selectedSymbol().orElse(null);
            if (symbol != null && orderPanel.mouseClicked(event.x(), event.y(), symbol)) {
                orderPanel.unfocusInputs();
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
        graphics.fill(0, 0, width, height, BinancePalette.BACKGROUND);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        hits.clear();
        MarketSnapshot snapshot = ClientMarket.latest().orElse(null);
        if (snapshot == null) {
            layout = TradingLayout.compute(width, height, 1);
            extractHeader(graphics);
            graphics.centeredText(font, Component.translatable("screen.creepercapital.trading.waiting"), width / 2,
                    GuiText.centeredY(font, 0, height), BinancePalette.TEXT_SECONDARY);
            return;
        }
        TickerPlan ticker = planTicker(snapshot);
        layout = TradingLayout.compute(width, height, ticker.rows());
        extractHeader(graphics);
        extractTicker(graphics, snapshot, ticker, mouseX, mouseY);
        extractChartCard(graphics, snapshot, mouseX, mouseY);
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
        if (contractMenuOpen) {
            extractContractMenu(graphics, snapshot.symbol(), mouseX, mouseY);
        }
    }

    /** 合约下拉列表：挂在交易代码按钮下方，盖在图表之上，当前合约黄字，鼠标悬停的一项底色变亮。 */
    private void extractContractMenu(GuiGraphicsExtractor graphics, String current, int mouseX, int mouseY) {
        List<String> symbols = ClientMarket.symbols();
        int left = symbolButton.left();
        int top = symbolButton.bottom();
        int right = left + menuWidth(symbols);
        int bottom = top + symbols.size() * MENU_ROW_HEIGHT;
        graphics.fill(left, top, right, bottom, BinancePalette.PANEL);
        graphics.outline(left, top, right - left, bottom - top, BinancePalette.DIVIDER);
        int hovered = menuIndexAt(symbols, mouseX, mouseY);
        for (int i = 0; i < symbols.size(); i++) {
            int rowTop = top + i * MENU_ROW_HEIGHT;
            if (i == hovered) {
                graphics.fill(left + 1, rowTop, right - 1, rowTop + MENU_ROW_HEIGHT, BinancePalette.HOVER);
            }
            String symbol = symbols.get(i);
            int color = symbol.equals(current) ? BinancePalette.BRAND_YELLOW
                    : i == hovered ? BinancePalette.TEXT_PRIMARY : BinancePalette.TEXT_SECONDARY;
            graphics.text(font, symbol, left + MENU_PADDING, GuiText.centeredY(font, rowTop, rowTop + MENU_ROW_HEIGHT), color, false);
        }
    }

    /** 标题栏（矮屏时省掉）：品牌黄的模组名。 */
    private void extractHeader(GuiGraphicsExtractor graphics) {
        TradingLayout.Rect header = layout.header();
        if (header.isEmpty()) {
            return;
        }
        graphics.fill(header.left(), header.top(), header.right(), header.bottom(), BinancePalette.PANEL);
        graphics.text(font, title, TradingLayout.GAP + PAD, GuiText.centeredY(font, header.top(), header.bottom()),
                BinancePalette.BRAND_YELLOW, false);
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
        graphics.fill(card.left(), card.top(), card.right(), card.bottom(), BinancePalette.PANEL);
        graphics.enableScissor(card.left(), card.top(), card.right(), card.bottom());
        int left = card.left() + PAD;
        int upperOffset = (rowHeight - font.lineHeight * 2 - LINE_GAP) / 2 + 1;

        boolean switchable = ClientMarket.symbols().size() > 1;
        symbolButton = new TradingLayout.Rect(card.left(), card.top(), left + plan.symbolWidth() + STAT_GAP / 2, card.top() + rowHeight);
        if (switchable && (contractMenuOpen || symbolButton.contains(mouseX, mouseY))) {
            graphics.fill(symbolButton.left(), symbolButton.top(), symbolButton.right(), symbolButton.bottom(), BinancePalette.HOVER);
        }
        int upperY = card.top() + upperOffset;
        graphics.text(font, snapshot.symbol(), left, upperY, BinancePalette.TEXT_PRIMARY, false);
        if (switchable) {
            int caretColor = contractMenuOpen ? BinancePalette.BRAND_YELLOW : BinancePalette.TEXT_SECONDARY;
            int caretCenter = left + font.width(snapshot.symbol()) + CARET_GAP + CARET_HALF_WIDTH;
            for (int line = 0; line <= CARET_HALF_WIDTH; line++) {
                int half = CARET_HALF_WIDTH - line;
                int y = upperY + 2 + line;
                graphics.fill(caretCenter - half, y, caretCenter + half + 1, y + 1, caretColor);
            }
        }
        graphics.text(font, Component.translatable("screen.creepercapital.trading.perpetual"), left, upperY + font.lineHeight + LINE_GAP,
                BinancePalette.TEXT_SECONDARY, false);

        for (int i = 0; i < plan.stats().size(); i++) {
            LayoutFit.Slot slot = plan.slots().get(i);
            if (slot.dropped()) {
                continue;
            }
            Stat stat = plan.stats().get(i);
            int y = card.top() + slot.row() * rowHeight + upperOffset;
            graphics.text(font, stat.label(), slot.x(), y, BinancePalette.TEXT_SECONDARY, false);
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
                hit.contains(mouseX, mouseY) ? BinancePalette.BUTTON_HOVER : BinancePalette.BUTTON);
        int swatchTop = top + (TOGGLE_HEIGHT - SWATCH) / 2;
        int x = left + TOGGLE_PADDING;
        graphics.fill(x, swatchTop, x + SWATCH, swatchTop + SWATCH, BinancePalette.up());
        graphics.fill(x + SWATCH + 1, swatchTop, x + SWATCH * 2 + 1, swatchTop + SWATCH, BinancePalette.down());
        graphics.text(font, ClientConfig.colorScheme().label(), x + SWATCH * 2 + 5, GuiText.centeredY(font, top, top + TOGGLE_HEIGHT),
                BinancePalette.TEXT_PRIMARY, false);
        hits.add(hit);
    }

    /** 行情栏的数据，按显示顺序；优先级决定行数不够时先丢哪项（最新价格最后丢）。 */
    private static List<Stat> stats(MarketSnapshot snapshot) {
        List<Stat> stats = new ArrayList<>();
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.last_price"),
                MarketFormat.price(snapshot.lastPrice()), BinancePalette.direction(ClientMarket.lastPriceDirection()), 10));
        ClientMarket.stats24h().ifPresent(stats24h -> {
            double change = stats24h.close() - stats24h.open();
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.change_24h"),
                    MarketFormat.signedPrice(change) + " " + MarketFormat.signedPercent(stats24h.changeRatio()),
                    BinancePalette.signed(change), 9));
        });
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.mark_price"),
                MarketFormat.price(snapshot.markPrice()), BinancePalette.TEXT_PRIMARY, 8));
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.index_price"),
                MarketFormat.price(snapshot.indexPrice()), BinancePalette.TEXT_PRIMARY, 5));
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.funding"),
                MarketFormat.fundingRate(snapshot.fundingRate()) + " / " + MarketFormat.countdown(snapshot.secondsUntilFunding()),
                BinancePalette.BRAND_YELLOW, 7));
        ClientMarket.stats24h().ifPresent(stats24h -> {
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.high_24h"),
                    MarketFormat.price(stats24h.high()), BinancePalette.TEXT_PRIMARY, 4));
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.low_24h"),
                    MarketFormat.price(stats24h.low()), BinancePalette.TEXT_PRIMARY, 3));
            stats.add(new Stat(Component.translatable("screen.creepercapital.trading.volume_24h"),
                    MarketFormat.volume(stats24h.volume()), BinancePalette.TEXT_PRIMARY, 2));
        });
        stats.add(new Stat(Component.translatable("screen.creepercapital.trading.market_time"),
                MarketFormat.marketTime(snapshot.time()), BinancePalette.TEXT_PRIMARY, 6));
        return stats;
    }

    /**
     * 图表卡片：顶部页签行（K 线周期；盘口栏并进来时前面还有 K 线 / 盘口 / 最新成交三页，一行放不下就把周期挪到第二行），
     * 下面是 K 线图或盘口栏的当前页。
     */
    private void extractChartCard(GuiGraphicsExtractor graphics, MarketSnapshot snapshot, int mouseX, int mouseY) {
        TradingLayout.Rect card = layout.chart();
        graphics.fill(card.left(), card.top(), card.right(), card.bottom(), BinancePalette.PANEL);
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
                    graphics.horizontalLine(card.left(), card.right() - 1, contentTop, BinancePalette.DIVIDER);
                    x = card.left() + 4;
                    rowTop = contentTop + 1;
                    contentTop = rowTop + TAB_HEIGHT;
                } else {
                    graphics.verticalLine(x, rowTop + 3, rowTop + TAB_HEIGHT - 4, BinancePalette.DIVIDER);
                    x += 4;
                }
            }
            int padding = x + TabStrip.width(font, labels, 6) <= card.right() - 4 ? 6 : 4;
            int[][] boxes = TabStrip.extract(graphics, font, x, rowTop, TAB_HEIGHT, labels, selected, padding, mouseX, mouseY);
            for (int i = 0; i < periods.length; i++) {
                CandlePeriod period = periods[i];
                register(boxes[i], () -> ClientKlines.select(period));
            }
        }
        graphics.horizontalLine(card.left(), card.right() - 1, contentTop, BinancePalette.DIVIDER);
        contentTop += 1;
        if (showKline) {
            if (tabbed) {
                // 盘口栏的页这一帧没画：滚轮不该再翻最新成交
                marketColumn.forgetPage();
            }
            List<Candle> candles = ClientKlines.candles();
            if (candles == null || candles.isEmpty()) {
                graphics.centeredText(font, Component.translatable("screen.creepercapital.trading.kline.loading"),
                        (card.left() + card.right()) / 2, GuiText.centeredY(font, contentTop, card.bottom()), BinancePalette.TEXT_SECONDARY);
            } else {
                chart.extract(graphics, card.left() + 6, contentTop + 3, card.right() - 4, card.bottom() - 2, ClientKlines.selected(),
                        candles, mouseX, mouseY);
            }
        } else {
            marketColumn.extractPage(graphics, card.left(), contentTop, card.right(), card.bottom(), snapshot.symbol(), snapshot);
        }
        graphics.disableScissor();
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
        graphics.fill(card.left(), card.top(), card.right(), card.bottom(), BinancePalette.PANEL);
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
        graphics.horizontalLine(card.left(), card.right() - 1, card.top() + TABLE_TAB_HEIGHT, BinancePalette.DIVIDER);

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
                        MarketFormat.balance(assets.walletBalance()) + " CRP", BinancePalette.TEXT_PRIMARY, 0),
                new Stat(Component.translatable("screen.creepercapital.trading.assets.unrealized_pnl"),
                        MarketFormat.signedBalance(assets.unrealizedPnl()) + " CRP", BinancePalette.signed(assets.unrealizedPnl().units()), 0),
                new Stat(Component.translatable("screen.creepercapital.trading.assets.margin_balance"),
                        MarketFormat.balance(assets.marginBalance()) + " CRP", BinancePalette.TEXT_PRIMARY, 0),
                new Stat(Component.translatable("screen.creepercapital.trading.assets.available_balance"),
                        MarketFormat.balance(assets.availableBalance()) + " CRP", BinancePalette.TEXT_PRIMARY, 0));
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
            graphics.text(font, stat.label(), x, y, BinancePalette.TEXT_SECONDARY, false);
            graphics.text(font, stat.value(), x, y + font.lineHeight + LINE_GAP, stat.valueColor(), false);
        }
    }
}
