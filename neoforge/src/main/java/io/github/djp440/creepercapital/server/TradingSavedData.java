package io.github.djp440.creepercapital.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
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
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.core.trading.TradingState;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 世界存档里的交易账：流动性池余额、玩家的仓位、成交历史与委托（挂单和历史委托），存为 {@code data/creepercapital/trading.dat}。
 * 金额以最小单位的整数存储；玩家余额不在这里，在 {@link AccountSavedData}。
 * <p>
 * 读出来只是状态，服务器启动时用 {@link #bind} 接上玩家账本变成活的 {@link TradingBook}，保存时再取它的状态编码。
 */
public final class TradingSavedData extends SavedData {
    private static final Codec<Money> MONEY = Codec.LONG.xmap(Money::ofUnits, Money::units);
    private static final Codec<Side> SIDE = Codec.STRING.xmap(Side::valueOf, Side::name);
    private static final Codec<TradeKind> KIND = Codec.STRING.xmap(TradeKind::valueOf, TradeKind::name);

    private static final Codec<MarginMode> MODE = Codec.STRING.xmap(MarginMode::valueOf, MarginMode::name);

    private record OwnedPosition(UUID player, Position position) {
    }

    /** 玩家在某个合约上设置的保证金模式（只存和缺省不同的）。 */
    private record ModeSetting(UUID player, String symbol, MarginMode mode) {
    }

    private static final Codec<ModeSetting> MODE_SETTING_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("player").forGetter(ModeSetting::player),
            Codec.STRING.fieldOf("symbol").forGetter(ModeSetting::symbol),
            MODE.fieldOf("mode").forGetter(ModeSetting::mode))
            .apply(i, ModeSetting::new));

    private static final Codec<Position> POSITION_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("symbol").forGetter(Position::symbol),
            SIDE.fieldOf("side").forGetter(Position::side),
            Codec.LONG.fieldOf("lots").forGetter(Position::lots),
            Codec.DOUBLE.fieldOf("entry_price").forGetter(Position::entryPrice),
            Codec.INT.fieldOf("leverage").forGetter(Position::leverage),
            MONEY.fieldOf("margin").forGetter(Position::margin),
            Codec.INT.fieldOf("quantity_decimals").forGetter(Position::quantityDecimals),
            // 旧存档（#11 之前）没有这个字段，都是逐仓
            MODE.optionalFieldOf("margin_mode", MarginMode.ISOLATED).forGetter(Position::mode))
            .apply(i, Position::new));

    private static final Codec<OwnedPosition> OWNED_POSITION_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("player").forGetter(OwnedPosition::player),
            POSITION_CODEC.fieldOf("position").forGetter(OwnedPosition::position))
            .apply(i, OwnedPosition::new));

    private static final Codec<PlayerTrade> TRADE_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("id").forGetter(PlayerTrade::id),
            UUIDUtil.CODEC.fieldOf("player").forGetter(PlayerTrade::player),
            Codec.STRING.fieldOf("symbol").forGetter(PlayerTrade::symbol),
            SIDE.fieldOf("side").forGetter(PlayerTrade::side),
            KIND.fieldOf("kind").forGetter(PlayerTrade::kind),
            Codec.DOUBLE.fieldOf("price").forGetter(PlayerTrade::price),
            Codec.LONG.fieldOf("lots").forGetter(PlayerTrade::lots),
            MONEY.fieldOf("fee").forGetter(PlayerTrade::fee),
            MONEY.fieldOf("realized_pnl").forGetter(PlayerTrade::realizedPnl),
            Codec.LONG.fieldOf("market_seconds").forGetter(PlayerTrade::marketSeconds))
            .apply(i, PlayerTrade::new));

    /** 附加条款是 #15 新增的，旧存档的委托没有这些字段，全部缺省为没有。 */
    private static final Codec<OrderExtras> EXTRAS_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("reduce_only", false).forGetter(OrderExtras::reduceOnly),
            Codec.STRING.xmap(TriggerSource::valueOf, TriggerSource::name)
                    .optionalFieldOf("trigger_source", TriggerSource.LAST).forGetter(OrderExtras::triggerSource),
            Codec.DOUBLE.optionalFieldOf("attached_take_profit", 0.0).forGetter(OrderExtras::attachedTakeProfit),
            Codec.DOUBLE.optionalFieldOf("attached_stop_loss", 0.0).forGetter(OrderExtras::attachedStopLoss))
            .apply(i, OrderExtras::new));

    private static final Codec<Order> ORDER_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("id").forGetter(Order::id),
            UUIDUtil.CODEC.fieldOf("player").forGetter(Order::player),
            Codec.STRING.fieldOf("symbol").forGetter(Order::symbol),
            SIDE.fieldOf("side").forGetter(Order::side),
            Codec.STRING.xmap(OrderType::valueOf, OrderType::name).fieldOf("type").forGetter(Order::type),
            Codec.DOUBLE.fieldOf("price").forGetter(Order::price),
            Codec.LONG.fieldOf("lots").forGetter(Order::lots),
            Codec.INT.fieldOf("leverage").forGetter(Order::leverage),
            MONEY.fieldOf("frozen").forGetter(Order::frozen),
            Codec.STRING.xmap(OrderStatus::valueOf, OrderStatus::name).fieldOf("status").forGetter(Order::status),
            Codec.DOUBLE.fieldOf("fill_price").forGetter(Order::fillPrice),
            MONEY.fieldOf("fee").forGetter(Order::fee),
            Codec.LONG.fieldOf("created_seconds").forGetter(Order::createdSeconds),
            Codec.LONG.fieldOf("closed_seconds").forGetter(Order::closedSeconds),
            Codec.STRING.xmap(CancelReason::valueOf, CancelReason::name).fieldOf("cancel_reason").forGetter(Order::cancelReason),
            EXTRAS_CODEC.optionalFieldOf("extras", OrderExtras.NONE).forGetter(Order::extras))
            .apply(i, Order::new));

    private static final Codec<FundingRecord> FUNDING_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("id").forGetter(FundingRecord::id),
            UUIDUtil.CODEC.fieldOf("player").forGetter(FundingRecord::player),
            Codec.STRING.fieldOf("symbol").forGetter(FundingRecord::symbol),
            SIDE.fieldOf("side").forGetter(FundingRecord::side),
            MODE.fieldOf("margin_mode").forGetter(FundingRecord::mode),
            Codec.LONG.fieldOf("lots").forGetter(FundingRecord::lots),
            Codec.DOUBLE.fieldOf("rate").forGetter(FundingRecord::rate),
            Codec.DOUBLE.fieldOf("mark_price").forGetter(FundingRecord::markPrice),
            MONEY.fieldOf("notional").forGetter(FundingRecord::notional),
            MONEY.fieldOf("amount").forGetter(FundingRecord::amount),
            MONEY.fieldOf("shortfall").forGetter(FundingRecord::shortfall),
            Codec.LONG.fieldOf("market_seconds").forGetter(FundingRecord::marketSeconds))
            .apply(i, FundingRecord::new));

    private static final Codec<TradingState> STATE_CODEC = RecordCodecBuilder.create(i -> i.group(
            MONEY.fieldOf("pool").forGetter(TradingState::poolBalance),
            Codec.LONG.fieldOf("last_trade_id").forGetter(TradingState::lastTradeId),
            OWNED_POSITION_CODEC.listOf().fieldOf("positions").forGetter(state -> flattenPositions(state)),
            TRADE_CODEC.listOf().fieldOf("trades").forGetter(state -> flattenTrades(state)),
            // 委托是 #14 新增的，旧存档没有这两个字段，缺省为没有委托
            Codec.LONG.optionalFieldOf("last_order_id", 0L).forGetter(TradingState::lastOrderId),
            ORDER_CODEC.listOf().optionalFieldOf("orders", List.of()).forGetter(state -> flattenOrders(state)),
            // 保证金模式是 #11 新增的，旧存档没有这个字段，缺省为全部逐仓
            MODE_SETTING_CODEC.listOf().optionalFieldOf("margin_modes", List.of())
                    .forGetter(state -> flattenModes(state)),
            // 资金费记录是 #13 新增的，旧存档没有这两个字段，缺省为没有记录
            Codec.LONG.optionalFieldOf("last_funding_id", 0L).forGetter(TradingState::lastFundingId),
            FUNDING_CODEC.listOf().optionalFieldOf("funding", List.of()).forGetter(state -> flattenFunding(state)),
            // 风险保障基金是 #12 新增的，旧存档没有这个字段，缺省为 0
            MONEY.optionalFieldOf("fund", Money.ZERO).forGetter(TradingState::fundBalance))
            .apply(i, (pool, lastTradeId, positions, trades, lastOrderId, orders, modes, lastFundingId, funding, fund) ->
                    new TradingState(pool, lastTradeId, groupPositions(positions), groupTrades(trades), lastOrderId,
                            groupOrders(orders), groupModes(modes), lastFundingId, groupFunding(funding), fund)));

    public static final Codec<TradingSavedData> CODEC = STATE_CODEC.xmap(TradingSavedData::new, TradingSavedData::state);

    public static final SavedDataType<TradingSavedData> TYPE = new SavedDataType<>(
            CreeperCapital.id("trading"), () -> new TradingSavedData(TradingState.initial()), CODEC);

    private final TradingState loaded;
    private @Nullable TradingBook book;

    private TradingSavedData(TradingState loaded) {
        this.loaded = loaded;
    }

    /** 接上玩家账本，得到活的交易账；之后存档编码的是它的状态。 */
    public TradingBook bind(AccountBook accounts) {
        book = TradingBook.restore(accounts, loaded);
        return book;
    }

    private TradingState state() {
        return book != null ? book.state() : loaded;
    }

    private static List<OwnedPosition> flattenPositions(TradingState state) {
        List<OwnedPosition> flat = new ArrayList<>();
        state.positions().forEach((player, owned) -> owned.forEach(position -> flat.add(new OwnedPosition(player, position))));
        return flat;
    }

    private static List<PlayerTrade> flattenTrades(TradingState state) {
        List<PlayerTrade> flat = new ArrayList<>();
        state.trades().values().forEach(flat::addAll);
        flat.sort(Comparator.comparingLong(PlayerTrade::id));
        return flat;
    }

    private static List<FundingRecord> flattenFunding(TradingState state) {
        List<FundingRecord> flat = new ArrayList<>();
        state.funding().values().forEach(flat::addAll);
        flat.sort(Comparator.comparingLong(FundingRecord::id));
        return flat;
    }

    private static Map<UUID, List<FundingRecord>> groupFunding(List<FundingRecord> flat) {
        Map<UUID, List<FundingRecord>> grouped = new LinkedHashMap<>();
        for (FundingRecord record : flat) {
            grouped.computeIfAbsent(record.player(), p -> new ArrayList<>()).add(record);
        }
        return grouped;
    }

    private static List<ModeSetting> flattenModes(TradingState state) {
        List<ModeSetting> flat = new ArrayList<>();
        state.marginModes().forEach((player, bySymbol) -> bySymbol
                .forEach((symbol, mode) -> flat.add(new ModeSetting(player, symbol, mode))));
        return flat;
    }

    private static Map<UUID, Map<String, MarginMode>> groupModes(List<ModeSetting> flat) {
        Map<UUID, Map<String, MarginMode>> grouped = new HashMap<>();
        for (ModeSetting setting : flat) {
            grouped.computeIfAbsent(setting.player(), p -> new HashMap<>()).put(setting.symbol(), setting.mode());
        }
        return grouped;
    }

    private static List<Order> flattenOrders(TradingState state) {
        List<Order> flat = new ArrayList<>();
        state.orders().values().forEach(flat::addAll);
        flat.sort(Comparator.comparingLong(Order::id));
        return flat;
    }

    private static Map<UUID, List<Order>> groupOrders(List<Order> flat) {
        Map<UUID, List<Order>> grouped = new LinkedHashMap<>();
        for (Order order : flat) {
            grouped.computeIfAbsent(order.player(), p -> new ArrayList<>()).add(order);
        }
        return grouped;
    }

    private static Map<UUID, List<Position>> groupPositions(List<OwnedPosition> flat) {
        Map<UUID, List<Position>> grouped = new HashMap<>();
        for (OwnedPosition owned : flat) {
            grouped.computeIfAbsent(owned.player(), p -> new ArrayList<>()).add(owned.position());
        }
        return grouped;
    }

    private static Map<UUID, List<PlayerTrade>> groupTrades(List<PlayerTrade> flat) {
        Map<UUID, List<PlayerTrade>> grouped = new LinkedHashMap<>();
        for (PlayerTrade trade : flat) {
            grouped.computeIfAbsent(trade.player(), p -> new ArrayList<>()).add(trade);
        }
        return grouped;
    }
}
