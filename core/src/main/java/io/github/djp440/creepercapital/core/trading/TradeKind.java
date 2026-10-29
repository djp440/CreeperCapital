package io.github.djp440.creepercapital.core.trading;

/**
 * 玩家成交是开仓（增加仓位）、平仓（减少仓位）还是强平（系统按标记价格平掉仓位，#12）。
 * 网络上按序号编码，新增只能追加在末尾。
 */
public enum TradeKind {
    OPEN,
    CLOSE,
    /** 强平：保证金率到 100%，系统按标记价格平仓；买卖方向与平仓相同。 */
    LIQUIDATION
}
