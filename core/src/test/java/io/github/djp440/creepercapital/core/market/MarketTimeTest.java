package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketTimeTest {

    @Test
    @DisplayName("第 0 个行情步是市场日历的第 1 日 00:00:00")
    void stepZeroIsDayOneMidnight() {
        MarketTime time = MarketTime.ofStep(0);

        assertEquals(0L, time.totalSeconds());
        assertEquals(1L, time.day());
        assertEquals(0, time.hour());
        assertEquals(0, time.minute());
        assertEquals(0, time.second());
    }

    @Test
    @DisplayName("每个行情步前进 6 市场秒，满 60 秒进位到分钟")
    void eachStepAdvancesSixMarketSeconds() {
        assertEquals(6L, MarketTime.ofStep(1).totalSeconds());
        assertEquals(6, MarketTime.ofStep(1).second());

        MarketTime tenSteps = MarketTime.ofStep(10);
        assertEquals(1, tenSteps.minute());
        assertEquals(0, tenSteps.second());
    }

    @Test
    @DisplayName("14400 个行情步正好一个市场日：进位到第 2 日 00:00:00")
    void oneMarketDayIsFourteenThousandFourHundredSteps() {
        MarketTime time = MarketTime.ofStep(14_400);

        assertEquals(2L, time.day());
        assertEquals(0, time.hour());
        assertEquals(0, time.minute());
        assertEquals(0, time.second());

        MarketTime beforeMidnight = MarketTime.ofStep(14_399);
        assertEquals(1L, beforeMidnight.day());
        assertEquals(23, beforeMidnight.hour());
        assertEquals(59, beforeMidnight.minute());
        assertEquals(54, beforeMidnight.second());
    }

    @Test
    @DisplayName("时分秒按 8 小时 12 分 6 秒这样的组合正确拆分")
    void splitsIntoHourMinuteSecond() {
        // 第 3 日 08:12:06 = 2 日 + 8h + 12min + 6s = 2*86400 + 29526 秒 = 202326 秒 = 33721 步
        MarketTime time = MarketTime.ofStep(33_721);

        assertEquals(3L, time.day());
        assertEquals(8, time.hour());
        assertEquals(12, time.minute());
        assertEquals(6, time.second());
    }
}
