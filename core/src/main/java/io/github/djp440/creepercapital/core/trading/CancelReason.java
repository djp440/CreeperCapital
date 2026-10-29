package io.github.djp440.creepercapital.core.trading;

/** 委托被撤销的原因。 */
public enum CancelReason {
    /** 没有被撤销（委托是挂着的或已成交）。 */
    NONE,
    /** 玩家主动撤单。 */
    USER,
    /** 触发成交时开仓条件已不满足（例如其间开了反向仓位、杠杆与仓位不一致），服务端撤单并释放冻结资金。 */
    FILL_REJECTED,
    /** 玩家被强平，同合约（全仓则是全部）的挂单随之撤销，冻结资金退回可用余额。 */
    LIQUIDATED
}
