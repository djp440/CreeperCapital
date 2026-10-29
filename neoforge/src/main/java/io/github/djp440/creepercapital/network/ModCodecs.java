package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.MarginTiers;
import io.github.djp440.creepercapital.core.market.MarketTrade;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.CancelReason;
import io.github.djp440.creepercapital.core.trading.FundingRecord;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderExtras;
import io.github.djp440.creepercapital.core.trading.OrderStatus;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
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

    static final StreamCodec<ByteBuf, MarginMode> MARGIN_MODE = ByteBufCodecs.VAR_INT
            .map(ordinal -> MarginMode.values()[Math.floorMod(ordinal, MarginMode.values().length)], MarginMode::ordinal);

    static final StreamCodec<ByteBuf, MarginTiers.Definition> MARGIN_TIER = StreamCodec.composite(
            MONEY, MarginTiers.Definition::upTo,
            ByteBufCodecs.VAR_INT, MarginTiers.Definition::maxLeverage,
            ByteBufCodecs.DOUBLE, MarginTiers.Definition::maintenanceRate,
            MarginTiers.Definition::new);

    /** 分档同步给客户端：界面据此用和服务端同一份公式算保证金率与强平价；档位至多 16 档防止恶意数据。 */
    static final StreamCodec<ByteBuf, MarginTiers> MARGIN_TIERS = MARGIN_TIER.apply(ByteBufCodecs.list(16))
            .map(MarginTiers::of, MarginTiers::definitions);

    static final StreamCodec<ByteBuf, Position> POSITION = StreamCodec.composite(
            SYMBOL, Position::symbol,
            SIDE, Position::side,
            ByteBufCodecs.VAR_LONG, Position::lots,
            ByteBufCodecs.DOUBLE, Position::entryPrice,
            ByteBufCodecs.VAR_INT, Position::leverage,
            MONEY, Position::margin,
            ByteBufCodecs.VAR_INT, Position::quantityDecimals,
            MARGIN_MODE, Position::mode,
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

    static final StreamCodec<ByteBuf, FundingRecord> FUNDING = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, FundingRecord::id,
            UUIDUtil.STREAM_CODEC, FundingRecord::player,
            SYMBOL, FundingRecord::symbol,
            SIDE, FundingRecord::side,
            MARGIN_MODE, FundingRecord::mode,
            ByteBufCodecs.VAR_LONG, FundingRecord::lots,
            ByteBufCodecs.DOUBLE, FundingRecord::rate,
            ByteBufCodecs.DOUBLE, FundingRecord::markPrice,
            MONEY, FundingRecord::notional,
            MONEY, FundingRecord::amount,
            MONEY, FundingRecord::shortfall,
            ByteBufCodecs.VAR_LONG, FundingRecord::marketSeconds,
            FundingRecord::new);

    static final StreamCodec<ByteBuf, MarketTrade> MARKET_TRADE = StreamCodec.composite(
            SYMBOL, MarketTrade::symbol,
            ByteBufCodecs.VAR_LONG, MarketTrade::marketSeconds,
            ByteBufCodecs.BOOL, MarketTrade::buy,
            ByteBufCodecs.DOUBLE, MarketTrade::price,
            ByteBufCodecs.VAR_LONG, MarketTrade::lots,
            ByteBufCodecs.stringUtf8(64), MarketTrade::player,
            MarketTrade::new);

    private static <E extends Enum<E>> StreamCodec<ByteBuf, E> enumCodec(Class<E> type) {
        E[] values = type.getEnumConstants();
        return ByteBufCodecs.VAR_INT.map(ordinal -> values[Math.floorMod(ordinal, values.length)], Enum::ordinal);
    }

    private static final StreamCodec<ByteBuf, OrderType> ORDER_TYPE = enumCodec(OrderType.class);
    private static final StreamCodec<ByteBuf, OrderStatus> ORDER_STATUS = enumCodec(OrderStatus.class);
    private static final StreamCodec<ByteBuf, CancelReason> CANCEL_REASON = enumCodec(CancelReason.class);
    private static final StreamCodec<ByteBuf, TriggerSource> TRIGGER_SOURCE = enumCodec(TriggerSource.class);

    static final StreamCodec<ByteBuf, OrderExtras> ORDER_EXTRAS = StreamCodec.composite(
            ByteBufCodecs.BOOL, OrderExtras::reduceOnly,
            TRIGGER_SOURCE, OrderExtras::triggerSource,
            ByteBufCodecs.DOUBLE, OrderExtras::attachedTakeProfit,
            ByteBufCodecs.DOUBLE, OrderExtras::attachedStopLoss,
            OrderExtras::new);

    /** 委托有 16 个字段，超过 {@code StreamCodec.composite} 的上限，所以逐字段读写。 */
    static final StreamCodec<ByteBuf, Order> ORDER = StreamCodec.of(
            (buf, order) -> {
                ByteBufCodecs.VAR_LONG.encode(buf, order.id());
                UUIDUtil.STREAM_CODEC.encode(buf, order.player());
                SYMBOL.encode(buf, order.symbol());
                SIDE.encode(buf, order.side());
                ORDER_TYPE.encode(buf, order.type());
                ByteBufCodecs.DOUBLE.encode(buf, order.price());
                ByteBufCodecs.VAR_LONG.encode(buf, order.lots());
                ByteBufCodecs.VAR_INT.encode(buf, order.leverage());
                MONEY.encode(buf, order.frozen());
                ORDER_STATUS.encode(buf, order.status());
                ByteBufCodecs.DOUBLE.encode(buf, order.fillPrice());
                MONEY.encode(buf, order.fee());
                ByteBufCodecs.VAR_LONG.encode(buf, order.createdSeconds());
                ByteBufCodecs.VAR_LONG.encode(buf, order.closedSeconds());
                CANCEL_REASON.encode(buf, order.cancelReason());
                ORDER_EXTRAS.encode(buf, order.extras());
            },
            buf -> new Order(ByteBufCodecs.VAR_LONG.decode(buf), UUIDUtil.STREAM_CODEC.decode(buf), SYMBOL.decode(buf),
                    SIDE.decode(buf), ORDER_TYPE.decode(buf), ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), MONEY.decode(buf),
                    ORDER_STATUS.decode(buf), ByteBufCodecs.DOUBLE.decode(buf), MONEY.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), CANCEL_REASON.decode(buf),
                    ORDER_EXTRAS.decode(buf)));

    static final StreamCodec<ByteBuf, TradingParams> TRADING_PARAMS = StreamCodec.composite(
            SYMBOL, TradingParams::symbol,
            ByteBufCodecs.DOUBLE, TradingParams::spread,
            ByteBufCodecs.VAR_INT, TradingParams::maxLeverage,
            ByteBufCodecs.VAR_INT, TradingParams::quantityDecimals,
            ByteBufCodecs.DOUBLE, TradingParams::depthSlippage,
            MARGIN_TIERS, TradingParams::marginTiers,
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
