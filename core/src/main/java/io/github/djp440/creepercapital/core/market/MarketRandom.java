package io.github.djp440.creepercapital.core.market;

/**
 * 行情用的随机数发生器（SplitMix64）。
 * <p>
 * 全部状态只有一个 long，可以随存档保存并原样恢复；{@link java.util.Random} 的内部状态取不出来，所以不用它。
 * 下面所有分布都只靠这一个状态推出，不缓存任何中间值。
 */
final class MarketRandom {
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    /** 让模拟成交的流与行情的流（同一个种子和交易代码）错开。 */
    private static final long TRADES_SALT = 0x5DEECE66DL;

    private long state;

    MarketRandom(long state) {
        this.state = state;
    }

    /**
     * 某个合约专属的随机数流：由市场种子和交易代码共同决定，所以合约之间互不影响，
     * 增删别的合约也不会改变这个合约的走势。{@link String#hashCode()} 的算法由语言规范固定，跨平台一致。
     */
    static MarketRandom forContract(long marketSeed, String symbol) {
        return new MarketRandom(new MarketRandom(marketSeed + symbol.hashCode() * GOLDEN_GAMMA).nextLong());
    }

    /**
     * 某合约某个行情步专属的「模拟成交」随机数流：由市场种子、交易代码和行情步序号决定，与行情自己的流（{@link #forContract}）
     * 互不影响，所以拆分成交不会改变价格路径，同一步永远得到同一个流。
     */
    static MarketRandom forTrades(long marketSeed, String symbol, long step) {
        long base = new MarketRandom(marketSeed + symbol.hashCode() * GOLDEN_GAMMA + TRADES_SALT).nextLong();
        return new MarketRandom(new MarketRandom(base + step * GOLDEN_GAMMA).nextLong());
    }

    /** 当前状态，传给 {@code new MarketRandom(state)} 可以从这里继续。 */
    long state() {
        return state;
    }

    private long nextLong() {
        state += GOLDEN_GAMMA;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 均匀分布于 [0, 1)。 */
    double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** 标准正态分布（Box-Muller，每次调用消耗两个均匀随机数，不缓存多余的一个，保证状态只有 {@link #state()}）。 */
    double nextGaussian() {
        double u1 = 1.0 - nextDouble();
        double u2 = nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    /**
     * 方差为 1 的 Student-t 分布（自由度 {@code degreesOfFreedom} 必须大于 4，保证峰度有限）：肥尾，偶尔出现远超正态的大冲击。
     * 由正态数除以 sqrt(卡方/自由度) 构成，卡方用 Gamma(自由度/2, 2) 抽取。
     */
    double nextStandardizedStudentT(int degreesOfFreedom) {
        double chiSquare = 2.0 * nextGamma(degreesOfFreedom / 2.0);
        double student = nextGaussian() / Math.sqrt(chiSquare / degreesOfFreedom);
        return student * Math.sqrt((degreesOfFreedom - 2.0) / degreesOfFreedom);
    }

    /** Gamma(shape, 1) 分布，shape 不小于 1（Marsaglia-Tsang）。消耗的随机数个数不固定，但状态仍只有 {@link #state()}。 */
    private double nextGamma(double shape) {
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x = nextGaussian();
            double v = 1.0 + c * x;
            if (v <= 0) {
                continue;
            }
            v = v * v * v;
            double u = nextDouble();
            if (Math.log(1.0 - u) < 0.5 * x * x + d - d * v + d * Math.log(v)) {
                return d * v;
            }
        }
    }
}
