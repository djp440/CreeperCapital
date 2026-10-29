package io.github.djp440.creepercapital.core.market;

/**
 * 「最新成交」里的一笔：模拟成交（只展示市场活跃度，{@code player} 为空）或玩家成交（高亮并标出玩家名）。
 *
 * @param symbol        交易代码
 * @param marketSeconds 成交时的市场时间（市场秒）
 * @param buy           主动方向：true 为买入（吃卖盘），false 为卖出
 * @param price         成交价
 * @param lots          成交数量，以最小数量单位（手）计
 * @param player        玩家名；模拟成交为空串
 */
public record MarketTrade(String symbol, long marketSeconds, boolean buy, double price, long lots, String player) {

    public boolean isPlayer() {
        return !player.isEmpty();
    }
}
