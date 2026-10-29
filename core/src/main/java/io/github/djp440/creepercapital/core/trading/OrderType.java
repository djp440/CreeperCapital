package io.github.djp440.creepercapital.core.trading;

/** 委托类型（HUM_MEMO 里更多类型另行决定）。 */
public enum OrderType {
    /** 限价：买入价不高于委托价、卖出价不低于委托价时成交，有效期 GTC；可带只减仓。 */
    LIMIT,
    /** 止盈：价格朝盈利方向到达触发价（多仓 ≥、空仓 ≤）时，按市价平仓。 */
    TAKE_PROFIT,
    /** 止损：价格朝亏损方向到达触发价（多仓 ≤、空仓 ≥）时，按市价平仓。 */
    STOP_LOSS;

    /** 触发后按市价平仓的类型（止盈、止损）。 */
    public boolean isTrigger() {
        return this != LIMIT;
    }
}
