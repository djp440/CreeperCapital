package io.github.djp440.creepercapital.server;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.ContractState;
import io.github.djp440.creepercapital.core.market.IndexState;
import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketState;
import io.github.djp440.creepercapital.core.market.PricingState;
import io.github.djp440.creepercapital.core.market.Regime;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 世界存档里的市场：市场时间、暂停状态，以及每个合约的行情模型状态、溢价及其采样、随机数状态和正在走的 1m K 线，
 * 存为 {@code data/creepercapital/market.dat}。已收盘的 K 线不在这里，它们追加写进世界目录下的
 * {@code creepercapital/klines}，见 {@link ServerMarket}。
 * <p>
 * 读出来的只是状态：要等服务器读完合约配置，再用 {@link #bind} 以当前配置恢复成活的 {@link Market}。
 * 保存时才取活市场的当前状态编码，所以行情推进时只需要标记为脏。
 * <p>
 * 兼容旧存档：#6 及以前的存档只有一个 DIAMONDCRP 的指数价格，读出来转成单合约的状态。
 */
public final class MarketSavedData extends SavedData {
    private static final String LEGACY_SYMBOL = "DIAMONDCRP";

    private static final Codec<PricingState> PRICING_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("premium").forGetter(PricingState::premium),
            Codec.INT.fieldOf("premium_index_samples").forGetter(PricingState::premiumIndexSamples),
            Codec.DOUBLE.fieldOf("premium_index_weighted_sum").forGetter(PricingState::premiumIndexWeightedSum),
            Codec.DOUBLE.listOf().fieldOf("basis_samples").forGetter(PricingState::basisSamples))
            .apply(i, PricingState::new));

    private static final Codec<Candle> CANDLE_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("start").forGetter(Candle::start),
            Codec.DOUBLE.fieldOf("open").forGetter(Candle::open),
            Codec.DOUBLE.fieldOf("high").forGetter(Candle::high),
            Codec.DOUBLE.fieldOf("low").forGetter(Candle::low),
            Codec.DOUBLE.fieldOf("close").forGetter(Candle::close),
            Codec.DOUBLE.fieldOf("volume").forGetter(Candle::volume))
            .apply(i, Candle::new));

    private static final Codec<Regime> REGIME_CODEC = Codec.STRING.comapFlatMap(name -> {
        try {
            return DataResult.success(Regime.valueOf(name));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "未知的市场状态：" + name);
        }
    }, Regime::name);

    private static final Codec<IndexState> INDEX_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("log_fundamental").forGetter(IndexState::logFundamental),
            Codec.DOUBLE.fieldOf("slow_deviation").forGetter(IndexState::slowDeviation),
            Codec.DOUBLE.fieldOf("jump_deviation").forGetter(IndexState::jumpDeviation),
            Codec.DOUBLE.fieldOf("volatility_ratio").forGetter(IndexState::volatilityRatio),
            REGIME_CODEC.fieldOf("regime").forGetter(IndexState::regime),
            Codec.DOUBLE.optionalFieldOf("pending_jump", 0.0).forGetter(IndexState::pendingJump))
            .apply(i, IndexState::new));

    private static final Codec<ContractState> CONTRACT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("symbol").forGetter(ContractState::symbol),
            INDEX_CODEC.fieldOf("index").forGetter(ContractState::index),
            Codec.LONG.fieldOf("random_state").forGetter(ContractState::randomState),
            PRICING_CODEC.fieldOf("pricing").forGetter(ContractState::pricing),
            Codec.DOUBLE.fieldOf("last_volume").forGetter(ContractState::lastVolume),
            CANDLE_CODEC.optionalFieldOf("live_minute").forGetter(state -> Optional.ofNullable(state.liveMinute())))
            .apply(i, (symbol, index, randomState, pricing, lastVolume, liveMinute) ->
                    new ContractState(symbol, index, randomState, pricing, lastVolume, liveMinute.orElse(null))));

    private static final Codec<MarketState> CURRENT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("seed").forGetter(MarketState::seed),
            Codec.LONG.fieldOf("step").forGetter(MarketState::step),
            Codec.INT.fieldOf("ticks_into_step").forGetter(MarketState::ticksIntoStep),
            // 暂停状态在 #4 才有，更早的存档没有这一项
            Codec.BOOL.optionalFieldOf("paused", false).forGetter(MarketState::paused),
            CONTRACT_CODEC.listOf().fieldOf("contracts").forGetter(MarketState::contracts))
            .apply(i, MarketState::new));

    /** #6 及以前的存档格式：整个市场只有 DIAMONDCRP 一个指数价格和一个随机数状态。 */
    private static final Codec<MarketState> LEGACY_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("step").forGetter(MarketState::step),
            Codec.INT.fieldOf("ticks_into_step").forGetter(MarketState::ticksIntoStep),
            Codec.DOUBLE.fieldOf("index_price").forGetter(state -> state.contracts().get(0).index().price()),
            Codec.LONG.fieldOf("random_state").forGetter(state -> state.contracts().get(0).randomState()),
            Codec.BOOL.optionalFieldOf("paused", false).forGetter(MarketState::paused),
            // 双层定价之前的存档没有这一项，从零溢价开始
            PRICING_CODEC.optionalFieldOf("pricing", PricingState.INITIAL)
                    .forGetter(state -> state.contracts().get(0).pricing()),
            // K 线之前的存档没有下面两项：成交量从 0 开始，当前 1m K 线从下一个行情步重新开出
            Codec.DOUBLE.optionalFieldOf("last_volume", 0.0).forGetter(state -> state.contracts().get(0).lastVolume()),
            CANDLE_CODEC.optionalFieldOf("live_minute")
                    .forGetter(state -> Optional.ofNullable(state.contracts().get(0).liveMinute())))
            .apply(i, MarketSavedData::fromLegacy));

    private static final Codec<MarketState> STATE_CODEC = Codec.withAlternative(CURRENT_CODEC, LEGACY_CODEC);

    public static final Codec<MarketSavedData> CODEC = STATE_CODEC.xmap(MarketSavedData::loaded, MarketSavedData::state);

    /** 新世界的市场由 {@link ServerMarket} 按世界种子显式创建，这里的工厂不会被调用。 */
    public static final SavedDataType<MarketSavedData> TYPE = new SavedDataType<>(
            CreeperCapital.id("market"),
            () -> {
                throw new IllegalStateException("市场必须按世界种子显式创建");
            },
            CODEC);

    /** 从存档读出、还没有按配置恢复成活市场时的状态。 */
    private @Nullable MarketState pending;
    private @Nullable Market market;

    private MarketSavedData(@Nullable MarketState pending, @Nullable Market market) {
        this.pending = pending;
        this.market = market;
    }

    /** 包装一个已经在运行的市场。 */
    public static MarketSavedData of(Market market) {
        return new MarketSavedData(null, market);
    }

    private static MarketSavedData loaded(MarketState state) {
        return new MarketSavedData(state, null);
    }

    /**
     * 旧存档转成新的状态：DIAMONDCRP 从旧存档的指数价格重新起步（模型状态取起始值），
     * 随机数状态沿用旧的；市场种子取旧随机数状态，之后配置里新加的合约据此取得各自的随机数流。
     */
    private static MarketState fromLegacy(long step, int ticksIntoStep, double indexPrice, long randomState,
            boolean paused, PricingState pricing, double lastVolume, Optional<Candle> liveMinute) {
        ContractState diamond = new ContractState(LEGACY_SYMBOL, IndexState.startingAt(indexPrice), randomState,
                pricing, lastVolume, liveMinute.orElse(null));
        return new MarketState(randomState, step, ticksIntoStep, paused, List.of(diamond));
    }

    /**
     * 以当前的合约配置恢复成活的市场；已经是活的就原样返回。
     * 配置里新加的合约从起始价格开始，配置里去掉的合约状态原样留在存档里。
     */
    public Market bind(List<ContractSpec> specs) {
        if (market == null) {
            market = Market.restore(pending, specs);
            pending = null;
        }
        return market;
    }

    /** 此刻要写进存档的状态。 */
    public MarketState state() {
        return market != null ? market.state() : pending;
    }
}
