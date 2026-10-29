package io.github.djp440.creepercapital.core.trading;

import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 一次资金费结算：某名玩家的某个仓位在结算点按「名义价值 × 费率」与流动性池收付。
 * 同时是持久化的「资金费记录」和 #19「离线事件汇总」要用的结构化事件，别另拼字符串。
 *
 * @param id            编号，全服递增，恢复后接着往下编
 * @param player        玩家
 * @param symbol        交易代码
 * @param side          结算时仓位的方向
 * @param mode          结算时仓位的保证金模式（决定从保证金还是余额里收付）
 * @param lots          结算时仓位的数量（手）
 * @param rate          结算时的资金费率（比例，0.0001 即 0.01%）；为正多头付、为负空头付
 * @param markPrice     结算时的标记价格
 * @param notional      结算时的仓位名义价值 = 标记价格 × 数量
 * @param amount        实际收付的金额，站在玩家一侧：正数为收到，负数为支付；可能小于应收付的数额（见 {@code shortfall}）
 * @param shortfall     应收付但没能收付的部分（不为负）：逐仓保证金或全仓余额不够付、流动性池不够收时的差额
 * @param marketSeconds 结算时的市场时间（市场秒）
 */
public record FundingRecord(long id, UUID player, String symbol, Side side, MarginMode mode, long lots, double rate,
        double markPrice, Money notional, Money amount, Money shortfall, long marketSeconds) {
    /** 玩家付出了资金费。 */
    public boolean paid() {
        return amount.units() < 0;
    }
}
