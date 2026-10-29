package io.github.djp440.creepercapital.core.market;

import java.util.Optional;

/**
 * 全服共享的市场，只由服务器 tick 推进（ADR-0003）。
 */
public final class Market {
    /** 每个行情步包含的服务器 tick 数。 */
    public static final int TICKS_PER_STEP = 20;
    /** 每个行情步跨越的市场秒数：1 真实秒对应 6 市场秒（ADR-0003）。 */
    static final int MARKET_SECONDS_PER_STEP = 6;

    private static final String SYMBOL = "DIAMONDCRP";
    private static final double INITIAL_INDEX_PRICE = 1000.0;

    private final RandomWalkIndexPrice indexPrice;
    private int ticksIntoStep;
    private long step;

    /**
     * @param seed 行情随机数种子，同一种子走出同一条行情
     */
    public Market(long seed) {
        this.indexPrice = new RandomWalkIndexPrice(INITIAL_INDEX_PRICE, seed);
    }

    /**
     * 推进一个服务器 tick。
     *
     * @return 这个 tick 恰好完成一个行情步时返回该步的快照，否则为空
     */
    public Optional<MarketSnapshot> tick() {
        ticksIntoStep++;
        if (ticksIntoStep < TICKS_PER_STEP) {
            return Optional.empty();
        }
        ticksIntoStep = 0;
        step++;
        indexPrice.next();
        return Optional.of(snapshot());
    }

    public MarketSnapshot snapshot() {
        return new MarketSnapshot(step, SYMBOL, indexPrice.current());
    }
}
