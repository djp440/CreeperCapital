package io.github.djp440.creepercapital.core.trading;

import java.util.List;

/** 切换保证金模式或调整杠杆的结果：已生效，或被拒绝（带原因，界面据此提示）。 */
public sealed interface MarginResult {
    /** 已生效（设成和现状一样也算生效）。 */
    record Applied() implements MarginResult {
    }

    /** 被拒绝，账户和仓位都没有变化；原因与参数含义见 {@link TradeResult.Reason}。 */
    record Rejected(TradeResult.Reason reason, List<String> args) implements MarginResult {
    }

    static MarginResult reject(TradeResult.Reason reason, String... args) {
        return new Rejected(reason, List.of(args));
    }
}
