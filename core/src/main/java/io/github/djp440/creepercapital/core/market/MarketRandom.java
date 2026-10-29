package io.github.djp440.creepercapital.core.market;

/**
 * 行情用的随机数发生器（SplitMix64）。
 * <p>
 * 全部状态只有一个 long，可以随存档保存并原样恢复；{@link java.util.Random} 的内部状态取不出来，所以不用它。
 */
final class MarketRandom {
    private long state;

    MarketRandom(long state) {
        this.state = state;
    }

    /** 当前状态，传给 {@code new MarketRandom(state)} 可以从这里继续。 */
    long state() {
        return state;
    }

    private long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 均匀分布于 [0, 1)。 */
    private double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** 标准正态分布（Box-Muller，每次调用消耗两个均匀随机数，不缓存多余的一个，保证状态只有 {@link #state()}）。 */
    double nextGaussian() {
        double u1 = 1.0 - nextDouble();
        double u2 = nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }
}
