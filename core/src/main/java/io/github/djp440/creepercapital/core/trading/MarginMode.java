package io.github.djp440.creepercapital.core.trading;

/** 保证金模式，按（玩家，合约）设置。 */
public enum MarginMode {
    /** 逐仓：保证金只属于这个仓位，亏损最多亏到它。 */
    ISOLATED,
    /** 全仓：账户内所有全仓仓位共用同一份余额作保证金，未实现盈亏计入保证金余额。 */
    CROSS
}
