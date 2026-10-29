package io.github.djp440.creepercapital.client;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.OpenEstimate;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Quantities;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.network.OrderPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * 下单面板：顶部切换「市价」「限价」，右侧点一下切逐仓 / 全仓，选杠杆、填数量（限价还要填委托价），
 * 点「开多」「开空」开仓，或点「平仓」平掉当前合约的仓位（市价立即平，限价则挂只减仓限价单）。限价单挂在服务端，价格穿越委托价才成交，挂单期间冻结保证金。
 * <p>
 * 数量下方有「预估」「止盈止损」两页：预估页是可用余额与预估成交价、保证金、手续费；止盈止损页填止盈价、止损价，选触发价来源
 * （最新价格 / 标记价格）和只减仓开关。开仓时填了止盈止损就随开仓附带；有仓位时「设止盈止损」给现有仓位补设。
 * 只减仓开着时，「开多」「开空」是买入 / 卖出缩减反向仓位，永远不会开仓。面板高度很紧，所以两页共用同一块区域。
 * <p>
 * 已有仓位时点杠杆的 − / + 是直接调整仓位的杠杆（加仓的杠杆必须和仓位一致），有仓位时不能切换保证金模式；
 * 「平仓」留空数量就是全平。市价的预估成交均价、保证金和手续费由 {@link TradingBook#estimateOpen} 按实际开仓的同一条
 * 虚拟流动性曲线算（大单逐档吃单、均价更差），开多、开空各一行均价，保证金与手续费按较高的开多一侧显示；
 * 限价按委托价估算。下单结果（成交或拒单原因）由服务端回报，显示在按钮下方；面板底部还有空间时显示账户概况。
 */
final class OrderPanel {
    /** 只用来给输入框定初始宽度；面板实际宽度由 {@link TradingLayout} 按屏幕算，输入框每帧按它重设宽度。 */
    private static final int WIDTH = 168;
    private static final int PAD = OrderPanelLayout.PAD;
    private static final int ROW = OrderPanelLayout.ROW;
    private static final int BUTTON_HEIGHT = 18;
    private static final int STEP_BUTTON = OrderPanelLayout.STEP_BUTTON;
    private static final int INPUT_HEIGHT = OrderPanelLayout.INPUT_HEIGHT;
    private static final int BUTTON_BACKGROUND = TradingPalette.BUTTON;
    private static final int BUTTON_HOVER = TradingPalette.BUTTON_HOVER;
    private static final int INPUT_BACKGROUND = TradingPalette.BACKGROUND;
    private static final int DISABLED = TradingPalette.DISABLED;
    /** 面板底部的账户概况：标题加三行（钱包余额、未实现盈亏、保证金余额）。 */
    private static final int ACCOUNT_HEIGHT = ROW * 4 + 4;

    private final Font font;
    private final EditBox quantityBox;
    private final EditBox priceBox;
    private final EditBox takeProfitBox;
    private final EditBox stopLossBox;
    /** 每帧渲染时记下各按钮的区域，点击判定要用：{左, 上, 右, 下}。 */
    private int[] modeBox = new int[4];
    private int[] minus = new int[4];
    private int[] plus = new int[4];
    private int[] buyBox = new int[4];
    private int[] sellBox = new int[4];
    private int[] closeBox = new int[4];
    private int[] marketTab = new int[4];
    private int[] limitTab = new int[4];
    private int[] estimateTab = new int[4];
    private int[] protectionTab = new int[4];
    private int[] sourceBox = new int[4];
    private int[] reduceBox = new int[4];
    private int[] protectBox = new int[4];
    /** 上一帧画出来的输入框区域；点击判定读它，和绘制同源（#39）。 */
    private OrderPanelLayout.Fields fields = new OrderPanelLayout.Fields(TradingLayout.Rect.EMPTY, TradingLayout.Rect.EMPTY,
            TradingLayout.Rect.EMPTY, TradingLayout.Rect.EMPTY, 0, 0);
    private int priceUnitWidth;
    private int quantityUnitWidth;

    OrderPanel(Font font) {
        this.font = font;
        this.quantityBox = new EditBox(font, 0, 0, WIDTH - PAD * 2 - 8, INPUT_HEIGHT - 6,
                Component.translatable("screen.creepercapital.trading.order.quantity_field"));
        quantityBox.setBordered(false);
        quantityBox.setMaxLength(24);
        quantityBox.setFilter(text -> text.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.'));
        quantityBox.setHint(Component.translatable("screen.creepercapital.trading.order.quantity_hint"));
        this.priceBox = new EditBox(font, 0, 0, WIDTH - PAD * 2 - 8, INPUT_HEIGHT - 6,
                Component.translatable("screen.creepercapital.trading.order.limit_price_field"));
        priceBox.setBordered(false);
        priceBox.setMaxLength(16);
        priceBox.setFilter(text -> text.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.'));
        priceBox.setHint(Component.translatable("screen.creepercapital.trading.order.limit_price_hint"));
        this.takeProfitBox = triggerBox("screen.creepercapital.trading.order.take_profit_field");
        this.stopLossBox = triggerBox("screen.creepercapital.trading.order.stop_loss_field");
    }

    private EditBox triggerBox(String key) {
        EditBox box = new EditBox(font, 0, 0, WIDTH / 2 - PAD - 8, INPUT_HEIGHT - 6, Component.translatable(key));
        box.setBordered(false);
        box.setMaxLength(16);
        box.setFilter(text -> text.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.'));
        box.setHint(Component.translatable(key));
        return box;
    }

    /**
     * 点击落在某个输入框画出来的区域里（含内边距和右侧单位文字）就由这里处理：把点击坐标夹回输入框的文字区域再交给它定位光标。
     * 原版 {@code EditBox} 只认文字区域内的点击，点在边距或单位文字上会落空。
     *
     * @return 被点中的输入框；没点在任何输入框上返回 {@code null}
     */
    @Nullable
    EditBox mouseClickedInput(MouseButtonEvent event, boolean doubleClick) {
        OrderPanelLayout.Input input = fields.hitTest(event.x(), event.y());
        if (input == null) {
            return null;
        }
        EditBox box;
        TradingLayout.Rect field;
        int unitWidth = 0;
        switch (input) {
            case PRICE -> {
                box = priceBox;
                field = fields.price();
                unitWidth = priceUnitWidth;
            }
            case QUANTITY -> {
                box = quantityBox;
                field = fields.quantity();
                unitWidth = quantityUnitWidth;
            }
            case TAKE_PROFIT -> {
                box = takeProfitBox;
                field = fields.takeProfit();
            }
            default -> {
                box = stopLossBox;
                field = fields.stopLoss();
            }
        }
        TradingLayout.Rect text = OrderPanelLayout.textArea(field, unitWidth);
        MouseButtonEvent inside = new MouseButtonEvent(OrderPanelLayout.clampX(text, event.x()),
                OrderPanelLayout.clampY(text, event.y()), event.buttonInfo());
        return box.mouseClicked(inside, doubleClick) ? box : null;
    }

    boolean anyInputFocused() {
        return quantityBox.isFocused() || priceBox.isFocused() || takeProfitBox.isFocused() || stopLossBox.isFocused();
    }

    void unfocusInputs() {
        quantityBox.setFocused(false);
        priceBox.setFocused(false);
        takeProfitBox.setFocused(false);
        stopLossBox.setFocused(false);
    }

    EditBox takeProfitBox() {
        return takeProfitBox;
    }

    EditBox stopLossBox() {
        return stopLossBox;
    }

    /** 开发用（自动截图）：切到止盈止损页并填好止盈价、止损价。 */
    void setProtection(String takeProfit, String stopLoss) {
        ClientTrading.setProtectionPage(true);
        takeProfitBox.setValue(takeProfit);
        stopLossBox.setValue(stopLoss);
    }

    EditBox priceBox() {
        return priceBox;
    }

    /** 开发用（自动截图）：切到限价并填好委托价。 */
    void setLimit(String price) {
        ClientTrading.setLimitMode(true);
        priceBox.setValue(price);
    }

    EditBox quantityBox() {
        return quantityBox;
    }

    /** 开发用（自动截图）：填好数量。 */
    void setQuantity(String text) {
        quantityBox.setValue(text);
    }

    void extract(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, String symbol, @Nullable MarketSnapshot snapshot,
            int mouseX, int mouseY, float partialTick) {
        graphics.fill(left, top, right, bottom, TradingPalette.PANEL);
        TradingParams params = ClientTrading.params(symbol).orElse(null);
        Position position = ClientTrading.position(symbol).orElse(null);
        int x = left + PAD;
        int width = right - left - PAD * 2;
        int y = top + 6;

        boolean limit = ClientTrading.limitMode();
        boolean protectionPage = ClientTrading.protectionPage();
        // 输入框画在哪里由 OrderPanelLayout 算，点击判定也读这份结果，保证画出来的和点得到的一致
        fields = OrderPanelLayout.compute(new TradingLayout.Rect(left, top, right, bottom), limit, protectionPage);
        // 市价模式下委托价输入框不画，也不能接收点击
        priceBox.visible = limit;
        priceBox.active = limit;
        if (!limit) {
            priceBox.setFocused(false);
        }
        int tabBottom = y + ROW;
        int marketWidth = font.width(Component.translatable("screen.creepercapital.trading.order.mode.market")) + 12;
        int limitWidth = font.width(Component.translatable("screen.creepercapital.trading.order.mode.limit")) + 12;
        marketTab = new int[] {x, y - 2, x + marketWidth, tabBottom};
        limitTab = new int[] {x + marketWidth, y - 2, x + marketWidth + limitWidth, tabBottom};
        modeTab(graphics, marketTab, "screen.creepercapital.trading.order.mode.market", !limit, mouseX, mouseY);
        modeTab(graphics, limitTab, "screen.creepercapital.trading.order.mode.limit", limit, mouseX, mouseY);
        // 保证金模式：点一下在「逐仓」「全仓」之间切换（有仓位或委托时服务端会拒绝并提示）
        Component modeLabel = Component.translatable(ClientTrading.marginMode(symbol) == MarginMode.CROSS
                ? "screen.creepercapital.trading.order.cross" : "screen.creepercapital.trading.order.isolated");
        int modeWidth = font.width(modeLabel) + 10;
        modeBox = new int[] {right - PAD - modeWidth, y - 2, right - PAD, tabBottom};
        graphics.fill(modeBox[0], modeBox[1], modeBox[2], modeBox[3],
                inside(modeBox, mouseX, mouseY) ? BUTTON_HOVER : BUTTON_BACKGROUND);
        graphics.centeredText(font, modeLabel, (modeBox[0] + modeBox[2]) / 2, y + 1, TradingPalette.TEXT_PRIMARY);
        y += ROW + 2;

        // 杠杆：「−」「倍数」「+」，有仓位时直接调整仓位的杠杆（受当前档位上限和可用保证金约束，由服务端校验）
        int leverage = ClientTrading.leverage(symbol);
        graphics.text(font, Component.translatable("screen.creepercapital.trading.order.leverage"), x, y + 3, TradingPalette.TEXT_SECONDARY, false);
        String leverageText = leverage + "x";
        int plusLeft = right - PAD - STEP_BUTTON;
        int textRight = plusLeft - 4;
        int minusLeft = textRight - Math.max(font.width("125x"), font.width(leverageText)) - 4 - STEP_BUTTON;
        minus = new int[] {minusLeft, y, minusLeft + STEP_BUTTON, y + STEP_BUTTON};
        plus = new int[] {plusLeft, y, plusLeft + STEP_BUTTON, y + STEP_BUTTON};
        stepButton(graphics, minus, "−", leverage > 1, mouseX, mouseY);
        stepButton(graphics, plus, "+", params != null && leverage < params.maxLeverage(), mouseX, mouseY);
        graphics.centeredText(font, leverageText, (minus[2] + plus[0]) / 2, y + 3, TradingPalette.TEXT_PRIMARY);
        y += STEP_BUTTON + 4;

        // 委托价（限价）：输入框右侧标出 CRP 作单位
        if (limit) {
            TradingLayout.Rect field = fields.price();
            y = field.top();
            graphics.fill(x, y, x + width, y + INPUT_HEIGHT, INPUT_BACKGROUND);
            graphics.outline(x, y, width, INPUT_HEIGHT, priceBox.isFocused() ? TradingPalette.BRAND_YELLOW : TradingPalette.DIVIDER);
            int crpWidth = font.width("CRP");
            graphics.text(font, "CRP", x + width - 4 - crpWidth, y + (INPUT_HEIGHT - font.lineHeight) / 2 + 1,
                    TradingPalette.TEXT_SECONDARY, false);
            placeBox(priceBox, field, crpWidth);
            priceUnitWidth = crpWidth;
            priceBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        // 数量：输入框右侧标出标的资源的名称作单位
        String unit = underlyingName(symbol);
        y = fields.quantity().top();
        graphics.fill(x, y, x + width, y + INPUT_HEIGHT, INPUT_BACKGROUND);
        graphics.outline(x, y, width, INPUT_HEIGHT, quantityBox.isFocused() ? TradingPalette.BRAND_YELLOW : TradingPalette.DIVIDER);
        int unitWidth = font.width(unit);
        graphics.text(font, unit, x + width - 4 - unitWidth, y + (INPUT_HEIGHT - font.lineHeight) / 2 + 1,
                TradingPalette.TEXT_SECONDARY, false);
        placeBox(quantityBox, fields.quantity(), unitWidth);
        quantityUnitWidth = unitWidth;
        quantityBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        y = fields.tabsY();

        // 预估页 / 止盈止损页共用同一块区域（面板高度很紧）
        takeProfitBox.visible = protectionPage;
        takeProfitBox.active = protectionPage;
        stopLossBox.visible = protectionPage;
        stopLossBox.active = protectionPage;
        if (!protectionPage) {
            takeProfitBox.setFocused(false);
            stopLossBox.setFocused(false);
        }
        int estimateWidth = font.width(Component.translatable("screen.creepercapital.trading.order.tab.estimate")) + 12;
        Component protectionLabel = protectionLabel();
        int protectionWidth = font.width(protectionLabel) + 12;
        estimateTab = new int[] {x, y - 2, x + estimateWidth, y + ROW};
        protectionTab = new int[] {x + estimateWidth, y - 2, x + estimateWidth + protectionWidth, y + ROW};
        modeTab(graphics, estimateTab, Component.translatable("screen.creepercapital.trading.order.tab.estimate"),
                !protectionPage, mouseX, mouseY);
        modeTab(graphics, protectionTab, protectionLabel, protectionPage, mouseX, mouseY);
        if (position != null && protectionPage) {
            // 止盈止损页放不下单独一行的仓位提示，缩短后放在页签条右侧
            Component held = Component.translatable("screen.creepercapital.trading.order.holding_short",
                    Component.translatable(position.side() == Side.LONG
                            ? "screen.creepercapital.trading.side.long" : "screen.creepercapital.trading.side.short"),
                    Quantities.format(position.lots(), position.quantityDecimals()), position.leverage());
            String text = GuiText.fit(font, held.getString(), right - PAD - protectionTab[2] - 4);
            graphics.text(font, text, right - PAD - font.width(text), y, TradingPalette.side(position.side() == Side.LONG), false);
        }
        y += ROW + 3;
        OptionalLong lots = params == null ? OptionalLong.empty() : Quantities.parseLots(quantityBox.getValue(), params.quantityDecimals());
        if (protectionPage) {
            y = extractProtection(graphics, x, width, y, mouseX, mouseY, partialTick);
        } else {
            // 预估：可用余额、成交价、保证金、手续费
            y = extractInfo(graphics, x, right - PAD, y, params, snapshot, lots, leverage,
                    limit ? limitPrice() : OptionalDouble.empty(), limit);
        }
        if (position != null && !protectionPage) {
            Component held = Component.translatable("screen.creepercapital.trading.order.holding",
                    Component.translatable(position.side() == Side.LONG
                            ? "screen.creepercapital.trading.side.long" : "screen.creepercapital.trading.side.short"),
                    Quantities.format(position.lots(), position.quantityDecimals()), position.leverage());
            graphics.text(font, GuiText.fit(font, held.getString(), width), x, y, TradingPalette.side(position.side() == Side.LONG), false);
            y += ROW - 1;
        }
        y += 1;

        // 开多 / 开空 / 平仓
        int gap = 6;
        int half = (width - gap) / 2;
        buyBox = new int[] {x, y, x + half, y + BUTTON_HEIGHT};
        sellBox = new int[] {x + half + gap, y, x + width, y + BUTTON_HEIGHT};
        colorButton(graphics, buyBox, Component.translatable("screen.creepercapital.trading.order.buy"), TradingPalette.up(), mouseX, mouseY);
        colorButton(graphics, sellBox, Component.translatable("screen.creepercapital.trading.order.sell"), TradingPalette.down(), mouseX, mouseY);
        y += BUTTON_HEIGHT + 4;
        boolean canClose = position != null;
        // 有仓位时，平仓行分成「平仓」和「设止盈止损」两个按钮
        int closeRight = canClose ? x + half : x + width;
        closeBox = new int[] {x, y, closeRight, y + BUTTON_HEIGHT - 2};
        protectBox = canClose ? new int[] {x + half + gap, y, x + width, y + BUTTON_HEIGHT - 2} : new int[4];
        plainButton(graphics, closeBox, Component.translatable(limit
                ? "screen.creepercapital.trading.order.close_limit" : "screen.creepercapital.trading.order.close"),
                canClose, mouseX, mouseY);
        if (canClose) {
            plainButton(graphics, protectBox, Component.translatable("screen.creepercapital.trading.order.protect"), true,
                    mouseX, mouseY);
        }
        y += BUTTON_HEIGHT + 2;

        // 账户概况：面板底部放得下（按钮下方至少还留两行下单结果）时才画
        int accountTop = bottom - 6 - ACCOUNT_HEIGHT;
        boolean account = accountTop >= y + (font.lineHeight + 1) * 2 + 4;
        if (account) {
            extractAccount(graphics, x, right - PAD, accountTop);
        }

        // 下单结果：成功是主文字色（只是告知），失败是错误红（与涨跌色方案无关）
        ClientTrading.Message message = ClientTrading.lastResult();
        if (message != null) {
            int color = message.success() ? TradingPalette.TEXT_PRIMARY : TradingPalette.ERROR;
            int limitY = account ? accountTop - 4 : bottom - 3;
            // 只画面板放得下的行，免得长消息越出面板底部或压到账户概况
            for (FormattedCharSequence line : font.split(message.text(), width)) {
                if (y + font.lineHeight > limitY) {
                    break;
                }
                graphics.text(font, line, x, y, color, false);
                y += font.lineHeight + 1;
            }
        }
    }

    /** 账户概况：分隔线、标题，然后钱包余额、未实现盈亏（按正负着涨跌色）、保证金余额。 */
    private void extractAccount(GuiGraphicsExtractor graphics, int x, int right, int top) {
        graphics.horizontalLine(x, right - 1, top, TradingPalette.DIVIDER);
        int y = top + 5;
        graphics.text(font, Component.translatable("screen.creepercapital.trading.order.account"), x, y, TradingPalette.TEXT_PRIMARY, false);
        y += ROW;
        Assets assets = ClientAccount.assets();
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.assets.wallet",
                MarketFormat.money(assets.walletBalance()) + " CRP", TradingPalette.TEXT_PRIMARY);
        y += ROW - 1;
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.assets.unrealized_pnl",
                MarketFormat.signedMoney(assets.unrealizedPnl()) + " CRP", TradingPalette.signed(assets.unrealizedPnl().units()));
        y += ROW - 1;
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.assets.margin_balance",
                MarketFormat.money(assets.marginBalance()) + " CRP", TradingPalette.TEXT_PRIMARY);
    }

    /**
     * 可用余额与预估值，每项一行：名称在左、数值在右。
     * 市价：开多、开空的成交均价各一行（沿虚拟流动性曲线逐档吃单，与实际开仓同源），保证金和手续费按开多一侧（两边中较高的）；
     * 限价：按委托价估算，手续费按挂单（maker）算，下单即可成交时服务端按 taker 收，这里只是提示。
     */
    private int extractInfo(GuiGraphicsExtractor graphics, int x, int right, int y, @Nullable TradingParams params,
            @Nullable MarketSnapshot snapshot, OptionalLong lots, int leverage, OptionalDouble limitPrice, boolean limit) {
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.order.available",
                MarketFormat.money(ClientAccount.assets().availableBalance()) + " CRP", TradingPalette.TEXT_PRIMARY);
        y += ROW - 1;
        String margin = "--";
        String fee = "--";
        if (limit) {
            double price = limitPrice.orElse(Double.NaN);
            if (params != null && lots.isPresent()) {
                // 和服务端挂单冻结（frozenFor）同源同取整，不在界面里另算
                Optional<OpenEstimate> frozen = TradingBook.estimateLimit(params, price, lots.getAsLong(), leverage);
                margin = frozen.map(estimate -> MarketFormat.money(estimate.margin()) + " CRP").orElse("--");
                fee = frozen.map(estimate -> MarketFormat.fee(estimate.fee()) + " CRP").orElse("--");
            }
        } else {
            Optional<OpenEstimate> buy = estimate(params, snapshot, Side.LONG, lots, leverage);
            Optional<OpenEstimate> sell = estimate(params, snapshot, Side.SHORT, lots, leverage);
            infoRow(graphics, x, right, y, "screen.creepercapital.trading.order.price_long",
                    buy.map(estimate -> MarketFormat.price(estimate.price())).orElse("--"),
                    buy.isPresent() ? TradingPalette.up() : TradingPalette.TEXT_PRIMARY);
            y += ROW - 1;
            infoRow(graphics, x, right, y, "screen.creepercapital.trading.order.price_short",
                    sell.map(estimate -> MarketFormat.price(estimate.price())).orElse("--"),
                    sell.isPresent() ? TradingPalette.down() : TradingPalette.TEXT_PRIMARY);
            y += ROW - 1;
            margin = buy.map(estimate -> MarketFormat.money(estimate.margin()) + " CRP").orElse("--");
            fee = buy.map(estimate -> MarketFormat.fee(estimate.fee()) + " CRP").orElse("--");
        }
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.order.margin", margin, TradingPalette.TEXT_PRIMARY);
        y += ROW - 1;
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.order.fee", fee, TradingPalette.TEXT_PRIMARY);
        return y + ROW - 1;
    }

    private static Optional<OpenEstimate> estimate(@Nullable TradingParams params, @Nullable MarketSnapshot snapshot, Side side,
            OptionalLong lots, int leverage) {
        if (params == null || snapshot == null || lots.isEmpty()) {
            return Optional.empty();
        }
        return TradingBook.estimateOpen(params, snapshot.lastPrice(), side, lots.getAsLong(), leverage);
    }

    /** 止盈止损页：止盈价、止损价并排两个输入框；其下是触发价来源开关和只减仓开关。 */
    private int extractProtection(GuiGraphicsExtractor graphics, int x, int width, int y, int mouseX, int mouseY,
            float partialTick) {
        int gap = OrderPanelLayout.FIELD_GAP;
        int half = (width - gap) / 2;
        triggerField(graphics, takeProfitBox, fields.takeProfit(), mouseX, mouseY, partialTick);
        triggerField(graphics, stopLossBox, fields.stopLoss(), mouseX, mouseY, partialTick);
        y += INPUT_HEIGHT + 3;
        sourceBox =new int[] {x, y, x + half, y + STEP_BUTTON};
        reduceBox = new int[] {x + half + gap, y, x + width, y + STEP_BUTTON};
        boolean mark = ClientTrading.triggerSource() == TriggerSource.MARK;
        plainButton(graphics, sourceBox, Component.translatable(mark
                ? "screen.creepercapital.trading.order.source_mark" : "screen.creepercapital.trading.order.source_last"),
                true, mouseX, mouseY);
        boolean reduce = ClientTrading.reduceOnly();
        graphics.fill(reduceBox[0], reduceBox[1], reduceBox[2], reduceBox[3],
                inside(reduceBox, mouseX, mouseY) ? BUTTON_HOVER : BUTTON_BACKGROUND);
        // 复选框：方框，勾选时里面填黄色
        int boxTop = reduceBox[1] + (STEP_BUTTON - 8) / 2;
        graphics.outline(reduceBox[0] + 3, boxTop, 8, 8, reduce ? TradingPalette.BRAND_YELLOW : TradingPalette.TEXT_SECONDARY);
        if (reduce) {
            graphics.fill(reduceBox[0] + 5, boxTop + 2, reduceBox[0] + 9, boxTop + 6, TradingPalette.BRAND_YELLOW);
        }
        graphics.text(font, Component.translatable("screen.creepercapital.trading.order.reduce_only"), reduceBox[0] + 14,
                reduceBox[1] + (STEP_BUTTON - font.lineHeight) / 2 + 1, TradingPalette.TEXT_PRIMARY, false);
        return y + STEP_BUTTON + 3;
    }

    private void triggerField(GuiGraphicsExtractor graphics, EditBox box, TradingLayout.Rect field, int mouseX, int mouseY,
            float partialTick) {
        graphics.fill(field.left(), field.top(), field.right(), field.bottom(), INPUT_BACKGROUND);
        graphics.outline(field.left(), field.top(), field.width(), field.height(),
                box.isFocused() ? TradingPalette.BRAND_YELLOW : TradingPalette.DIVIDER);
        placeBox(box, field, 0);
        box.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** 把输入框本体摆到画出来的区域里的文字区域（见 {@link OrderPanelLayout#textArea}）。 */
    private static void placeBox(EditBox box, TradingLayout.Rect field, int unitWidth) {
        TradingLayout.Rect text = OrderPanelLayout.textArea(field, unitWidth);
        box.setWidth(text.width());
        box.setX(text.left());
        box.setY(text.top());
    }

    /** 「止盈止损」页签的文字：设了止盈止损或只减仓时末尾带一个黄点，提示这一页有设置。 */
    private Component protectionLabel() {
        Component label = Component.translatable("screen.creepercapital.trading.order.tab.protection");
        boolean set = !takeProfitBox.getValue().isEmpty() || !stopLossBox.getValue().isEmpty() || ClientTrading.reduceOnly();
        return set ? label.copy().append(Component.literal(" *").withColor(TradingPalette.BRAND_YELLOW)) : label;
    }

    private void plainButton(GuiGraphicsExtractor graphics, int[] box, Component label, boolean enabled, int mouseX, int mouseY) {
        graphics.fill(box[0], box[1], box[2], box[3], enabled && inside(box, mouseX, mouseY) ? BUTTON_HOVER : BUTTON_BACKGROUND);
        graphics.centeredText(font, label, (box[0] + box[2]) / 2, box[1] + (box[3] - box[1] - font.lineHeight) / 2 + 1,
                enabled ? TradingPalette.TEXT_PRIMARY : DISABLED);
    }

    /** 一行「名称 ……… 数值」：数值靠右完整显示，名称放不下时截断。 */
    private void infoRow(GuiGraphicsExtractor graphics, int x, int right, int y, String labelKey, String value, int valueColor) {
        int valueWidth = font.width(value);
        String label = GuiText.fit(font, Component.translatable(labelKey).getString(), right - x - valueWidth - 4);
        graphics.text(font, label, x, y, TradingPalette.TEXT_SECONDARY, false);
        graphics.text(font, value, right - valueWidth, y, valueColor, false);
    }

    /** 市价 / 限价页签：当前页黄字加下划线，鼠标悬停的底色变亮。 */
    private void modeTab(GuiGraphicsExtractor graphics, int[] box, String labelKey, boolean selected, int mouseX, int mouseY) {
        modeTab(graphics, box, Component.translatable(labelKey), selected, mouseX, mouseY);
    }

    private void modeTab(GuiGraphicsExtractor graphics, int[] box, Component label, boolean selected, int mouseX, int mouseY) {
        boolean hovered = inside(box, mouseX, mouseY);
        if (hovered && !selected) {
            graphics.fill(box[0], box[1], box[2], box[3], BUTTON_BACKGROUND);
        }
        graphics.text(font, label, box[0] + 6, box[1] + 2, selected ? TradingPalette.BRAND_YELLOW
                : hovered ? TradingPalette.TEXT_PRIMARY : TradingPalette.TEXT_SECONDARY, false);
        if (selected) {
            graphics.horizontalLine(box[0] + 6, box[2] - 7, box[3] - 1, TradingPalette.BRAND_YELLOW);
        }
    }

    /** 面板上填的委托价；没填或格式不对为空。 */
    private OptionalDouble limitPrice() {
        String text = priceBox.getValue().trim();
        if (text.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            double price = Double.parseDouble(text);
            return price > 0 && Double.isFinite(price) ? OptionalDouble.of(price) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private void stepButton(GuiGraphicsExtractor graphics, int[] box, String label, boolean enabled, int mouseX, int mouseY) {
        graphics.fill(box[0], box[1], box[2], box[3], enabled && inside(box, mouseX, mouseY) ? BUTTON_HOVER : BUTTON_BACKGROUND);
        graphics.centeredText(font, label, (box[0] + box[2]) / 2, box[1] + (box[3] - box[1] - font.lineHeight) / 2 + 1,
                enabled ? TradingPalette.TEXT_PRIMARY : DISABLED);
    }

    private void colorButton(GuiGraphicsExtractor graphics, int[] box, Component label, int color, int mouseX, int mouseY) {
        int shade = inside(box, mouseX, mouseY) ? brighten(color) : color;
        graphics.fill(box[0], box[1], box[2], box[3], shade);
        graphics.centeredText(font, label, (box[0] + box[2]) / 2, box[1] + (box[3] - box[1] - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
    }

    private static int brighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 24);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 24);
        int b = Math.min(255, (argb & 0xFF) + 24);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static boolean inside(int[] box, double x, double y) {
        return x >= box[0] && x < box[2] && y >= box[1] && y < box[3];
    }

    /** 处理一次点击；返回点击是否落在面板的按钮上（落在输入框上的点击交给输入框自己处理）。 */
    boolean mouseClicked(double x, double y, String symbol) {
        if (inside(modeBox, x, y)) {
            ClientTrading.toggleMarginMode(symbol);
            return true;
        }
        if (inside(marketTab, x, y)) {
            ClientTrading.setLimitMode(false);
            return true;
        }
        if (inside(limitTab, x, y)) {
            if (!ClientTrading.limitMode() && priceBox.getValue().isEmpty()) {
                // 第一次切到限价：委托价预填最新价格，省得从零输入
                ClientMarket.snapshot(symbol).ifPresent(snapshot -> priceBox.setValue(
                        String.format(Locale.ROOT, "%.2f", snapshot.lastPrice())));
            }
            ClientTrading.setLimitMode(true);
            return true;
        }
        if (inside(estimateTab, x, y)) {
            ClientTrading.setProtectionPage(false);
            return true;
        }
        if (inside(protectionTab, x, y)) {
            ClientTrading.setProtectionPage(true);
            return true;
        }
        if (ClientTrading.protectionPage() && inside(sourceBox, x, y)) {
            ClientTrading.toggleTriggerSource();
            return true;
        }
        if (ClientTrading.protectionPage() && inside(reduceBox, x, y)) {
            ClientTrading.setReduceOnly(!ClientTrading.reduceOnly());
            return true;
        }
        if (inside(protectBox, x, y)) {
            protect(symbol);
            return true;
        }
        if (inside(minus, x, y)) {
            ClientTrading.lowerLeverage(symbol);
            return true;
        }
        if (inside(plus, x, y)) {
            ClientTrading.raiseLeverage(symbol);
            return true;
        }
        if (inside(buyBox, x, y)) {
            open(OrderPayload.Action.OPEN_LONG, symbol);
            return true;
        }
        if (inside(sellBox, x, y)) {
            open(OrderPayload.Action.OPEN_SHORT, symbol);
            return true;
        }
        if (inside(closeBox, x, y)) {
            close(symbol);
            return true;
        }
        return false;
    }

    /** 按面板上填的数量开仓（限价模式还带上委托价）；数量或价格不合法时在面板上提示，不发给服务端。 */
    private void open(OrderPayload.Action action, String symbol) {
        TradingParams params = ClientTrading.params(symbol).orElse(null);
        if (params == null) {
            return;
        }
        OptionalLong lots = Quantities.parseLots(quantityBox.getValue(), params.quantityDecimals());
        if (lots.isEmpty()) {
            ClientTrading.showLocalError(Component.translatable("message.creepercapital.order.invalid_quantity_input",
                    params.quantityDecimals()));
            return;
        }
        OptionalDouble takeProfit = triggerPrice(takeProfitBox);
        OptionalDouble stopLoss = triggerPrice(stopLossBox);
        if (takeProfit.isEmpty() || stopLoss.isEmpty()) {
            ClientTrading.showLocalError(Component.translatable("message.creepercapital.order.invalid_trigger_input"));
            return;
        }
        if (!ClientTrading.limitMode()) {
            ClientTrading.sendOrder(action, symbol, lots.getAsLong(), OrderPayload.Execution.MARKET, 0,
                    takeProfit.getAsDouble(), stopLoss.getAsDouble());
            return;
        }
        OptionalDouble price = limitPrice();
        if (price.isEmpty()) {
            ClientTrading.showLocalError(Component.translatable("message.creepercapital.order.invalid_price_input"));
            return;
        }
        ClientTrading.sendOrder(action, symbol, lots.getAsLong(), OrderPayload.Execution.LIMIT, price.getAsDouble(),
                takeProfit.getAsDouble(), stopLoss.getAsDouble());
    }

    /** 止盈 / 止损输入框里的触发价：没填是 0（不设），填了但格式不对为空。 */
    private static OptionalDouble triggerPrice(EditBox box) {
        String text = box.getValue().trim();
        if (text.isEmpty()) {
            return OptionalDouble.of(0);
        }
        try {
            double price = Double.parseDouble(text);
            return price > 0 && Double.isFinite(price) ? OptionalDouble.of(price) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** 给现有仓位设止盈止损：数量留空就是整个仓位（固定为此刻的仓位数量），至少要填一项。 */
    private void protect(String symbol) {
        Position position = ClientTrading.position(symbol).orElse(null);
        TradingParams params = ClientTrading.params(symbol).orElse(null);
        if (position == null || params == null) {
            return;
        }
        OptionalDouble takeProfit = triggerPrice(takeProfitBox);
        OptionalDouble stopLoss = triggerPrice(stopLossBox);
        if (takeProfit.isEmpty() || stopLoss.isEmpty()) {
            ClientTrading.showLocalError(Component.translatable("message.creepercapital.order.invalid_trigger_input"));
            return;
        }
        if (takeProfit.getAsDouble() == 0 && stopLoss.getAsDouble() == 0) {
            ClientTrading.showLocalError(Component.translatable("message.creepercapital.order.need_trigger_input"));
            return;
        }
        long lots = position.lots();
        String text = quantityBox.getValue().trim();
        if (!text.isEmpty()) {
            OptionalLong parsed = Quantities.parseLots(text, params.quantityDecimals());
            if (parsed.isEmpty()) {
                ClientTrading.showLocalError(Component.translatable("message.creepercapital.order.invalid_quantity_input",
                        params.quantityDecimals()));
                return;
            }
            lots = parsed.getAsLong();
        }
        ClientTrading.sendOrder(OrderPayload.Action.PROTECT, symbol, lots, OrderPayload.Execution.MARKET, 0,
                takeProfit.getAsDouble(), stopLoss.getAsDouble());
    }

    /** 市价平仓：数量留空是全平，填了就平这么多。 */
    private void close(String symbol) {
        Position position = ClientTrading.position(symbol).orElse(null);
        TradingParams params = ClientTrading.params(symbol).orElse(null);
        if (position == null || params == null) {
            return;
        }
        String text = quantityBox.getValue().trim();
        long lots = position.lots();
        if (!text.isEmpty()) {
            OptionalLong parsed = Quantities.parseLots(text, params.quantityDecimals());
            if (parsed.isEmpty()) {
                ClientTrading.showLocalError(Component.translatable("message.creepercapital.order.invalid_quantity_input",
                        params.quantityDecimals()));
                return;
            }
            lots = parsed.getAsLong();
        }
        if (!ClientTrading.limitMode()) {
            ClientTrading.sendOrder(OrderPayload.Action.CLOSE, symbol, lots, OrderPayload.Execution.MARKET, 0, 0, 0);
            return;
        }
        // 限价平仓就是只减仓限价单：价格穿越委托价时按委托价平仓
        OptionalDouble price = limitPrice();
        if (price.isEmpty()) {
            ClientTrading.showLocalError(Component.translatable("message.creepercapital.order.invalid_price_input"));
            return;
        }
        ClientTrading.sendOrder(OrderPayload.Action.CLOSE, symbol, lots, OrderPayload.Execution.LIMIT, price.getAsDouble(), 0, 0);
    }

    private static String underlyingName(String symbol) {
        return symbol.endsWith("CRP") ? symbol.substring(0, symbol.length() - 3) : symbol;
    }
}
