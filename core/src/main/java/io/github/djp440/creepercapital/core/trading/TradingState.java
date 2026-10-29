package io.github.djp440.creepercapital.core.trading;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 交易账要存档的全部状态（玩家余额在 {@code AccountBook} 里，不在这里）。
 *
 * @param poolBalance  流动性池余额
 * @param lastTradeId  最近一笔成交的编号，恢复后接着往下编
 * @param positions    每位玩家的全部仓位
 * @param trades       每位玩家最近的成交，最旧的在前
 */
public record TradingState(Money poolBalance, long lastTradeId, Map<UUID, List<Position>> positions,
        Map<UUID, List<PlayerTrade>> trades) {
    /** 新世界的状态：池子是初始资金，没有仓位和成交。 */
    public static TradingState initial() {
        return new TradingState(LiquidityPool.INITIAL_BALANCE, 0, Map.of(), Map.of());
    }
}
