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
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.core.trading.TradingState;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 世界存档里的交易账：流动性池余额、玩家的仓位与成交历史，存为 {@code data/creepercapital/trading.dat}。
 * 金额以最小单位的整数存储；玩家余额不在这里，在 {@link AccountSavedData}。
 * <p>
 * 读出来只是状态，服务器启动时用 {@link #bind} 接上玩家账本变成活的 {@link TradingBook}，保存时再取它的状态编码。
 */
public final class TradingSavedData extends SavedData {
    private static final Codec<Money> MONEY = Codec.LONG.xmap(Money::ofUnits, Money::units);
    private static final Codec<Side> SIDE = Codec.STRING.xmap(Side::valueOf, Side::name);
    private static final Codec<TradeKind> KIND = Codec.STRING.xmap(TradeKind::valueOf, TradeKind::name);

    private record OwnedPosition(UUID player, Position position) {
    }

    private static final Codec<Position> POSITION_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("symbol").forGetter(Position::symbol),
            SIDE.fieldOf("side").forGetter(Position::side),
            Codec.LONG.fieldOf("lots").forGetter(Position::lots),
            Codec.DOUBLE.fieldOf("entry_price").forGetter(Position::entryPrice),
            Codec.INT.fieldOf("leverage").forGetter(Position::leverage),
            MONEY.fieldOf("margin").forGetter(Position::margin),
            Codec.INT.fieldOf("quantity_decimals").forGetter(Position::quantityDecimals))
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

    private static final Codec<TradingState> STATE_CODEC = RecordCodecBuilder.create(i -> i.group(
            MONEY.fieldOf("pool").forGetter(TradingState::poolBalance),
            Codec.LONG.fieldOf("last_trade_id").forGetter(TradingState::lastTradeId),
            OWNED_POSITION_CODEC.listOf().fieldOf("positions").forGetter(state -> flattenPositions(state)),
            TRADE_CODEC.listOf().fieldOf("trades").forGetter(state -> flattenTrades(state)))
            .apply(i, (pool, lastTradeId, positions, trades) -> new TradingState(pool, lastTradeId,
                    groupPositions(positions), groupTrades(trades))));

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
