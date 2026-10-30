package io.github.djp440.creepercapital.client;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.client.TradingLayout.Rect;

/**
 * 下单面板里输入框的位置与命中判定（纯计算，不依赖 Minecraft，单元测试在窗口 × GUI 缩放矩阵上验证）。
 * <p>
 * 输入框画出来的是一整块带边框的区域（含内边距与右侧单位文字），但原版 {@code EditBox} 自己的点击区域只有里面的文字区域，
 * 点在边距或单位文字上会落空、点不进去（#39）。所以点击由这里按「画出来的区域」判定，再把坐标夹回文字区域交给输入框定位光标。
 */
final class OrderPanelLayout {
    static final int PAD = 8;
    static final int ROW = 12;
    static final int STEP_BUTTON = 14;
    static final int INPUT_HEIGHT = 16;
    /** 输入框内文字区域距绘制区域左 / 上边缘的距离，右侧留白与单位文字的间距。 */
    static final int TEXT_INSET_X = 4;
    static final int TEXT_INSET_Y = 4;
    static final int UNIT_GAP = 4;
    /** 两个并排输入框（止盈价 / 止损价）之间的间距。 */
    static final int FIELD_GAP = 6;

    enum Input {
        PRICE, QUANTITY, TAKE_PROFIT, STOP_LOSS
    }

    /**
     * 各输入框画出来的区域，不显示的为 {@link Rect#EMPTY}。
     *
     * @param tabsY   「预估 / 止盈止损」页签行的 y（页签文字行顶，页签框再向上留 2）
     * @param infoTop 页签行之下、预估信息或止盈止损输入框的起始 y
     */
    record Fields(Rect price, Rect quantity, Rect takeProfit, Rect stopLoss, int tabsY, int infoTop) {
        /** 点 {@code (x, y)} 落在哪个输入框画出来的区域里；都不在返回 {@code null}。 */
        @Nullable
        Input hitTest(double x, double y) {
            if (price.contains(x, y)) {
                return Input.PRICE;
            }
            if (quantity.contains(x, y)) {
                return Input.QUANTITY;
            }
            if (takeProfit.contains(x, y)) {
                return Input.TAKE_PROFIT;
            }
            if (stopLoss.contains(x, y)) {
                return Input.STOP_LOSS;
            }
            return null;
        }
    }

    private OrderPanelLayout() {
    }

    /**
     * @param panel          下单面板卡片
     * @param limit          限价模式（多一行委托价输入框）
     * @param protectionPage 止盈止损页（页签行下面是止盈价、止损价两个输入框）
     */
    static Fields compute(Rect panel, boolean limit, boolean protectionPage) {
        int x = panel.left() + PAD;
        int width = panel.width() - PAD * 2;
        int y = panel.top() + 6;
        // 市价 / 限价页签与保证金模式按钮一行，杠杆一行
        y += ROW + 2;
        y += STEP_BUTTON + 4;
        Rect price = Rect.EMPTY;
        if (limit) {
            price = new Rect(x, y, x + width, y + INPUT_HEIGHT);
            y += INPUT_HEIGHT + 5;
        }
        Rect quantity = new Rect(x, y, x + width, y + INPUT_HEIGHT);
        y += INPUT_HEIGHT + 5;
        int tabsY = y;
        int infoTop = y + ROW + 3;
        Rect takeProfit = Rect.EMPTY;
        Rect stopLoss = Rect.EMPTY;
        if (protectionPage) {
            int half = (width - FIELD_GAP) / 2;
            takeProfit = new Rect(x, infoTop, x + half, infoTop + INPUT_HEIGHT);
            stopLoss = new Rect(x + half + FIELD_GAP, infoTop, x + width, infoTop + INPUT_HEIGHT);
        }
        return new Fields(price, quantity, takeProfit, stopLoss, tabsY, infoTop);
    }

    /** 输入框内真正放文字（也是 {@code EditBox} 的矩形）的区域；{@code unitWidth} 是右侧单位文字的宽度，没有单位传 0。 */
    static Rect textArea(Rect field, int unitWidth) {
        int right = field.right() - TEXT_INSET_X - (unitWidth > 0 ? unitWidth + UNIT_GAP : 0);
        int left = field.left() + TEXT_INSET_X;
        return new Rect(left, field.top() + TEXT_INSET_Y, Math.max(left, right), field.bottom() - 2);
    }

    /** 把点击的 x 夹进文字区域，作为交给输入框定位光标的坐标。 */
    static double clampX(Rect text, double x) {
        return Math.max(text.left(), Math.min(x, text.right() - 1));
    }

    static double clampY(Rect text, double y) {
        return Math.max(text.top(), Math.min(y, text.bottom() - 1));
    }
}
