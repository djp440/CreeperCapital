package io.github.djp440.creepercapital.core.kline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CandleTest {

    @Test
    @DisplayName("七个周期的时长按市场时间计：1m/5m/15m/1h/4h/1d/1w")
    void periodsAreMeasuredInMarketTime() {
        assertEquals(List.of("1m", "5m", "15m", "1h", "4h", "1d", "1w"),
                List.of(CandlePeriod.values()).stream().map(CandlePeriod::label).toList());
        assertEquals(60L, CandlePeriod.M1.seconds());
        assertEquals(300L, CandlePeriod.M5.seconds());
        assertEquals(900L, CandlePeriod.M15.seconds());
        assertEquals(3_600L, CandlePeriod.H1.seconds());
        assertEquals(14_400L, CandlePeriod.H4.seconds());
        assertEquals(86_400L, CandlePeriod.D1.seconds());
        assertEquals(604_800L, CandlePeriod.W1.seconds());
    }

    @Test
    @DisplayName("K 线边界对齐市场日历起点：恰在边界的那一秒属于新 K 线，边界前一秒属于旧 K 线")
    void boundariesAlignToTheMarketCalendar() {
        assertEquals(0L, CandlePeriod.M1.startOf(0));
        assertEquals(0L, CandlePeriod.M1.startOf(59));
        assertEquals(60L, CandlePeriod.M1.startOf(60));
        assertEquals(0L, CandlePeriod.M5.startOf(299));
        assertEquals(300L, CandlePeriod.M5.startOf(300));
        assertEquals(900L, CandlePeriod.M15.startOf(1_799));
        assertEquals(1_800L, CandlePeriod.M15.startOf(1_800));
        // 第 1 日 03:59:59 仍在 00:00 开始的 4h K 线里，04:00:00 起是新的一根
        assertEquals(0L, CandlePeriod.H4.startOf(4 * 3_600 - 1));
        assertEquals(14_400L, CandlePeriod.H4.startOf(4 * 3_600));
        assertEquals(0L, CandlePeriod.D1.startOf(86_399));
        assertEquals(86_400L, CandlePeriod.D1.startOf(86_400));
        // 周 K 以市场日历第 1 日为一周的第一天，每 7 个市场日一根
        assertEquals(0L, CandlePeriod.W1.startOf(7 * 86_400 - 1));
        assertEquals(7 * 86_400L, CandlePeriod.W1.startOf(7 * 86_400));
    }

    @Test
    @DisplayName("一根 K 线由第一个价格开盘，之后每个行情步更新最高、最低、收盘并累计成交量")
    void candleAggregatesOhlcv() {
        Candle candle = Candle.opening(60, 100.0, 2.0);
        candle = candle.accept(103.0, 3.0);
        candle = candle.accept(99.0, 1.5);
        candle = candle.accept(101.0, 0.5);

        assertEquals(new Candle(60, 100.0, 103.0, 99.0, 101.0, 7.0), candle);
    }

    @Test
    @DisplayName("开盘即为唯一价格时，开高低收相同")
    void freshCandleHasFlatBody() {
        assertEquals(new Candle(0, 5.0, 5.0, 5.0, 5.0, 1.0), Candle.opening(0, 5.0, 1.0));
    }

    @Test
    @DisplayName("合并两根相邻 K 线：开盘取前者，收盘取后者，最高最低取极值，成交量相加")
    void mergeCombinesAdjacentCandles() {
        Candle first = new Candle(0, 100.0, 105.0, 98.0, 102.0, 10.0);
        Candle second = new Candle(60, 102.5, 104.0, 96.0, 97.0, 4.0);

        assertEquals(new Candle(0, 100.0, 105.0, 96.0, 97.0, 14.0), first.merge(second));
    }

    @Test
    @DisplayName("不能把较早的 K 线合并到较晚的 K 线之后")
    void mergeRejectsOutOfOrderCandles() {
        Candle later = Candle.opening(60, 1.0, 1.0);
        Candle earlier = Candle.opening(0, 1.0, 1.0);

        assertThrows(IllegalArgumentException.class, () -> later.merge(earlier));
    }
}
