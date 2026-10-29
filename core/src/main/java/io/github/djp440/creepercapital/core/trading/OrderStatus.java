package io.github.djp440.creepercapital.core.trading;

/** 委托的状态：挂着（当前委托），或已经终结（历史委托）。 */
public enum OrderStatus {
    /** 尚未成交的挂单，冻结着资金。 */
    OPEN,
    /** 已成交（含下单即成交的）。 */
    FILLED,
    /** 已撤销。 */
    CANCELLED
}
