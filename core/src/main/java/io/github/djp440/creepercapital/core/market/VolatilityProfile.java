package io.github.djp440.creepercapital.core.market;

/**
 * 波动率倾向：每个合约独有的一组行情性格参数，决定它的波动幅度、跳涨跳跌频率等走势特征。
 *
 * @param dailyVolatility 日波动率：按市场日计的对数收益率标准差（含跳跃），0.03 即 3%
 * @param jumpsPerDay     平均每个市场日发生几次跳跃，0 表示不跳
 * @param jumpMin         跳跃幅度（价格变化比例）的下限，0.05 即 5%
 * @param jumpMax         跳跃幅度的上限；幅度在上下限之间按对数均匀分布，小跳多、大跳少
 * @param premiumBias     溢价随市场状态偏移的幅度：牛市溢价向 +premiumBias 偏，熊市向 -premiumBias 偏，震荡围绕 0
 */
public record VolatilityProfile(double dailyVolatility, double jumpsPerDay, double jumpMin, double jumpMax,
        double premiumBias) {

    public VolatilityProfile {
        requireFinite(dailyVolatility, "dailyVolatility");
        requireFinite(jumpsPerDay, "jumpsPerDay");
        requireFinite(jumpMin, "jumpMin");
        requireFinite(jumpMax, "jumpMax");
        requireFinite(premiumBias, "premiumBias");
        if (dailyVolatility <= 0 || dailyVolatility > 1) {
            throw new IllegalArgumentException("日波动率必须在 (0, 1] 内：" + dailyVolatility);
        }
        if (jumpsPerDay < 0) {
            throw new IllegalArgumentException("每日跳跃次数不能为负：" + jumpsPerDay);
        }
        if (jumpMin <= 0 || jumpMin > jumpMax || jumpMax >= 1) {
            throw new IllegalArgumentException("跳跃幅度必须满足 0 < 最小 <= 最大 < 1：" + jumpMin + " ~ " + jumpMax);
        }
        if (premiumBias < 0 || premiumBias > 0.1) {
            throw new IllegalArgumentException("溢价偏移必须在 [0, 0.1] 内：" + premiumBias);
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " 必须是有限的数：" + value);
        }
    }
}
