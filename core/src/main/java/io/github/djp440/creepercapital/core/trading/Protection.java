package io.github.djp440.creepercapital.core.trading;

/**
 * 一组止盈止损设置：开仓时附带的，或给已有仓位补设的。价格为 0 表示不设这一项。
 *
 * @param takeProfit 止盈触发价（0 = 不设）
 * @param stopLoss   止损触发价（0 = 不设）
 * @param source     触发价格来源
 */
public record Protection(double takeProfit, double stopLoss, TriggerSource source) {
    public static final Protection NONE = new Protection(0, 0, TriggerSource.LAST);

    public boolean isEmpty() {
        return takeProfit == 0 && stopLoss == 0;
    }
}
