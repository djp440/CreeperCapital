package io.github.djp440.creepercapital.core.trading;

/** 委托被撤销的原因。 */
public enum CancelReason {
    /** 没有被撤销（委托是挂着的或已成交）。 */
    NONE,
    /** 玩家主动撤单。 */
    USER,
    /** 触发成交时开仓条件已不满足（例如其间开了反向仓位、杠杆与仓位不一致），服务端撤单并释放冻结资金。 */
    FILL_REJECTED,
    /** 只减仓 / 止盈止损委托要缩减的仓位已经没有了（全平、止盈止损触发、强平都算），委托自动失效。 */
    POSITION_CLOSED
}
