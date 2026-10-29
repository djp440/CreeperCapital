package io.github.djp440.creepercapital.core.trading;

import java.util.List;
import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 一次强平的结构化记录，供玩家通知和 #19「离线事件汇总」使用（别只拼字符串）。
 * 逐仓强平一个仓位产出一条，全仓强平整个全仓账户产出一条（里面是全部全仓仓位）。
 *
 * @param player          被强平的玩家
 * @param mode            逐仓还是全仓
 * @param positions       被强平的仓位，按交易代码排序
 * @param collateralLost  玩家损失的抵押：逐仓是该仓位的保证金，全仓是整个全仓钱包（含可用余额、挂单冻结）
 * @param toFund          流入风险保障基金的金额：玩家抵押扣掉亏损后剩下的部分（其中包含清算费）；穿仓时为 0
 * @param shortfall       穿仓金额：亏损超出玩家抵押的部分；没穿仓为 0
 * @param fundCovered     穿仓中由风险保障基金垫付的部分
 * @param poolBorne       穿仓中基金不够、由流动性池承担（少收）的部分
 * @param cancelledOrders 强平时一并撤销的挂单（冻结资金已退回可用余额）
 * @param marketSeconds   发生时的市场时间（市场秒）
 */
public record LiquidationEvent(UUID player, MarginMode mode, List<Liquidated> positions, Money collateralLost,
        Money toFund, Money shortfall, Money fundCovered, Money poolBorne, List<OrderEvent> cancelledOrders,
        long marketSeconds) {

    /**
     * 被强平的一个仓位。
     *
     * @param price       成交价，就是触发强平的标记价格
     * @param realizedPnl 按成交价算的盈亏（未封顶；穿仓的部分不由玩家承担，见 {@link LiquidationEvent#shortfall}）
     * @param fee         清算费，已包含在流入基金的金额里
     * @param tradeId     对应的成交历史编号
     */
    public record Liquidated(String symbol, Side side, long lots, double price, Money realizedPnl, Money fee,
            long tradeId) {
    }

    /** 被强平的合约，按交易代码排序。 */
    public List<String> symbols() {
        return positions.stream().map(Liquidated::symbol).toList();
    }
}
