package io.github.djp440.creepercapital.core.market;

import java.util.Arrays;

/**
 * 市场状态：行情所处的牛市、熊市或震荡阶段，决定基本面价值的漂移方向、波动水平和溢价的偏移方向，会随机切换。
 * <p>
 * 每个状态的平均持续时间不同，离开时按固定概率转到另外两个状态之一；牛市与熊市对称，所以长期看没有整体偏向。
 */
public enum Regime {
    /** 牛市：基本面价值上漂，波动偏低，溢价偏正。 */
    BULL(3.0, +0.35, 1.0, +1),
    /** 熊市：基本面价值下漂，波动偏高，溢价偏负。 */
    BEAR(3.0, -0.35, 1.3, -1),
    /** 震荡：没有趋势，波动最低，溢价围绕 0。 */
    RANGE(4.0, 0.0, 0.8, 0);

    /** 离开某个状态时转到各状态的概率，行是当前状态、列是目标状态，顺序同枚举声明（牛、熊、震荡）。 */
    private static final double[][] TRANSITIONS = {
            {0.0, 0.3, 0.7},
            {0.3, 0.0, 0.7},
            {0.5, 0.5, 0.0},
    };

    /** 平均持续的市场日数。 */
    private final double meanDays;
    /** 基本面价值每个市场日的漂移，以日波动率的倍数计。 */
    private final double driftPerDay;
    /** 归一化之前的波动倍数。 */
    private final double rawVolatility;
    /** 溢价偏移的方向：+1 偏正，-1 偏负，0 不偏。 */
    private final int premiumSign;

    Regime(double meanDays, double driftPerDay, double rawVolatility, int premiumSign) {
        this.meanDays = meanDays;
        this.driftPerDay = driftPerDay;
        this.rawVolatility = rawVolatility;
        this.premiumSign = premiumSign;
    }

    /** 每个行情步离开当前状态的概率（持续时间服从几何分布，均值为 {@link #meanDays}）。 */
    double leaveProbability() {
        return 1.0 / (meanDays * IndexModel.STEPS_PER_DAY);
    }

    /** 基本面价值每个市场日的漂移，以日波动率的倍数计。 */
    double driftPerDay() {
        return driftPerDay;
    }

    /**
     * 扩散项的波动倍数。三个状态按长期时间占比加权后，波动方差的均值恰为 1，
     * 所以状态切换只改变波动在时间上的分布，不改变整体的日波动。
     */
    double volatility() {
        return rawVolatility / Shares.NORMALIZER;
    }

    /** 溢价偏移的方向：+1 偏正，-1 偏负，0 不偏。 */
    int premiumSign() {
        return premiumSign;
    }

    /**
     * 离开当前状态时转到哪个状态。
     *
     * @param u 均匀分布于 [0, 1) 的随机数
     */
    Regime successor(double u) {
        double[] row = TRANSITIONS[ordinal()];
        double cumulative = 0;
        Regime[] regimes = values();
        for (int i = 0; i < regimes.length; i++) {
            cumulative += row[i];
            if (u < cumulative) {
                return regimes[i];
            }
        }
        return regimes[regimes.length - 1];
    }

    /** 三个状态的长期时间占比，由转移表迭代得出，用来归一化波动倍数、折算漂移造成的日方差。 */
    static final class Shares {
        private static final double[] SHARES = stationaryTimeShares();
        static final double NORMALIZER = Math.sqrt(meanSquareVolatility());

        private Shares() {
        }

        static double of(Regime regime) {
            return SHARES[regime.ordinal()];
        }

        private static double meanSquareVolatility() {
            double sum = 0;
            for (Regime regime : values()) {
                sum += SHARES[regime.ordinal()] * regime.rawVolatility * regime.rawVolatility;
            }
            return sum;
        }

        /** 嵌入链（只看「离开时转到哪」）的平稳分布乘以各状态的平均持续时间，归一化后即时间占比。 */
        private static double[] stationaryTimeShares() {
            Regime[] regimes = values();
            double[] visits = new double[regimes.length];
            Arrays.fill(visits, 1.0 / regimes.length);
            for (int iteration = 0; iteration < 1_000; iteration++) {
                double[] next = new double[regimes.length];
                for (Regime from : regimes) {
                    for (Regime to : regimes) {
                        next[to.ordinal()] += visits[from.ordinal()] * TRANSITIONS[from.ordinal()][to.ordinal()];
                    }
                }
                visits = next;
            }
            double total = 0;
            double[] shares = new double[regimes.length];
            for (Regime regime : regimes) {
                shares[regime.ordinal()] = visits[regime.ordinal()] * regime.meanDays;
                total += shares[regime.ordinal()];
            }
            for (int i = 0; i < shares.length; i++) {
                shares[i] /= total;
            }
            return shares;
        }
    }
}
