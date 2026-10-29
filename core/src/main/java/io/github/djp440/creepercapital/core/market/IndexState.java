package io.github.djp440.creepercapital.core.market;

/**
 * 指数价格模型需要持久化的全部状态。
 * <p>
 * 指数价格 = 基本面价值 × exp(慢偏离 + 跳跃偏离)：基本面价值缓慢漂移，价格围绕它均值回归；
 * 慢偏离是日常扩散造成的、回归得很慢的偏离，跳跃偏离是跳跃留下的、几小时内回吐的那部分。
 * 状态直接存对数值，保存再恢复不经过 exp/log 的往返，所以恢复后的走势与没停过的完全一致。
 *
 * @param logFundamental  基本面价值的对数
 * @param slowDeviation   慢偏离（对数）
 * @param jumpDeviation   跳跃偏离（对数）
 * @param volatilityRatio GARCH 条件方差与长期方差之比，1 表示处于平均水平；与配置的日波动率无关，所以改配置不会让它失效
 * @param regime          当前的市场状态
 * @param pendingJump     已排队、将在下一个行情步生效的手动跳跃（对数收益率），没有则为 0
 */
public record IndexState(double logFundamental, double slowDeviation, double jumpDeviation, double volatilityRatio,
        Regime regime, double pendingJump) {

    public IndexState {
        if (!Double.isFinite(logFundamental) || !Double.isFinite(slowDeviation) || !Double.isFinite(jumpDeviation)
                || !Double.isFinite(pendingJump)) {
            throw new IllegalArgumentException("指数价格模型的状态必须是有限的数");
        }
        if (!(volatilityRatio > 0) || !Double.isFinite(volatilityRatio)) {
            throw new IllegalArgumentException("条件方差比必须是有限的正数：" + volatilityRatio);
        }
    }

    /** 新市场，或只记得价格的旧存档：基本面价值等于价格，没有偏离，处于震荡、平均波动水平。 */
    public static IndexState startingAt(double price) {
        return new IndexState(Math.log(price), 0.0, 0.0, 1.0, Regime.RANGE, 0.0);
    }

    /** 此刻的指数价格。 */
    public double price() {
        return Math.exp(logFundamental + slowDeviation + jumpDeviation);
    }
}
