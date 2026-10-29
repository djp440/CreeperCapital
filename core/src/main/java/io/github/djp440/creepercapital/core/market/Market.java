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
    private boolean paused;

    /**
     * @param seed 行情随机数种子，同一种子走出同一条行情
     */
    public Market(long seed) {
        this.indexPrice = new RandomWalkIndexPrice(INITIAL_INDEX_PRICE, seed);
    }

    private Market(MarketState state) {
        this.indexPrice = new RandomWalkIndexPrice(state.indexPrice(), new MarketRandom(state.randomState()));
        this.ticksIntoStep = state.ticksIntoStep();
        this.step = state.step();
        this.paused = state.paused();
    }

    /** 从保存的状态恢复市场，接着停下的地方走，不补算停机期间的行情。 */
    public static Market restore(MarketState state) {
        return new Market(state);
    }

    /** 需要持久化的全部状态，包括是否暂停。 */
    public MarketState state() {
        return new MarketState(step, ticksIntoStep, indexPrice.current(), indexPrice.randomState(), paused);
    }

    /**
     * 推进一个服务器 tick。暂停时什么都不发生：行情步、价格、市场时间和 tick 计数全部冻结。
     *
     * @return 这个 tick 恰好完成一个行情步时返回该步的快照，否则为空
     */
    public Optional<MarketSnapshot> tick() {
        if (paused) {
            return Optional.empty();
        }
        ticksIntoStep++;
        if (ticksIntoStep < TICKS_PER_STEP) {
            return Optional.empty();
        }
        ticksIntoStep = 0;
        step++;
        indexPrice.next();
        return Optional.of(snapshot());
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    public MarketSnapshot snapshot() {
        return new MarketSnapshot(step, SYMBOL, indexPrice.current());
    }
}
