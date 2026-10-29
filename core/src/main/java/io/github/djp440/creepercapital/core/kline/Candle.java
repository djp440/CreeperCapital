package io.github.djp440.creepercapital.core.kline;

/**
 * 一根 K 线：某个周期内的开高低收与成交量。
 *
 * @param start  K 线的起点（市场秒），对齐其周期的边界
 * @param open   开盘价：这根 K 线里第一个行情步的价格
 * @param high   最高价
 * @param low    最低价
 * @param close  收盘价：到目前为止最后一个行情步的价格
 * @param volume 成交量（标的资源数量）
 */
public record Candle(long start, double open, double high, double low, double close, double volume) {

    /** 由第一个行情步开出一根新 K 线。 */
    public static Candle opening(long start, double price, double volume) {
        return new Candle(start, price, price, price, price, volume);
    }

    /** 并入下一个行情步：更新最高、最低、收盘，累计成交量。 */
    public Candle accept(double price, double volume) {
        return new Candle(start, open, Math.max(high, price), Math.min(low, price), price, this.volume + volume);
    }

    /** 与紧随其后的一根 K 线合并成更长周期的 K 线。 */
    public Candle merge(Candle next) {
        if (next.start < start) {
            throw new IllegalArgumentException("只能合并时间在后的 K 线：" + start + " 之后，实际 " + next.start);
        }
        return new Candle(start, open, Math.max(high, next.high), Math.min(low, next.low), next.close,
                volume + next.volume);
    }
}
