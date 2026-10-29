package io.github.djp440.creepercapital.core.market;

import java.util.Optional;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.core.kline.Klines;
import io.github.djp440.creepercapital.core.kline.Retention;
import io.github.djp440.creepercapital.core.kline.Stats24h;

/**
 * 一个合约的行情：指数价格模型、溢价与资金费率、K 线，以及自己专属的随机数流。
 * 市场时钟（行情步、暂停）由 {@link Market} 统一管理，所有合约共用。
 */
final class ContractMarket {
    private final ContractSpec spec;
    private final MarketRandom random;
    private final IndexModel index;
    private final ContractPricing pricing;
    private final Klines klines;
    /** 最近一个行情步的成交量。 */
    private double lastVolume;

    /** 新合约：从起始价格开始，随机数流由市场种子和交易代码决定。 */
    ContractMarket(long marketSeed, ContractSpec spec) {
        this.spec = spec;
        this.random = MarketRandom.forContract(marketSeed, spec.symbol());
        this.index = new IndexModel(calibrationOf(spec), IndexState.startingAt(spec.initialPrice()));
        this.pricing = new ContractPricing(PricingState.INITIAL);
        this.klines = new Klines(Retention.DEFAULT);
    }

    /** 从存档恢复：用配置里的当前定义（波动率倾向可能已被改过）接着保存的状态走。 */
    ContractMarket(ContractSpec spec, ContractState state) {
        this.spec = spec;
        this.random = new MarketRandom(state.randomState());
        this.index = new IndexModel(calibrationOf(spec), state.index());
        this.pricing = new ContractPricing(state.pricing());
        this.lastVolume = state.lastVolume();
        this.klines = new Klines(Retention.DEFAULT, state.liveMinute());
    }

    private static IndexModel.Calibration calibrationOf(ContractSpec spec) {
        return IndexModel.Calibration.of(spec.symbol(), spec.initialPrice(), spec.volatility());
    }

    ContractSpec spec() {
        return spec;
    }

    ContractState state() {
        return new ContractState(spec.symbol(), index.state(), random.state(), pricing.state(), lastVolume,
                klines.liveMinute().orElse(null));
    }

    Regime regime() {
        return index.regime();
    }

    /** 排队一次手动跳跃，下一个行情步生效。 */
    void queueJump(double changeRatio) {
        index.queueJump(Math.log1p(changeRatio));
    }

    /**
     * 走一个行情步。
     *
     * @param step   走完之后的行情步序号
     * @param jumped 这一步里发生的每次跳跃都通过它报告
     * @return 这一步的快照
     */
    MarketSnapshot next(long step, Consumer<JumpEvent> jumped) {
        MarketTime previous = MarketTime.ofStep(step - 1);
        MarketTime now = MarketTime.ofStep(step);
        Regime regime = index.regime();
        IndexModel.Step moved = index.next(random);
        lastVolume = spec.baseVolume() * index.volumeMultiplier(moved.logReturn());
        double price = index.price();
        pricing.next(previous, now, price, random, regime.premiumSign() * spec.volatility().premiumBias());
        if (moved.randomJump() != 0) {
            jumped.accept(new JumpEvent(spec.symbol(), step, Math.expm1(moved.randomJump()), JumpEvent.Cause.RANDOM));
        }
        if (moved.manualJump() != 0) {
            jumped.accept(new JumpEvent(spec.symbol(), step, Math.expm1(moved.manualJump()), JumpEvent.Cause.MANUAL));
        }
        MarketSnapshot snapshot = snapshot(step);
        klines.record(now.totalSeconds(), snapshot.lastPrice(), snapshot.volume());
        return snapshot;
    }

    MarketSnapshot snapshot(long step) {
        double price = index.price();
        return new MarketSnapshot(step, spec.symbol(), price, pricing.lastPrice(price),
                pricing.markPrice(price, MarketTime.ofStep(step)), pricing.fundingRate(), lastVolume);
    }

    Klines klines() {
        return klines;
    }

    Optional<Stats24h> stats24h() {
        return klines.stats24h();
    }
}
