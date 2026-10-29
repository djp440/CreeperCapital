package io.github.djp440.creepercapital.client;

import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Quantities;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.network.OrderPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * 下单面板（币安「市价」下单区）：切逐仓 / 全仓、选杠杆、填数量，点「开多」「开空」市价开仓，或点「平仓」市价平掉当前合约的仓位。
 * <p>
 * 已有仓位时点杠杆的 − / + 是直接调整仓位的杠杆（加仓的杠杆必须和仓位一致），有仓位时不能切换保证金模式；
 * 「平仓」留空数量就是全平。面板上的预估成交价、保证金和手续费只是提示，
 * 实际成交以服务端为准；下单结果（成交或拒单原因）由服务端回报，显示在面板底部。
 */
final class OrderPanel {
    static final int WIDTH = 168;
    private static final int PAD = 8;
    private static final int ROW = 12;
    private static final int BUTTON_HEIGHT = 18;
    private static final int STEP_BUTTON = 14;
    private static final int INPUT_HEIGHT = 16;
    private static final int BUTTON_BACKGROUND = 0xFF2B3139;
    private static final int BUTTON_HOVER = 0xFF3C4552;
    private static final int INPUT_BACKGROUND = 0xFF0B0E11;
    private static final int DISABLED = 0xFF474D57;

    private final Font font;
    private final EditBox quantityBox;
    /** 每帧渲染时记下各按钮的区域，点击判定要用：{左, 上, 右, 下}。 */
    private int[] modeBox = new int[4];
    private int[] minus = new int[4];
    private int[] plus = new int[4];
    private int[] buyBox = new int[4];
    private int[] sellBox = new int[4];
    private int[] closeBox = new int[4];

    OrderPanel(Font font) {
        this.font = font;
        this.quantityBox = new EditBox(font, 0, 0, WIDTH - PAD * 2 - 8, INPUT_HEIGHT - 6,
                Component.translatable("screen.creepercapital.trading.order.quantity_field"));
        quantityBox.setBordered(false);
        quantityBox.setMaxLength(24);
        quantityBox.setFilter(text -> text.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.'));
        quantityBox.setHint(Component.translatable("screen.creepercapital.trading.order.quantity_hint"));
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
        graphics.fill(left, top, right, bottom, BinancePalette.PANEL);
        graphics.outline(left, top, right - left, bottom - top, BinancePalette.DIVIDER);
        TradingParams params = ClientTrading.params(symbol).orElse(null);
        Position position = ClientTrading.position(symbol).orElse(null);
        int x = left + PAD;
        int width = right - left - PAD * 2;
        int y = top + 6;

        graphics.text(font, Component.translatable("screen.creepercapital.trading.order.title"), x, y, BinancePalette.BRAND_YELLOW, false);
        // 保证金模式：点一下在「逐仓」「全仓」之间切换（有仓位或委托时服务端会拒绝并提示）
        Component modeLabel = Component.translatable(ClientTrading.marginMode(symbol) == MarginMode.CROSS
                ? "screen.creepercapital.trading.order.cross" : "screen.creepercapital.trading.order.isolated");
        int modeWidth = font.width(modeLabel) + 10;
        modeBox = new int[] {right - PAD - modeWidth, y - 3, right - PAD, y + font.lineHeight + 1};
        graphics.fill(modeBox[0], modeBox[1], modeBox[2], modeBox[3],
                inside(modeBox, mouseX, mouseY) ? BUTTON_HOVER : BUTTON_BACKGROUND);
        graphics.centeredText(font, modeLabel, (modeBox[0] + modeBox[2]) / 2, y, BinancePalette.TEXT_PRIMARY);
        y += ROW + 2;

        // 杠杆：「−」「倍数」「+」，有仓位时直接调整仓位的杠杆（受当前档位上限和可用保证金约束，由服务端校验）
        int leverage = ClientTrading.leverage(symbol);
        graphics.text(font, Component.translatable("screen.creepercapital.trading.order.leverage"), x, y + 3, BinancePalette.TEXT_SECONDARY, false);
        String leverageText = leverage + "x";
        int plusLeft = right - PAD - STEP_BUTTON;
        int textRight = plusLeft - 4;
        int minusLeft = textRight - Math.max(font.width("125x"), font.width(leverageText)) - 4 - STEP_BUTTON;
        minus = new int[] {minusLeft, y, minusLeft + STEP_BUTTON, y + STEP_BUTTON};
        plus = new int[] {plusLeft, y, plusLeft + STEP_BUTTON, y + STEP_BUTTON};
        stepButton(graphics, minus, "−", leverage > 1, mouseX, mouseY);
        stepButton(graphics, plus, "+", params != null && leverage < params.maxLeverage(), mouseX, mouseY);
        graphics.centeredText(font, leverageText, (minus[2] + plus[0]) / 2, y + 3, BinancePalette.TEXT_PRIMARY);
        y += STEP_BUTTON + 4;

        // 数量：输入框右侧标出标的资源的名称作单位
        String unit = underlyingName(symbol);
        graphics.fill(x, y, x + width, y + INPUT_HEIGHT, INPUT_BACKGROUND);
        graphics.outline(x, y, width, INPUT_HEIGHT, quantityBox.isFocused() ? BinancePalette.BRAND_YELLOW : BinancePalette.DIVIDER);
        int unitWidth = font.width(unit);
        graphics.text(font, unit, x + width - 4 - unitWidth, y + (INPUT_HEIGHT - font.lineHeight) / 2 + 1,
                BinancePalette.TEXT_SECONDARY, false);
        quantityBox.setWidth(width - 12 - unitWidth);
        quantityBox.setX(x + 4);
        quantityBox.setY(y + 4);
        quantityBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        y += INPUT_HEIGHT + 5;

        // 预估：可用余额、成交价、保证金、手续费
        OptionalLong lots = params == null ? OptionalLong.empty() : Quantities.parseLots(quantityBox.getValue(), params.quantityDecimals());
        y = extractInfo(graphics, x, right - PAD, y, params, snapshot, lots, leverage);
        if (position != null) {
            Component held = Component.translatable("screen.creepercapital.trading.order.holding",
                    Component.translatable(position.side() == Side.LONG
                            ? "screen.creepercapital.trading.side.long" : "screen.creepercapital.trading.side.short"),
                    Quantities.format(position.lots(), position.quantityDecimals()), position.leverage());
            graphics.text(font, held, x, y, position.side() == Side.LONG
                    ? BinancePalette.UP : BinancePalette.DOWN, false);
            y += ROW - 1;
        }
        y += 1;

        // 开多 / 开空 / 平仓
        int gap = 6;
        int half = (width - gap) / 2;
        buyBox = new int[] {x, y, x + half, y + BUTTON_HEIGHT};
        sellBox = new int[] {x + half + gap, y, x + width, y + BUTTON_HEIGHT};
        colorButton(graphics, buyBox, Component.translatable("screen.creepercapital.trading.order.buy"), BinancePalette.UP, mouseX, mouseY);
        colorButton(graphics, sellBox, Component.translatable("screen.creepercapital.trading.order.sell"), BinancePalette.DOWN, mouseX, mouseY);
        y += BUTTON_HEIGHT + 4;
        closeBox = new int[] {x, y, x + width, y + BUTTON_HEIGHT - 2};
        boolean canClose = position != null;
        graphics.fill(closeBox[0], closeBox[1], closeBox[2], closeBox[3],
                canClose && inside(closeBox, mouseX, mouseY) ? BUTTON_HOVER : BUTTON_BACKGROUND);
        graphics.centeredText(font, Component.translatable("screen.creepercapital.trading.order.close"),
                (closeBox[0] + closeBox[2]) / 2, closeBox[1] + (closeBox[3] - closeBox[1] - font.lineHeight) / 2 + 1,
                canClose ? BinancePalette.TEXT_PRIMARY : DISABLED);
        y += BUTTON_HEIGHT + 2;

        // 下单结果
        ClientTrading.Message message = ClientTrading.lastResult();
        if (message != null) {
            int color = message.success() ? BinancePalette.UP : BinancePalette.DOWN;
            // 只画面板放得下的行，免得长消息越出面板底部
            for (FormattedCharSequence line : font.split(message.text(), width)) {
                if (y + font.lineHeight > bottom - 3) {
                    break;
                }
                graphics.text(font, line, x, y, color, false);
                y += font.lineHeight + 1;
            }
        }
    }

    /** 可用余额与预估成交价、保证金、手续费，每项一行：名称在左、数值在右。 */
    private int extractInfo(GuiGraphicsExtractor graphics, int x, int right, int y, @Nullable TradingParams params,
            @Nullable MarketSnapshot snapshot, OptionalLong lots, int leverage) {
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.order.available",
                ClientAccount.assets().availableBalance().toDisplayString() + " CRP");
        y += ROW - 1;
        String price = snapshot == null || params == null ? "--" : MarketFormat.price(snapshot.lastPrice());
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.order.price", price);
        y += ROW - 1;
        String margin = "--";
        String fee = "--";
        if (snapshot != null && params != null && lots.isPresent()) {
            double notional = snapshot.lastPrice() * lots.getAsLong() / Math.pow(10, params.quantityDecimals());
            margin = MarketFormat.price(notional / leverage) + " CRP";
            fee = MarketFormat.price(notional * TradingBook.TAKER_FEE_RATE) + " CRP";
        }
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.order.margin", margin);
        y += ROW - 1;
        infoRow(graphics, x, right, y, "screen.creepercapital.trading.order.fee", fee);
        return y + ROW - 1;
    }

    private void infoRow(GuiGraphicsExtractor graphics, int x, int right, int y, String labelKey, String value) {
        graphics.text(font, Component.translatable(labelKey), x, y, BinancePalette.TEXT_SECONDARY, false);
        graphics.text(font, value, right - font.width(value), y, BinancePalette.TEXT_PRIMARY, false);
    }

    private void stepButton(GuiGraphicsExtractor graphics, int[] box, String label, boolean enabled, int mouseX, int mouseY) {
        graphics.fill(box[0], box[1], box[2], box[3], enabled && inside(box, mouseX, mouseY) ? BUTTON_HOVER : BUTTON_BACKGROUND);
        graphics.centeredText(font, label, (box[0] + box[2]) / 2, box[1] + (box[3] - box[1] - font.lineHeight) / 2 + 1,
                enabled ? BinancePalette.TEXT_PRIMARY : DISABLED);
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

    /** 按面板上填的数量市价开仓；数量不合法时在面板上提示，不发给服务端。 */
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
        ClientTrading.send(action, symbol, lots.getAsLong());
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
        ClientTrading.send(OrderPayload.Action.CLOSE, symbol, lots);
    }

    private static String underlyingName(String symbol) {
        return symbol.endsWith("CRP") ? symbol.substring(0, symbol.length() - 3) : symbol;
    }
}
