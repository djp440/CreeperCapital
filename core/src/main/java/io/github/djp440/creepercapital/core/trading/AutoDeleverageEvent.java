package io.github.djp440.creepercapital.core.trading;

import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 一次自动减仓（ADL，#17）：流动性池权益不足时，系统以开仓价强制缩减一个盈利仓位的一部分。
 * 它是结构化事件，在线通知、日志和离线事件汇总（#19）都用它，不要拼字符串。
 *
 * @param player          被减仓的玩家
 * @param symbol          合约
 * @param side            仓位方向
 * @param mode            仓位的保证金模式
 * @param lots            被缩减的数量（手）
 * @param remainingLots   缩减之后剩余的数量（0 表示整个仓位被减光）
 * @param price           成交价：仓位的开仓价（所以被缩减部分的盈利归零）
 * @param markPrice       触发时的标记价格
 * @param lostProfit      玩家因此损失的浮盈（被缩减部分按标记价格本来的盈利）
 * @param releasedMargin  退回玩家可用余额的保证金
 * @param rank            触发时这个仓位在 ADL 排名里的名次（1 为最先被减）
 * @param tradeId         对应成交历史里那笔 {@link TradeKind#ADL} 成交的编号
 * @param marketSeconds   发生时的市场时间（市场秒）
 */
public record AutoDeleverageEvent(UUID player, String symbol, Side side, MarginMode mode, long lots, long remainingLots,
        double price, double markPrice, Money lostProfit, Money releasedMargin, int rank, long tradeId,
        long marketSeconds) {
}
