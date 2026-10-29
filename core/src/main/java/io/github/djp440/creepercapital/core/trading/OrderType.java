package io.github.djp440.creepercapital.core.trading;

/** 委托类型。目前只有限价；止盈止损、只减仓等在 #15 里往这里加（HUM_MEMO 里更多类型另行决定）。 */
public enum OrderType {
    /** 限价：买单价格不高于委托价、卖单价格不低于委托价时成交，有效期 GTC。 */
    LIMIT
}
