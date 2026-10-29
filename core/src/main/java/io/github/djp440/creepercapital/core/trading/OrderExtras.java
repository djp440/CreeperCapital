package io.github.djp440.creepercapital.core.trading;

/**
 * 委托在限价之外的附加条款（单独成记录是因为存档 Codec 的字段数有上限，旧存档没有这些字段时取 {@link #NONE}）。
 *
 * @param reduceOnly         只减仓：只能缩减现有仓位，数量超过仓位时按仓位缩减，没有仓位就不成交（自动失效）。
 *                           止盈止损委托天然只减仓
 * @param triggerSource      止盈止损的触发价来源；限价开仓带有附带止盈止损时，是附带委托将采用的来源
 * @param attachedTakeProfit 限价开仓附带的止盈触发价（0 = 没有）；成交成仓位时才生成止盈委托
 * @param attachedStopLoss   限价开仓附带的止损触发价（0 = 没有）
 */
public record OrderExtras(boolean reduceOnly, TriggerSource triggerSource, double attachedTakeProfit,
        double attachedStopLoss) {
    public static final OrderExtras NONE = new OrderExtras(false, TriggerSource.LAST, 0, 0);

    public Protection attached() {
        return new Protection(attachedTakeProfit, attachedStopLoss, triggerSource);
    }
}
