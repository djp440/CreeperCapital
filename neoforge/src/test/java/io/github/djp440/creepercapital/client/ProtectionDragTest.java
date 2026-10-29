package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.Side;

/**
 * 拖拽持仓线 tag 设止盈止损：纵坐标 → 价格、拖出的是止盈还是止损、价格是否合法（与服务端同一条规则）。
 * 提交的载荷（{@code OrderPayload.protect}）被服务端接受 / 拒绝由 GameTest 覆盖（测试类路径里没有 Minecraft）。
 */
class ProtectionDragTest {
    /** 纵坐标 100 ↔ 价格 300，纵坐标 300 ↔ 价格 100，每像素 1 CRP。 */
    private static final PriceScale SCALE = new PriceScale(100, 201, 300, 100);

    @Test
    @DisplayName("纵坐标换算成价格，按显示精度取到 2 位小数")
    void convertsYToARoundedPrice() {
        assertEquals(300.0, ProtectionDrag.priceAt(SCALE, 100));
        assertEquals(100.0, ProtectionDrag.priceAt(SCALE, 300));
        assertEquals(199.5, ProtectionDrag.priceAt(SCALE, 200.5));
        // 3 位以上的小数取整到分
        PriceScale fine = new PriceScale(0, 4, 1.0, 0.0);
        assertEquals(0.67, ProtectionDrag.priceAt(fine, 1));
    }

    @Test
    @DisplayName("拖到零价或负价：不是合法价格（NaN）")
    void nonPositivePricesAreNotPrices() {
        PriceScale nearZero = new PriceScale(0, 101, 50, -50);
        assertTrue(Double.isNaN(ProtectionDrag.priceAt(nearZero, 50)), "价格 0");
        assertTrue(Double.isNaN(ProtectionDrag.priceAt(nearZero, 90)), "负价格");
    }

    @Test
    @DisplayName("新拖出的线：多仓往参考价之上是止盈、之下是止损，空仓相反；正好在参考价上不成立")
    void newLineTypeFollowsTheProfitDirection() {
        assertEquals(OrderType.TAKE_PROFIT, ProtectionDrag.typeFor(Side.LONG, 200, 210));
        assertEquals(OrderType.STOP_LOSS, ProtectionDrag.typeFor(Side.LONG, 200, 190));
        assertEquals(OrderType.TAKE_PROFIT, ProtectionDrag.typeFor(Side.SHORT, 200, 190));
        assertEquals(OrderType.STOP_LOSS, ProtectionDrag.typeFor(Side.SHORT, 200, 210));
        assertNull(ProtectionDrag.typeFor(Side.LONG, 200, 200));
        assertNull(ProtectionDrag.typeFor(Side.LONG, 200, Double.NaN));
    }

    @Test
    @DisplayName("价格合法性与服务端一致：多仓止盈须高于、止损须低于参考价，空仓相反；价格须为正数")
    void validityMatchesTheServerRule() {
        assertTrue(ProtectionDrag.valid(Side.LONG, OrderType.TAKE_PROFIT, 210, 200));
        assertFalse(ProtectionDrag.valid(Side.LONG, OrderType.TAKE_PROFIT, 190, 200), "改单时把止盈拖过了参考价");
        assertFalse(ProtectionDrag.valid(Side.LONG, OrderType.TAKE_PROFIT, 200, 200), "等于参考价会立即触发");
        assertTrue(ProtectionDrag.valid(Side.LONG, OrderType.STOP_LOSS, 190, 200));
        assertFalse(ProtectionDrag.valid(Side.LONG, OrderType.STOP_LOSS, 210, 200));
        assertTrue(ProtectionDrag.valid(Side.SHORT, OrderType.TAKE_PROFIT, 190, 200));
        assertFalse(ProtectionDrag.valid(Side.SHORT, OrderType.STOP_LOSS, 190, 200));
        assertFalse(ProtectionDrag.valid(Side.LONG, OrderType.STOP_LOSS, Double.NaN, 200));
        assertFalse(ProtectionDrag.valid(Side.LONG, OrderType.STOP_LOSS, 190, Double.NaN), "没有参考价");
    }

    @Test
    @DisplayName("松开时离起点不到 3 像素算拖回原处（取消）")
    void releasingNearTheStartCancels() {
        assertTrue(ProtectionDrag.draggedBack(200, 202.5));
        assertTrue(ProtectionDrag.draggedBack(200, 197.5));
        assertFalse(ProtectionDrag.draggedBack(200, 203));
    }
}
