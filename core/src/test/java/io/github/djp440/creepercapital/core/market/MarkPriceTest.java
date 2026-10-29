package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarkPriceTest {
    private static final double EPSILON = 1e-9;
    /** 距结算还剩 4 小时，正好半个周期。 */
    private static final long HALF_INTERVAL = 4 * 3600L;

    // 指数 1000、资金费率 0.01%、半个周期 → 价格 1 = 1000 × (1 + 0.0001 × 0.5) = 1000.05
    // 基差均线 0.8 → 价格 2 = 1000.8

    @Test
    @DisplayName("最新价格居中时取最新价格：候选 1000.05 / 1000.8 / 1000.3 → 1000.3")
    void lastPriceInTheMiddle() {
        assertEquals(1000.3, MarkPrice.of(1000.0, 0.0001, HALF_INTERVAL, 0.8, 1000.3), EPSILON);
    }

    @Test
    @DisplayName("最新价格偏高时取指数 + 基差均线：候选 1000.05 / 1000.8 / 1002 → 1000.8")
    void basisPriceInTheMiddle() {
        assertEquals(1000.8, MarkPrice.of(1000.0, 0.0001, HALF_INTERVAL, 0.8, 1002.0), EPSILON);
    }

    @Test
    @DisplayName("最新价格偏低时取资金费率推算的价格：候选 1000.05 / 1000.8 / 999 → 1000.05")
    void fundingPriceInTheMiddle() {
        assertEquals(1000.05, MarkPrice.of(1000.0, 0.0001, HALF_INTERVAL, 0.8, 999.0), EPSILON);
    }

    @Test
    @DisplayName("结算点上距结算时间为 0，价格 1 退化为指数价格：候选 2000 / 1990 / 1995 → 1995")
    void fundingPriceIsIndexAtSettlement() {
        assertEquals(1995.0, MarkPrice.of(2000.0, 0.003, 0L, -10.0, 1995.0), EPSILON);
        assertEquals(2000.0, MarkPrice.of(2000.0, 0.003, 0L, -10.0, 2010.0), EPSILON);
    }
}
