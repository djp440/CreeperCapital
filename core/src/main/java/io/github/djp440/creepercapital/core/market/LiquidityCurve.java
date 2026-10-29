package io.github.djp440.creepercapital.core.market;

import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟流动性曲线：玩家市价成交时沿它逐档吃单，单子越大成交均价越差（ADR-0002 的<b>临时价格冲击</b>）。
 * 曲线只取决于合约的深度参数与调用时给的最新价格，不记得任何成交，所以成交后最新价格不变，其他玩家看到的价格不受影响。
 * <p>
 * 曲线是离散阶梯：买盘（卖一价起向上）与卖盘（买一价起向下）各有无穷多档，每档数量相同，约等于 {@link #LEVEL_NOTIONAL} CRP
 * 的名义价值（按最新价格折成整数手，至少 1 手）；第 {@code i} 档的价格是第一档的 {@code (1 ± step)^i} 倍。
 * 界面的盘口显示前若干档（{@link #levels}），实际成交沿同一阶梯从第一档吃起、最后一档可部分成交（{@link #averageFillPrice}），
 * 所以按盘口逐档吃单算出的均价就是实际成交均价；客户端用同步下来的深度参数调用同一份代码，不复制算法。
 * <p>
 * 标定：{@code depthSlippage} 是名义价值 {@link #REFERENCE_NOTIONAL} CRP 的市价单的平均滑点，
 * <b>相对第一档（买一价/卖一价）</b>，不含半个点差；含半个点差时再加 {@code spread / 2 / 最新价}。
 * 步长 {@code step = 2 × depthSlippage / (参考档数 − 1)}，因为参考单刚好吃 20 档，平均上浮约 9.5 个步长；
 * 二阶项（复利）使实际略大，约 0.2%（相对滑点本身），远小于测试容差。
 */
public final class LiquidityCurve {
    /** 标定滑点用的参考名义价值（CRP）。 */
    public static final double REFERENCE_NOTIONAL = 10_000;
    /** 每一档的名义价值（CRP），参考单正好吃 20 档。 */
    public static final double LEVEL_NOTIONAL = 500;
    /** 滑点上限：超过这个值一档的价格变化就大得没有意义。 */
    public static final double MAX_DEPTH_SLIPPAGE = 0.5;

    /** 阶梯上的一档。 */
    public record Level(double price, long lots) {
    }

    private final double lastPrice;
    private final double halfSpread;
    private final long levelLots;
    /** 相邻两档价格的相对变化（正数）。 */
    private final double step;

    private LiquidityCurve(double lastPrice, double halfSpread, long levelLots, double step) {
        this.lastPrice = lastPrice;
        this.halfSpread = halfSpread;
        this.levelLots = levelLots;
        this.step = step;
    }

    /**
     * @param spread           点差（CRP，绝对价格间隔）
     * @param depthSlippage    名义价值 {@link #REFERENCE_NOTIONAL} CRP 的市价单的平均滑点（比例，0 表示无限深）
     * @param quantityDecimals 数量精度
     * @param lastPrice        当前最新价格
     */
    public static LiquidityCurve of(double spread, double depthSlippage, int quantityDecimals, double lastPrice) {
        if (!(spread >= 0) || !Double.isFinite(spread)) {
            throw new IllegalArgumentException("点差必须是非负的有限数：" + spread);
        }
        if (!(depthSlippage >= 0) || !(depthSlippage < MAX_DEPTH_SLIPPAGE)) {
            throw new IllegalArgumentException("深度滑点必须在 0（含）到 " + MAX_DEPTH_SLIPPAGE + "（不含）之间：" + depthSlippage);
        }
        if (!(lastPrice > 0) || !Double.isFinite(lastPrice)) {
            throw new IllegalArgumentException("最新价格必须是有限的正数：" + lastPrice);
        }
        long levelLots = Math.max(1, Math.round(LEVEL_NOTIONAL / lastPrice * Math.pow(10, quantityDecimals)));
        double referenceLevels = REFERENCE_NOTIONAL / LEVEL_NOTIONAL;
        double step = depthSlippage * 2 / (referenceLevels - 1);
        return new LiquidityCurve(lastPrice, spread / 2, levelLots, step);
    }

    /** 每一档的数量（手）。 */
    public long levelLots() {
        return levelLots;
    }

    /** 第一档的价格：买入是最新价 + 半个点差（卖一价），卖出是最新价 − 半个点差（买一价）。 */
    public double touchPrice(boolean buy) {
        return buy ? lastPrice + halfSpread : lastPrice - halfSpread;
    }

    /** 第 {@code index} 档（从 0 开始）。买入侧是卖盘，逐档变贵；卖出侧是买盘，逐档变便宜。 */
    public Level level(boolean buy, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("档位不能为负：" + index);
        }
        return new Level(touchPrice(buy) * Math.exp(index * logRatio(buy)), levelLots);
    }

    /** 前 {@code count} 档，供盘口显示。 */
    public List<Level> levels(boolean buy, int count) {
        List<Level> levels = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            levels.add(level(buy, i));
        }
        return levels;
    }

    /**
     * 市价成交 {@code lots} 手的成交均价：从第一档逐档吃，最后一档部分成交；买入价格向上、卖出向下。
     * <p>
     * <b>这是全模组唯一的成交价算法</b>：经 {@code TradingBook.fillPrice} 供市价单使用，限价委托中按市价成交（taker）的部分
     * 与止盈止损触发后的市价成交也必须走它，不得各算各的。
     *
     * @return 成交均价；数量大到浮点溢出时返回非有限数或非正数，调用方应当拒单
     */
    public double averageFillPrice(boolean buy, long lots) {
        if (lots <= 0) {
            throw new IllegalArgumentException("成交数量必须为正：" + lots);
        }
        double logRatio = logRatio(buy);
        long fullLevels = lots / levelLots;
        long remainder = lots % levelLots;
        // 吃满的 fullLevels 档价格是等比数列 1, r, r², …，求和用 expm1 避免步长很小时的相消误差
        double fullCost = step == 0 ? fullLevels : Math.expm1(fullLevels * logRatio) / Math.expm1(logRatio);
        double cost = levelLots * fullCost + remainder * Math.exp(fullLevels * logRatio);
        // 先除后乘：不超过第一档时 cost / lots 恰好是 1，均价与第一档价格逐位相等
        return touchPrice(buy) * (cost / lots);
    }

    private double logRatio(boolean buy) {
        return buy ? Math.log1p(step) : Math.log1p(-step);
    }
}
