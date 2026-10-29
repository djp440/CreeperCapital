package io.github.djp440.creepercapital.core.kline;

import java.util.List;

/**
 * K 线图上的技术指标。目前只有均线（MA）。
 */
public final class Indicators {
    private Indicators() {
    }

    /**
     * 收盘价的简单移动平均 MA(period)。
     *
     * @return 与 {@code candles} 等长；前 {@code period - 1} 个位置不足 {@code period} 根，值为 NaN
     */
    public static double[] movingAverage(List<Candle> candles, int period) {
        if (period < 1) {
            throw new IllegalArgumentException("均线周期必须为正，实际 " + period);
        }
        double[] average = new double[candles.size()];
        double windowSum = 0;
        for (int i = 0; i < average.length; i++) {
            windowSum += candles.get(i).close();
            if (i >= period) {
                windowSum -= candles.get(i - period).close();
            }
            average[i] = i >= period - 1 ? windowSum / period : Double.NaN;
        }
        return average;
    }
}
