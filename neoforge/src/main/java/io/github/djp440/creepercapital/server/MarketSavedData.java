package io.github.djp440.creepercapital.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketState;
import io.github.djp440.creepercapital.core.market.PricingState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 世界存档里的市场：市场时间、价格、溢价及其采样和随机数状态，存为 {@code data/creepercapital/market.dat}。
 * <p>
 * 持有的是活的 {@link Market}，保存时才取其当前状态编码，所以行情推进时只需要标记为脏。
 */
public final class MarketSavedData extends SavedData {
    private static final Codec<PricingState> PRICING_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("premium").forGetter(PricingState::premium),
            Codec.INT.fieldOf("premium_index_samples").forGetter(PricingState::premiumIndexSamples),
            Codec.DOUBLE.fieldOf("premium_index_weighted_sum").forGetter(PricingState::premiumIndexWeightedSum),
            Codec.DOUBLE.listOf().fieldOf("basis_samples").forGetter(PricingState::basisSamples))
            .apply(i, PricingState::new));

    private static final Codec<MarketState> STATE_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("step").forGetter(MarketState::step),
            Codec.INT.fieldOf("ticks_into_step").forGetter(MarketState::ticksIntoStep),
            Codec.DOUBLE.fieldOf("index_price").forGetter(MarketState::indexPrice),
            Codec.LONG.fieldOf("random_state").forGetter(MarketState::randomState),
            Codec.BOOL.optionalFieldOf("paused", false).forGetter(MarketState::paused),
            // 双层定价之前的存档没有这一项，从零溢价开始
            PRICING_CODEC.optionalFieldOf("pricing", PricingState.INITIAL).forGetter(MarketState::pricing))
            .apply(i, MarketState::new));

    public static final Codec<MarketSavedData> CODEC = STATE_CODEC.xmap(
            state -> new MarketSavedData(Market.restore(state)),
            data -> data.market.state());

    /** 新世界的市场由 {@link ServerMarket} 按世界种子显式创建，这里的工厂不会被调用。 */
    public static final SavedDataType<MarketSavedData> TYPE = new SavedDataType<>(
            CreeperCapital.id("market"),
            () -> {
                throw new IllegalStateException("市场必须按世界种子显式创建");
            },
            CODEC);

    private final Market market;

    public MarketSavedData(Market market) {
        this.market = market;
    }

    public Market market() {
        return market;
    }
}
