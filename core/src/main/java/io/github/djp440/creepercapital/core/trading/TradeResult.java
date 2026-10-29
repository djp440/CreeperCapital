package io.github.djp440.creepercapital.core.trading;

import java.util.List;

/** 一次下单的结果：成交，或被拒绝（带原因，界面据此提示）。 */
public sealed interface TradeResult {
    /** 已成交。 */
    record Filled(PlayerTrade trade) implements TradeResult {
    }

    /** 限价委托已挂出（没有立即成交），资金已冻结。 */
    record Placed(Order order) implements TradeResult {
    }

    /** 给已有仓位补设的止盈止损委托已挂出（最多一张止盈、一张止损），不冻结资金。 */
    record ProtectionPlaced(java.util.List<Order> orders) implements TradeResult {
    }

    /**
     * 被拒绝，账户和池子都没有变化。
     *
     * @param reason 原因
     * @param args   提示文案的参数（已格式化的文本），含义见 {@link Reason}
     */
    record Rejected(Reason reason, List<String> args) implements TradeResult {
    }

    /** 拒单原因。 */
    enum Reason {
        /** 数量不是正整数手，或数额大到无法结算。 */
        INVALID_QUANTITY,
        /** 杠杆不在 1 到合约最高杠杆之间；参数：最高杠杆。 */
        INVALID_LEVERAGE,
        /** 余额不足以支付保证金和手续费；参数：需要的金额、可用余额。 */
        INSUFFICIENT_BALANCE,
        /** 已有反向仓位（单向持仓），要先平仓。 */
        OPPOSITE_POSITION,
        /** 已有仓位的杠杆与下单的不同；参数：仓位的杠杆。 */
        LEVERAGE_MISMATCH,
        /** 没有可平的仓位。 */
        NO_POSITION,
        /** 平仓数量超过仓位；参数：仓位数量（手）。 */
        CLOSE_EXCEEDS_POSITION,
        /** 成交价不是正数（价格已低于半个点差）。 */
        INVALID_PRICE,
        /** 仓位名义价值超过该杠杆下的档位上限；参数：该杠杆下允许的最大名义价值（CRP）。 */
        POSITION_LIMIT,
        /** 该合约有仓位或委托，不能切换保证金模式。 */
        MARGIN_MODE_LOCKED,
        /** 新杠杆超过当前名义价值所在档位的上限；参数：该档位的最高杠杆、当前名义价值（CRP）。 */
        LEVERAGE_ABOVE_TIER,
        /** 调整杠杆后仓位的保证金率会达到 100%（会被强平）；参数：调整后的保证金率（百分比文本）。 */
        LEVERAGE_UNSAFE,
        /** 挂着的委托已达上限；参数：上限。 */
        TOO_MANY_ORDERS,
        /** 止盈止损的触发价不合法（不是正数，或按当前价格会立即触发：多仓止盈须高于、止损须低于参考价，空仓相反）；参数：参考价。 */
        INVALID_TRIGGER_PRICE
    }

    static TradeResult reject(Reason reason, String... args) {
        return new Rejected(reason, List.of(args));
    }
}
