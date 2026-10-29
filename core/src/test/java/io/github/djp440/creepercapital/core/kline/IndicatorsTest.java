package io.github.djp440.creepercapital.core.kline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IndicatorsTest {

    private static Candle closing(long start, double close) {
        return new Candle(start, close, close, close, close, 1.0);
    }

    @Test
    @DisplayName("MA(3) 是最近 3 根收盘价的算术平均；不足 3 根的位置没有值")
    void movingAverageOfCloses() {
        List<Candle> candles = List.of(closing(0, 10), closing(60, 20), closing(120, 30), closing(180, 60),
                closing(240, 30));

        double[] ma = Indicators.movingAverage(candles, 3);

        assertEquals(5, ma.length);
        assertTrue(Double.isNaN(ma[0]));
        assertTrue(Double.isNaN(ma[1]));
        // (10+20+30)/3, (20+30+60)/3, (30+60+30)/3
        assertArrayEquals(new double[] {20.0, 110.0 / 3, 40.0}, new double[] {ma[2], ma[3], ma[4]}, 1e-12);
    }

    @Test
    @DisplayName("MA 只看收盘价，不受开高低影响")
    void movingAverageUsesCloseOnly() {
        List<Candle> candles = List.of(new Candle(0, 1, 100, 0.5, 4, 1), new Candle(60, 9, 50, 2, 6, 1));

        assertEquals(5.0, Indicators.movingAverage(candles, 2)[1], 1e-12);
    }

    @Test
    @DisplayName("MA(1) 等于收盘价本身；K 线比周期少时全部没有值；周期必须为正")
    void movingAverageEdgeCases() {
        List<Candle> candles = List.of(closing(0, 7), closing(60, 8));

        assertArrayEquals(new double[] {7, 8}, Indicators.movingAverage(candles, 1), 0.0);
        double[] tooFew = Indicators.movingAverage(candles, 3);
        assertTrue(Double.isNaN(tooFew[0]) && Double.isNaN(tooFew[1]));
        assertEquals(0, Indicators.movingAverage(List.of(), 5).length);
        assertThrows(IllegalArgumentException.class, () -> Indicators.movingAverage(candles, 0));
    }
}
