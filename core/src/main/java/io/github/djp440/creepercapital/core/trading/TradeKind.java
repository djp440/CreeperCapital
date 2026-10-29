package io.github.djp440.creepercapital.core.trading;

/**
 * 玩家成交是开仓（增加仓位）、平仓（减少仓位）、强平（系统按标记价格平掉仓位，#12）还是自动减仓（#17）。
 * 网络上按序号编码，新增只能追加在末尾。
 */
public enum TradeKind {
    OPEN,
    CLOSE,
    /** 强平：保证金率到 100%，系统按标记价格平仓；买卖方向与平仓相同。 */
    LIQUIDATION,
    /** 自动减仓（ADL，#17）：流动性池权益不足时，系统按开仓价缩减盈利仓位，被缩减部分盈利归零、无手续费；买卖方向与平仓相同。 */
    ADL
}
