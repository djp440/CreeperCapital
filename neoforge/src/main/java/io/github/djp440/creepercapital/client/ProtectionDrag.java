package io.github.djp440.creepercapital.client;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.Protection;
import io.github.djp440.creepercapital.core.trading.Side;

/**
 * 在图表上拖拽设止盈止损（纯函数，不碰 Minecraft）：鼠标纵坐标 → 触发价、拖出的是止盈还是止损、价格是否合法；提交用的载荷见 {@link io.github.djp440.creepercapital.network.OrderPayload#protect}。
 * <p>
 * 止盈 / 止损按<b>参考价</b>（触发价来源对应的最新价格或标记价格）区分：多仓往参考价之上拖是止盈、之下是止损，空仓相反——
 * 与服务端的校验（{@link Protection#validTrigger}）是同一条规则，所以拖出来的线只要不正好落在参考价上就总能提交。
 * 改已有的止盈 / 止损时类型不变，拖过参考价就不合法。
 */
final class ProtectionDrag {
    /** 松开时离起点不到这么多像素，算拖回原处（取消）。 */
    static final double DRAG_BACK_PIXELS = 3;

    private ProtectionDrag() {
    }

    /** 纵坐标对应的触发价，按显示精度取到 2 位小数（看到的就是提交的）；不是正数返回 NaN。 */
    static double priceAt(PriceScale scale, double y) {
        double raw = scale.high() - (scale.high() - scale.low()) * (y - scale.top()) / (scale.height() - 1);
        if (!Double.isFinite(raw)) {
            return Double.NaN;
        }
        double rounded = BigDecimal.valueOf(raw).setScale(2, RoundingMode.HALF_UP).doubleValue();
        return rounded > 0 ? rounded : Double.NaN;
    }

    /** 新拖出的线是止盈还是止损：多仓在参考价之上是止盈、之下是止损，空仓相反；正好在参考价上（或价格不合法）为 null。 */
    static @Nullable OrderType typeFor(Side side, double reference, double price) {
        if (Protection.validTrigger(side, OrderType.TAKE_PROFIT, price, reference)) {
            return OrderType.TAKE_PROFIT;
        }
        if (Protection.validTrigger(side, OrderType.STOP_LOSS, price, reference)) {
            return OrderType.STOP_LOSS;
        }
        return null;
    }

    /** 这个触发价能否提交（与服务端同一条规则）。 */
    static boolean valid(Side side, OrderType type, double price, double reference) {
        return Protection.validTrigger(side, type, price, reference);
    }

    /** 松开的位置离起点（原来那条线）不到 {@link #DRAG_BACK_PIXELS} 像素：算拖回原处。 */
    static boolean draggedBack(double startY, double y) {
        return Math.abs(y - startY) < DRAG_BACK_PIXELS;
    }
}
