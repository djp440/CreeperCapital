package io.github.djp440.creepercapital.core.market;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟成交：把一个行情步的成交量拆成若干笔，只用来在「最新成交」里展示市场活跃度，不进入任何账户、流动性池和风险保障基金。
 * <p>
 * 拆分是<b>无状态确定</b>的：随机数只由市场种子、交易代码和行情步序号派生（{@link MarketRandom#forTrades}），
 * 不碰行情自己的随机数流，所以不会改变价格路径，也不需要存档；同一步永远拆出同一批。
 * <ul>
 *   <li>数量：该步成交量折成整数手（四舍五入），拆成 2 到 8 笔（手数不够拆时更少），每笔至少 1 手，总和<b>精确等于</b>总手数；</li>
 *   <li>价格：买单在 [最新价, 最新价 + 半个点差]，卖单在 [最新价 - 半个点差, 最新价]，都落在点差范围内；</li>
 *   <li>方向：买单概率 = 0.5 + 0.35 × tanh(本步涨跌幅 / 单步波动率)，涨多买、跌多卖，范围 [15%, 85%]。</li>
 * </ul>
 */
public final class SimulatedTrades {
    private static final int MIN_TRADES = 2;
    private static final int MAX_TRADES = 8;
    /** 每笔相对大小的下限，避免一步里出现只有零头的笔。 */
    private static final double MIN_WEIGHT = 0.2;
    private static final double BUY_BIAS_RANGE = 0.35;

    private SimulatedTrades() {
    }

    /**
     * 拆出一个行情步的模拟成交。
     *
     * @param snapshot          该步某合约的快照（成交量、最新价）
     * @param previousLastPrice 上一个行情步的最新价格；未知（NaN）时按持平处理，买卖各占一半
     * @return 按时间顺序排列的成交；成交量折成不到 1 手时为空
     */
    public static List<MarketTrade> of(long marketSeed, ContractSpec spec, MarketSnapshot snapshot, double previousLastPrice) {
        double scaled = snapshot.volume() * Math.pow(10, spec.quantityDecimals());
        if (!(scaled >= 0.5) || scaled > Long.MAX_VALUE / 2) {
            return List.of();
        }
        long totalLots = Math.round(scaled);
        MarketRandom random = MarketRandom.forTrades(marketSeed, snapshot.symbol(), snapshot.step());
        int count = (int) Math.min(totalLots, MIN_TRADES + (long) (random.nextDouble() * (MAX_TRADES - MIN_TRADES + 1)));

        double[] weights = new double[count];
        double weightSum = 0;
        for (int i = 0; i < count; i++) {
            weights[i] = MIN_WEIGHT + random.nextDouble();
            weightSum += weights[i];
        }

        double buyProbability = 0.5 + BUY_BIAS_RANGE * Math.tanh(moveRatio(spec, snapshot, previousLastPrice));
        double last = snapshot.lastPrice();
        double halfSpread = spec.spread() / 2;
        long seconds = snapshot.time().totalSeconds();
        List<MarketTrade> trades = new ArrayList<>(count);
        double cumulative = 0;
        long assigned = 0;
        for (int i = 0; i < count; i++) {
            cumulative += weights[i];
            // 按累计权重取整划界，最后一笔直接收尾，保证总和精确
            long boundary = i == count - 1 ? totalLots : Math.round(totalLots * (cumulative / weightSum));
            long lots = boundary - assigned;
            assigned = boundary;
            boolean buy = random.nextDouble() < buyProbability;
            double offset = random.nextDouble() * halfSpread;
            if (lots > 0) {
                trades.add(new MarketTrade(snapshot.symbol(), seconds, buy, buy ? last + offset : last - offset, lots, ""));
            }
        }
        return trades;
    }

    /** 本步涨跌幅相对单步波动率的倍数；上一步价格未知时为 0。 */
    private static double moveRatio(ContractSpec spec, MarketSnapshot snapshot, double previousLastPrice) {
        if (!(previousLastPrice > 0) || !(snapshot.lastPrice() > 0)) {
            return 0;
        }
        double stepVolatility = spec.volatility().dailyVolatility() / Math.sqrt(IndexModel.STEPS_PER_DAY);
        return (snapshot.lastPrice() / previousLastPrice - 1) / stepVolatility;
    }
}
