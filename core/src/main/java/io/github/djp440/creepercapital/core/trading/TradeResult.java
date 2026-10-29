package io.github.djp440.creepercapital.core.trading;

import java.util.List;

/** 一次下单的结果：成交，或被拒绝（带原因，界面据此提示）。 */
public sealed interface TradeResult {
    /** 已成交。 */
    record Filled(PlayerTrade trade) implements TradeResult {
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
        INVALID_PRICE
    }

    static TradeResult reject(Reason reason, String... args) {
        return new Rejected(reason, List.of(args));
    }
}
