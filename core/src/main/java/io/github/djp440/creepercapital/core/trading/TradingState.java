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
 * @param lastOrderId  最近一张委托的编号，恢复后接着往下编
 * @param orders       每位玩家的委托：挂着的和最近的历史委托（按编号从小到大）
 */
public record TradingState(Money poolBalance, long lastTradeId, Map<UUID, List<Position>> positions,
        Map<UUID, List<PlayerTrade>> trades, long lastOrderId, Map<UUID, List<Order>> orders) {
    /** 旧存档的形状（没有委托）。 */
    public TradingState(Money poolBalance, long lastTradeId, Map<UUID, List<Position>> positions,
            Map<UUID, List<PlayerTrade>> trades) {
        this(poolBalance, lastTradeId, positions, trades, 0, Map.of());
    }

    /** 新世界的状态：池子是初始资金，没有仓位、成交和委托。 */
    public static TradingState initial() {
        return new TradingState(LiquidityPool.INITIAL_BALANCE, 0, Map.of(), Map.of());
    }
}
