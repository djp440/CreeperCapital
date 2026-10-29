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

    /**
     * 触发价是否合法：正的有限数，且按参考价不会立即触发——多仓止盈须高于、止损须低于参考价，空仓相反。
     * 服务端下单校验与客户端图表拖拽共用这一条规则。
     *
     * @param side      所属仓位的方向
     * @param type      止盈或止损
     * @param price     触发价
     * @param reference 参考价（按触发价来源取最新价格或标记价格）
     */
    public static boolean validTrigger(Side side, OrderType type, double price, double reference) {
        if (!Double.isFinite(price) || !(price > 0) || !Double.isFinite(reference)) {
            return false;
        }
        boolean above = price > reference;
        boolean below = price < reference;
        boolean wantAbove = (type == OrderType.TAKE_PROFIT) == (side == Side.LONG);
        return wantAbove ? above : below;
    }
}
