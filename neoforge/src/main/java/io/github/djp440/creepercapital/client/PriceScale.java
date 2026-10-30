package io.github.djp440.creepercapital.client;

import java.util.List;

import io.github.djp440.creepercapital.core.kline.Candle;

/**
 * 价格轴（纯函数）：价格和纵坐标的换算，{@code high} 在 {@code top}，{@code low} 在 {@code top + height - 1}。
 * 买卖点、持仓线、委托线等需要按价格定位的东西用同一个对象换算 y。
 */
record PriceScale(int top, int height, double high, double low) {
    /** 上下各留价格区间的这么多比例。 */
    private static final double PADDING = 0.08;

    /**
     * 按可见的 K 线 {@code [first, last]} 自动缩放：蜡烛图看最高 / 最低价，线形图（{@code closeOnly}）只看收盘价；
     * {@code lines} 是正在显示的指标线（与 {@code candles} 等长，NaN 点跳过，只看 {@code [first, last]}）——隐藏的指标不要传进来。
     */
    static PriceScale fit(int top, int height, List<Candle> candles, int first, int last, boolean closeOnly, List<double[]> lines) {
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        for (int i = first; i <= last; i++) {
            Candle candle = candles.get(i);
            high = Math.max(high, closeOnly ? candle.close() : candle.high());
            low = Math.min(low, closeOnly ? candle.close() : candle.low());
            for (double[] line : lines) {
                if (!Double.isNaN(line[i])) {
                    high = Math.max(high, line[i]);
                    low = Math.min(low, line[i]);
                }
            }
        }
        double pad = high - low < 1e-9 ? Math.max(Math.abs(high) * 0.005, 0.01) : (high - low) * PADDING;
        return new PriceScale(top, height, high + pad, low - pad);
    }

    int y(double price) {
        return top + (int) Math.round((high - price) / (high - low) * (height - 1));
    }

    double priceAt(int y) {
        return high - (high - low) * (y - top) / (height - 1);
    }
}
