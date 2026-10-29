package io.github.djp440.creepercapital.core.trading;

import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 玩家成交：一个合约账户的委托与流动性池之间实际发生的成交。
 *
 * @param id            成交编号，全服递增
 * @param player        玩家
 * @param symbol        交易代码
 * @param side          所属仓位的方向（开多、平多都是 LONG）
 * @param kind          开仓、平仓或强平
 * @param price         成交价；强平是触发强平的标记价格
 * @param lots          成交数量，以最小数量单位（手）计
 * @param fee           手续费（taker），归流动性池；强平是清算费，归风险保障基金
 * @param realizedPnl   已实现盈亏（不含手续费）；开仓为 0；强平是按标记价格算的盈亏（未封顶）
 * @param marketSeconds 成交时的市场时间（市场秒）
 */
public record PlayerTrade(long id, UUID player, String symbol, Side side, TradeKind kind, double price, long lots,
        Money fee, Money realizedPnl, long marketSeconds) {
}
