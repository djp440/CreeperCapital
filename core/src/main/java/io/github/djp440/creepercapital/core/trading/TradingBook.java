package io.github.djp440.creepercapital.core.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 全服的交易账：玩家的仓位和成交历史，以及与玩家对赌的流动性池。市价委托以流动性池为对手方（ADR-0001），逐仓、单向持仓。
 * <p>
 * 资金只在玩家的可用余额（{@link AccountBook}）、仓位保证金和流动性池之间流动：开仓把保证金和手续费从可用余额划出，
 * 平仓把保证金加上已实现盈亏、减去手续费划回，盈亏的另一头和手续费都在流动性池。除开发命令外，三者的总额不变。
 * <p>
 * 尚未做强平（见 #11），所以平仓时亏损最多亏到该仓位的保证金（逐仓的含义）；流动性池无力付出盈利时（见 #16，ADL）
 * 最多付出池子的全部余额。
 */
public final class TradingBook {
    /** taker 手续费率：成交名义价值的 0.05%。 */
    public static final double TAKER_FEE_RATE = 0.0005;

    /** 每位玩家的成交历史最多保留多少笔（最近的）。 */
    public static final int MAX_TRADES_PER_PLAYER = 200;

    /** maker 手续费率：成交名义价值的 0.02%（限价委托被动成交）。 */
    public static final double MAKER_FEE_RATE = 0.0002;

    private static final BigDecimal TAKER_FEE = BigDecimal.valueOf(TAKER_FEE_RATE);
    private static final BigDecimal MAKER_FEE = BigDecimal.valueOf(MAKER_FEE_RATE);

    private final AccountBook accounts;
    private final LiquidityPool pool;
    private final OrderBook orders;
    private final Map<UUID, Map<String, Position>> positions = new HashMap<>();
    private final Map<UUID, List<PlayerTrade>> trades = new HashMap<>();
    private long lastTradeId;

    public TradingBook(AccountBook accounts, LiquidityPool pool) {
        this.accounts = accounts;
        this.pool = pool;
        this.orders = new OrderBook(this, accounts);
    }

    /** 限价委托：挂单、撤单与每个行情步的触发。 */
    public OrderBook orders() {
        return orders;
    }

    /** 从存档恢复：池子、仓位、成交历史、委托和编号接着用，玩家余额仍在传入的账本里。 */
    public static TradingBook restore(AccountBook accounts, TradingState state) {
        TradingBook book = new TradingBook(accounts, new LiquidityPool(state.poolBalance()));
        book.lastTradeId = state.lastTradeId();
        book.orders.restore(state);
        state.positions().forEach((player, owned) -> {
            Map<String, Position> bySymbol = new HashMap<>();
            owned.forEach(position -> bySymbol.put(position.symbol(), position));
            book.positions.put(player, bySymbol);
        });
        state.trades().forEach((player, history) -> book.trades.put(player, new ArrayList<>(history)));
        return book;
    }

    /** 此刻要存档的状态（不可变副本）。 */
    public TradingState state() {
        Map<UUID, List<Position>> ownedPositions = new HashMap<>();
        positions.forEach((player, owned) -> {
            if (!owned.isEmpty()) {
                ownedPositions.put(player, List.copyOf(owned.values()));
            }
        });
        Map<UUID, List<PlayerTrade>> history = new HashMap<>();
        trades.forEach((player, list) -> history.put(player, List.copyOf(list)));
        return new TradingState(pool.balance(), lastTradeId, Map.copyOf(ownedPositions), Map.copyOf(history),
                orders.lastOrderId(), orders.state());
    }

    /**
     * 玩家的资产：钱包余额含仓位占用的保证金；保证金余额 = 钱包余额 + 未实现盈亏；可用余额是还没占用的余额
     * （逐仓的未实现盈亏不计入，要平仓实现了才能动用）。
     *
     * @param markPrice 某个合约当前的标记价格；返回 NaN（合约已不在配置里）时该仓位按开仓均价算，盈亏为 0
     */
    public Assets assets(UUID player, ToDoubleFunction<String> markPrice) {
        Money free = accounts.balance(player);
        Money margins = Money.ZERO;
        Money unrealized = Money.ZERO;
        for (Position position : positions(player)) {
            margins = margins.plus(position.margin());
            double mark = markPrice.applyAsDouble(position.symbol());
            // 逐仓亏损最多亏到保证金（和平仓结算一致）；没有强平前，浮亏不会让保证金余额低于可用余额
            Money floor = Money.ZERO.minus(position.margin());
            Money pnl = Double.isFinite(mark) ? position.pnlAt(mark) : Money.ZERO;
            unrealized = unrealized.plus(pnl.compareTo(floor) < 0 ? floor : pnl);
        }
        // 挂单冻结的资金仍属于玩家（钱包余额），只是不可用
        Money wallet = free.plus(margins).plus(orders.frozen(player));
        return new Assets(wallet, unrealized, wallet.plus(unrealized), free);
    }

    public LiquidityPool pool() {
        return pool;
    }

    public Optional<Position> position(UUID player, String symbol) {
        return Optional.ofNullable(positions.getOrDefault(player, Map.of()).get(symbol));
    }

    /** 这名玩家的全部仓位。 */
    public List<Position> positions(UUID player) {
        return List.copyOf(positions.getOrDefault(player, Map.of()).values());
    }

    /** 这名玩家最近的成交，按成交先后排列（最旧的在前）。 */
    public List<PlayerTrade> trades(UUID player) {
        return List.copyOf(trades.getOrDefault(player, List.of()));
    }

    /**
     * 市价开仓：已有同向仓位时加仓（开仓均价加权，保证金累加，杠杆沿用仓位的）。
     *
     * @param lots     数量，以最小数量单位（手）计
     * @param leverage 杠杆倍数
     */
    public TradeResult open(UUID player, ContractSpec spec, MarketSnapshot snapshot, Side side, long lots, int leverage) {
        TradeResult invalid = validateOpen(player, spec, side, lots, leverage);
        if (invalid != null) {
            return invalid;
        }
        double price = fillPrice(spec, snapshot, side, lots);
        if (!(price > 0)) {
            return TradeResult.reject(TradeResult.Reason.INVALID_PRICE);
        }
        return openAt(player, spec, snapshot.time().totalSeconds(), side, lots, leverage, price, TAKER_FEE);
    }

    /** 开仓的前置校验（数量、杠杆、与已有仓位的方向和杠杆）；通过返回 null。市价开仓与限价委托共用。 */
    TradeResult validateOpen(UUID player, ContractSpec spec, Side side, long lots, int leverage) {
        if (lots <= 0) {
            return TradeResult.reject(TradeResult.Reason.INVALID_QUANTITY);
        }
        if (leverage < 1 || leverage > spec.maxLeverage()) {
            return TradeResult.reject(TradeResult.Reason.INVALID_LEVERAGE, String.valueOf(spec.maxLeverage()));
        }
        Position existing = position(player, spec.symbol()).orElse(null);
        if (existing != null && existing.side() != side) {
            return TradeResult.reject(TradeResult.Reason.OPPOSITE_POSITION);
        }
        if (existing != null && existing.leverage() != leverage) {
            return TradeResult.reject(TradeResult.Reason.LEVERAGE_MISMATCH, String.valueOf(existing.leverage()));
        }
        return null;
    }

    /** 以给定的成交价和手续费率开仓（调用方已通过 {@link #validateOpen}）：市价是 taker 费率，限价被动成交是 maker 费率。 */
    TradeResult openAt(UUID player, ContractSpec spec, long marketSeconds, Side side, long lots, int leverage,
            double price, BigDecimal feeRate) {
        Position existing = position(player, spec.symbol()).orElse(null);
        Money margin;
        Money fee;
        Money required;
        Position updated;
        try {
            Money notional = notional(price, lots, spec.quantityDecimals());
            margin = Money.ofUnits(ceilDiv(notional.units(), leverage));
            fee = fee(notional, feeRate);
            required = margin.plus(fee);
            // 所有可能溢出的运算都在动钱之前做完，溢出时整笔不成交、账不变
            updated = existing == null
                    ? new Position(spec.symbol(), side, lots, price, leverage, margin, spec.quantityDecimals())
                    : addTo(existing, lots, price, margin);
        } catch (ArithmeticException e) {
            return TradeResult.reject(TradeResult.Reason.INVALID_QUANTITY);
        }
        Money available = accounts.balance(player);
        if (available.compareTo(required) < 0) {
            return TradeResult.reject(TradeResult.Reason.INSUFFICIENT_BALANCE, required.toDisplayString(),
                    available.toDisplayString());
        }

        if (required.isPositive()) {
            accounts.withdraw(player, required);
        }
        if (fee.isPositive()) {
            pool.receive(fee);
        }
        positions.computeIfAbsent(player, p -> new HashMap<>()).put(spec.symbol(), updated);
        return record(new PlayerTrade(0, player, spec.symbol(), side, TradeKind.OPEN, price, lots, fee, Money.ZERO,
                marketSeconds));
    }

    /** 限价委托挂单时要冻结的资金：保证金 + 按 maker 费率预估的手续费（委托价成交时正好是这个数）。 */
    static Money frozenFor(ContractSpec spec, double price, long lots, int leverage) {
        Money notional = notional(price, lots, spec.quantityDecimals());
        return Money.ofUnits(ceilDiv(notional.units(), leverage)).plus(fee(notional, MAKER_FEE));
    }

    /**
     * 市价平仓（可以只平一部分）：卖出（平多）或买入（平空）成交，盈亏与手续费在玩家和流动性池之间结清。
     *
     * @param lots 平仓数量，以最小数量单位（手）计，不超过仓位
     */
    public TradeResult close(UUID player, ContractSpec spec, MarketSnapshot snapshot, long lots) {
        Position position = position(player, spec.symbol()).orElse(null);
        if (position == null) {
            return TradeResult.reject(TradeResult.Reason.NO_POSITION);
        }
        if (lots <= 0) {
            return TradeResult.reject(TradeResult.Reason.INVALID_QUANTITY);
        }
        if (lots > position.lots()) {
            return TradeResult.reject(TradeResult.Reason.CLOSE_EXCEEDS_POSITION, String.valueOf(position.lots()));
        }
        double price = fillPrice(spec, snapshot, position.side(), TradeKind.CLOSE, lots);
        if (!(price > 0)) {
            return TradeResult.reject(TradeResult.Reason.INVALID_PRICE);
        }
        Money releasedMargin = lots == position.lots() ? position.margin()
                : Money.ofUnits(BigDecimal.valueOf(position.margin().units()).multiply(BigDecimal.valueOf(lots))
                        .divide(BigDecimal.valueOf(position.lots()), 0, RoundingMode.FLOOR).longValueExact());
        Money pnl = Position.pnl(position.side(), position.entryPrice(), price, lots, spec.quantityDecimals());
        Money tradeFee = fee(notional(price, lots, spec.quantityDecimals()));
        // 逐仓：亏损最多亏到这部分仓位的保证金；盈利由池子付，池子不够就付出它的全部余额
        Money realized;
        if (pnl.isPositive()) {
            realized = pool.payUpTo(pnl);
        } else {
            realized = pnl.compareTo(Money.ZERO.minus(releasedMargin)) < 0 ? Money.ZERO.minus(releasedMargin) : pnl;
            pool.receive(Money.ZERO.minus(realized));
        }
        Money gross = releasedMargin.plus(realized);
        Money fee = tradeFee;
        if (fee.compareTo(gross) > 0) {
            fee = gross;
        }
        pool.receive(fee);
        Money payout = gross.minus(fee);
        if (payout.isPositive()) {
            accounts.deposit(player, payout);
        }

        Map<String, Position> own = positions.get(player);
        if (lots == position.lots()) {
            own.remove(spec.symbol());
        } else {
            own.put(spec.symbol(), new Position(position.symbol(), position.side(), position.lots() - lots,
                    position.entryPrice(), position.leverage(), position.margin().minus(releasedMargin),
                    position.quantityDecimals()));
        }
        return record(new PlayerTrade(0, player, spec.symbol(), position.side(), TradeKind.CLOSE, price, lots, fee,
                realized, snapshot.time().totalSeconds()));
    }

    /** 给成交编号并记入玩家的成交历史。 */
    private TradeResult record(PlayerTrade trade) {
        PlayerTrade numbered = new PlayerTrade(++lastTradeId, trade.player(), trade.symbol(), trade.side(), trade.kind(),
                trade.price(), trade.lots(), trade.fee(), trade.realizedPnl(), trade.marketSeconds());
        List<PlayerTrade> history = trades.computeIfAbsent(trade.player(), p -> new ArrayList<>());
        history.add(numbered);
        if (history.size() > MAX_TRADES_PER_PLAYER) {
            history.remove(0);
        }
        return new TradeResult.Filled(numbered);
    }

    private static Position addTo(Position existing, long lots, double price, Money margin) {
        long total = Math.addExact(existing.lots(), lots);
        BigDecimal cost = BigDecimal.valueOf(existing.entryPrice()).multiply(BigDecimal.valueOf(existing.lots()))
                .add(BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(lots)));
        double average = cost.divide(BigDecimal.valueOf(total), 12, RoundingMode.HALF_UP).doubleValue();
        return new Position(existing.symbol(), existing.side(), total, average, existing.leverage(),
                existing.margin().plus(margin), existing.quantityDecimals());
    }

    /**
     * 市价成交价：<b>全模组唯一的成交价入口</b>。成交价是 {@code lots} 手沿虚拟流动性曲线（{@link io.github.djp440.creepercapital.core.market.LiquidityCurve}）
     * 逐档吃单的均价：买入（开多、平空）从卖一价（最新价格 + 半个点差）起越吃越贵，卖出（开空、平多）从买一价起越吃越便宜；
     * 这是临时冲击，不改变最新价格，其他玩家看到的价格不受影响。
     * <p>
     * 市价开平仓用它；限价委托中按市价成交（taker）的部分、止盈止损触发后的市价成交也必须调用它，不得另算成交价，
     * 否则那部分订单会绕过滑点。
     *
     * @return 成交均价；数量大到算不出有限的正价格时返回 NaN，调用方按 {@link TradeResult.Reason#INVALID_PRICE} 拒单
     */
    static double fillPrice(ContractSpec spec, MarketSnapshot snapshot, Side side, TradeKind kind, long lots) {
        boolean buy = (side == Side.LONG) == (kind == TradeKind.OPEN);
        double price = spec.liquidityCurve(snapshot.lastPrice()).averageFillPrice(buy, lots);
        return Double.isFinite(price) && price > 0 ? price : Double.NaN;
    }

    /** 开仓方向的市价成交价（限价委托判断是否穿越、市价开仓共用）。 */
    static double fillPrice(ContractSpec spec, MarketSnapshot snapshot, Side side, long lots) {
        return fillPrice(spec, snapshot, side, TradeKind.OPEN, lots);
    }

    /** 名义价值 = 价格 × 数量，取整到最小金额单位。 */
    static Money notional(double price, long lots, int quantityDecimals) {
        BigDecimal value = BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(lots)).movePointLeft(quantityDecimals);
        return Money.ofUnits(value.movePointRight(Money.SCALE).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    /** taker 手续费 = 名义价值 × 0.05%，四舍五入到最小金额单位。 */
    static Money fee(Money notional) {
        return fee(notional, TAKER_FEE);
    }

    static Money fee(Money notional, BigDecimal rate) {
        return Money.ofUnits(BigDecimal.valueOf(notional.units()).multiply(rate).setScale(0, RoundingMode.HALF_UP)
                .longValueExact());
    }

    private static long ceilDiv(long dividend, long divisor) {
        return Math.floorDiv(dividend + divisor - 1, divisor);
    }
}
