package io.github.djp440.creepercapital.core.kline;

/**
 * 行情栏的 24h 统计：最近 24 个市场小时（1440 根 1m K 线）的开高低收与成交量，数据与 K 线一致。
 *
 * @param open   窗口内第一个行情步的价格
 * @param high   窗口内最高价
 * @param low    窗口内最低价
 * @param close  最新价格
 * @param volume 窗口内成交量（标的资源数量）
 */
public record Stats24h(double open, double high, double low, double close, double volume) {

    /** 24h 涨跌幅（比例，0.01 即 +1%）。 */
    public double changeRatio() {
        return (close - open) / open;
    }
}
