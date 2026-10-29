package io.github.djp440.creepercapital.core.market;

import java.util.Optional;

import io.github.djp440.creepercapital.core.kline.Klines;
import io.github.djp440.creepercapital.core.kline.Retention;
import io.github.djp440.creepercapital.core.kline.Stats24h;

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

    private final MarketRandom random;
    private final RandomWalkIndexPrice indexPrice;
    private final ContractPricing pricing;
    private final Klines klines;
    private int ticksIntoStep;
    private long step;
    private boolean paused;
    /** 最近一个行情步的成交量。 */
    private double lastVolume;

    /**
     * @param seed 行情随机数种子，同一种子走出同一条行情
     */
    public Market(long seed) {
        this.random = new MarketRandom(seed);
        this.indexPrice = new RandomWalkIndexPrice(INITIAL_INDEX_PRICE, random);
        this.pricing = new ContractPricing(PricingState.INITIAL);
        this.klines = new Klines(Retention.DEFAULT);
    }

    private Market(MarketState state) {
        this.random = new MarketRandom(state.randomState());
        this.indexPrice = new RandomWalkIndexPrice(state.indexPrice(), random);
        this.pricing = new ContractPricing(state.pricing());
        this.ticksIntoStep = state.ticksIntoStep();
        this.step = state.step();
        this.paused = state.paused();
        this.lastVolume = state.lastVolume();
        this.klines = new Klines(Retention.DEFAULT, state.liveMinute());
    }

    /** 从保存的状态恢复市场，接着停下的地方走，不补算停机期间的行情。 */
    public static Market restore(MarketState state) {
        return new Market(state);
    }

    /**
     * 需要持久化的全部状态，包括是否暂停和正在走的 1m K 线。已收盘的 K 线不在其中，
     * 由宿主用 {@link io.github.djp440.creepercapital.core.kline.KlineStore#bind} 接到世界目录下的文件。
     */
    public MarketState state() {
        return new MarketState(step, ticksIntoStep, indexPrice.current(), random.state(), paused, pricing.state(),
                lastVolume, klines.liveMinute().orElse(null));
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
        MarketTime previous = MarketTime.ofStep(step);
        step++;
        lastVolume = RandomWalkIndexPrice.volumeFor(indexPrice.next());
        pricing.next(previous, MarketTime.ofStep(step), indexPrice.current(), random);
        MarketSnapshot snapshot = snapshot();
        klines.record(snapshot.time().totalSeconds(), snapshot.lastPrice(), snapshot.volume());
        return Optional.of(snapshot);
    }

    /**
     * 不等服务器 tick，直接推进若干个行情步，结果与逐 tick 推进完全相同（内部仍按 20 个 tick 凑一个行情步）。
     * 仅供开发命令快进行情用，正常运行时市场只由服务器 tick 推进（ADR-0003）。暂停时不前进。
     *
     * @return 最后一步的快照；一步也没走时为空
     */
    public Optional<MarketSnapshot> advanceSteps(int steps) {
        Optional<MarketSnapshot> last = Optional.empty();
        int done = 0;
        while (done < steps && !paused) {
            Optional<MarketSnapshot> snapshot = tick();
            if (snapshot.isPresent()) {
                last = snapshot;
                done++;
            }
        }
        return last;
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
        double index = indexPrice.current();
        return new MarketSnapshot(step, SYMBOL, index, pricing.lastPrice(index),
                pricing.markPrice(index, MarketTime.ofStep(step)), pricing.fundingRate(), lastVolume);
    }

    /** 本合约的 K 线（以最新价格计）。 */
    public Klines klines() {
        return klines;
    }

    /** 行情栏的 24h 统计，与 K 线数据一致；还没有行情步时为空。 */
    public Optional<Stats24h> stats24h() {
        return klines.stats24h();
    }
}
