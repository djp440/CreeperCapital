package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FundingTest {
    private static final double EPSILON = 1e-12;

    @Test
    @DisplayName("溢价指数均值落在 [-0.04%, 0.06%] 内时，资金费率恰好等于利率 0.01%")
    void rateEqualsInterestWhenPremiumIsNearInterest() {
        assertEquals(0.0001, Funding.rate(0.0), EPSILON);
        assertEquals(0.0001, Funding.rate(0.0003), EPSILON);
        assertEquals(0.0001, Funding.rate(-0.0002), EPSILON);
    }

    @Test
    @DisplayName("钳制边界：P = 0.06% 与 P = -0.04% 时 I − P 恰好触及 ±0.05%，费率仍为 0.01%")
    void clampBoundariesStillGiveInterest() {
        assertEquals(0.0001, Funding.rate(0.0006), EPSILON);
        assertEquals(0.0001, Funding.rate(-0.0004), EPSILON);
    }

    @Test
    @DisplayName("溢价超出钳制区间时，资金费率 = P ∓ 0.05%：P = 0.2% → 0.15%，P = -0.3% → -0.25%")
    void rateFollowsPremiumBeyondClamp() {
        assertEquals(0.0015, Funding.rate(0.002), EPSILON);
        assertEquals(-0.0025, Funding.rate(-0.003), EPSILON);
    }

    @Test
    @DisplayName("每 8 市场小时结算一次：倒计时在结算点为 0，下一步重新从 07:59:54 开始倒数")
    void countdownResetsAtEachSettlement() {
        assertEquals(0L, Funding.secondsUntilNext(MarketTime.ofStep(0)));
        assertEquals(28_794L, Funding.secondsUntilNext(MarketTime.ofStep(1)));
        assertEquals(6L, Funding.secondsUntilNext(MarketTime.ofStep(4_799)));
        assertEquals(0L, Funding.secondsUntilNext(MarketTime.ofStep(4_800)));
        assertEquals(28_794L, Funding.secondsUntilNext(MarketTime.ofStep(4_801)));
        // 第 2 日 03:00:00 = 97200 秒，下一个结算点是第 2 日 08:00:00
        assertEquals(5 * 3600L, Funding.secondsUntilNext(new MarketTime(97_200L)));
    }
}
