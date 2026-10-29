package io.github.djp440.creepercapital.core.trading;

/** 止盈止损用哪个价格判断是否触发。默认最新价格。 */
public enum TriggerSource {
    /** 最新价格（{@code MarketSnapshot.lastPrice}）。 */
    LAST,
    /** 标记价格（{@code MarketSnapshot.markPrice}）。 */
    MARK
}
