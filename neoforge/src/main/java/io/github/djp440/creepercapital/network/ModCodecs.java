package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * core 里的值类型不依赖 MC，它们的网络编解码统一写在适配层。
 */
final class ModCodecs {
    /** 合约的交易代码；长度上限防止恶意客户端发超长字符串。 */
    static final StreamCodec<ByteBuf, String> SYMBOL = ByteBufCodecs.stringUtf8(64);

    static final StreamCodec<ByteBuf, CandlePeriod> PERIOD = ByteBufCodecs.VAR_INT.map(ModCodecs::periodAt, CandlePeriod::ordinal);

    static final StreamCodec<ByteBuf, Candle> CANDLE = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, Candle::start,
            ByteBufCodecs.DOUBLE, Candle::open,
            ByteBufCodecs.DOUBLE, Candle::high,
            ByteBufCodecs.DOUBLE, Candle::low,
            ByteBufCodecs.DOUBLE, Candle::close,
            ByteBufCodecs.DOUBLE, Candle::volume,
            Candle::new);

    /** 金额以最小单位的整数传输，无浮点误差。 */
    static final StreamCodec<ByteBuf, Money> MONEY = ByteBufCodecs.VAR_LONG.map(Money::ofUnits, Money::units);

    static final StreamCodec<ByteBuf, Side> SIDE = ByteBufCodecs.VAR_INT.map(ordinal -> Side.values()[Math.floorMod(ordinal, Side.values().length)], Side::ordinal);

    static final StreamCodec<ByteBuf, TradeKind> TRADE_KIND =
            ByteBufCodecs.VAR_INT.map(ordinal -> TradeKind.values()[Math.floorMod(ordinal, TradeKind.values().length)], TradeKind::ordinal);

    static final StreamCodec<ByteBuf, Position> POSITION = StreamCodec.composite(
            SYMBOL, Position::symbol,
            SIDE, Position::side,
            ByteBufCodecs.VAR_LONG, Position::lots,
            ByteBufCodecs.DOUBLE, Position::entryPrice,
            ByteBufCodecs.VAR_INT, Position::leverage,
            MONEY, Position::margin,
            ByteBufCodecs.VAR_INT, Position::quantityDecimals,
            Position::new);

    static final StreamCodec<ByteBuf, PlayerTrade> TRADE = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, PlayerTrade::id,
            UUIDUtil.STREAM_CODEC, PlayerTrade::player,
            SYMBOL, PlayerTrade::symbol,
            SIDE, PlayerTrade::side,
            TRADE_KIND, PlayerTrade::kind,
            ByteBufCodecs.DOUBLE, PlayerTrade::price,
            ByteBufCodecs.VAR_LONG, PlayerTrade::lots,
            MONEY, PlayerTrade::fee,
            MONEY, PlayerTrade::realizedPnl,
            ByteBufCodecs.VAR_LONG, PlayerTrade::marketSeconds,
            PlayerTrade::new);

    static final StreamCodec<ByteBuf, TradingParams> TRADING_PARAMS = StreamCodec.composite(
            SYMBOL, TradingParams::symbol,
            ByteBufCodecs.DOUBLE, TradingParams::spread,
            ByteBufCodecs.VAR_INT, TradingParams::maxLeverage,
            ByteBufCodecs.VAR_INT, TradingParams::quantityDecimals,
            ByteBufCodecs.DOUBLE, TradingParams::depthSlippage,
            TradingParams::new);

    static final StreamCodec<ByteBuf, Stats24h> STATS_24H = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Stats24h::open,
            ByteBufCodecs.DOUBLE, Stats24h::high,
            ByteBufCodecs.DOUBLE, Stats24h::low,
            ByteBufCodecs.DOUBLE, Stats24h::close,
            ByteBufCodecs.DOUBLE, Stats24h::volume,
            Stats24h::new);

    private ModCodecs() {
    }

    private static CandlePeriod periodAt(int ordinal) {
        CandlePeriod[] periods = CandlePeriod.values();
        if (ordinal < 0 || ordinal >= periods.length) {
            throw new IllegalArgumentException("未知的 K 线周期编号：" + ordinal);
        }
        return periods[ordinal];
    }
}
