package io.github.djp440.creepercapital.core.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarginTiers;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 全服的交易账：玩家的仓位和成交历史，以及与玩家对赌的流动性池。市价委托以流动性池为对手方（ADR-0001），单向持仓，
 * 保证金模式按（玩家，合约）设置，逐仓或全仓（见 {@link MarginMode}）。
 * <p>
 * 资金只在玩家的可用余额（{@link AccountBook}）、挂单冻结、仓位保证金、流动性池和风险保障基金之间流动：开仓把保证金和手续费
 * 从可用余额划出，平仓把保证金加上已实现盈亏、减去手续费划回，盈亏的另一头和手续费都在流动性池。
 * 除开发命令外，这几处的总额不变。
 * <p>
 * 强平在每个行情步由 {@link #checkLiquidations} 检查（#12），所以正常平仓前仓位已被强平线拦住；平仓时逐仓亏损仍封顶为该仓位的保证金，
 * 全仓亏损封顶为保证金加账户里的全部余额，余额不会为负，是跳空来不及强平时的兜底。
 * 流动性池无力付出盈利时（见 #17，ADL）最多付出池子的全部余额。
 * <p>
 * 维持保证金、保证金率和强平价的公式在 {@link MarginMath}，分档在合约的 {@link MarginTiers}，这里不重复。
 */
public final class TradingBook {
    /** taker 手续费率：成交名义价值的 0.05%。 */
    public static final double TAKER_FEE_RATE = 0.0005;

    /** 每位玩家的成交历史最多保留多少笔（最近的）。 */
    public static final int MAX_TRADES_PER_PLAYER = 200;

    /** maker 手续费率：成交名义价值的 0.02%（限价委托被动成交）。 */
    public static final double MAKER_FEE_RATE = 0.0002;

    private static final BigDecimal TAKER_FEE = BigDecimal.valueOf(TAKER_FEE_RATE);
    /** 清算费率：强平时按标记价格算的名义价值的 0.5%（币安按合约分档，这里取一个常量）。清算费从玩家剩余的保证金里收，进风险保障基金。 */
    public static final double LIQUIDATION_FEE_RATE = 0.005;

    private static final BigDecimal MAKER_FEE = BigDecimal.valueOf(MAKER_FEE_RATE);
    private static final BigDecimal LIQUIDATION_FEE = BigDecimal.valueOf(LIQUIDATION_FEE_RATE);

    private final AccountBook accounts;
    private final LiquidityPool pool;
    private final InsuranceFund fund;
    private final OrderBook orders;
    private final Map<UUID, Map<String, Position>> positions = new HashMap<>();
    private final Map<UUID, List<PlayerTrade>> trades = new HashMap<>();
    /** 设为全仓的（玩家，合约）；没有记录的是缺省的逐仓。 */
    private final Map<UUID, Map<String, MarginMode>> modes = new HashMap<>();
    private long lastTradeId;
    private final List<Consumer<PlayerTrade>> tradeListeners = new ArrayList<>();

    public TradingBook(AccountBook accounts, LiquidityPool pool) {
        this(accounts, pool, new InsuranceFund());
    }

    private TradingBook(AccountBook accounts, LiquidityPool pool, InsuranceFund fund) {
        this.accounts = accounts;
        this.pool = pool;
        this.fund = fund;
        this.orders = new OrderBook(this, accounts);
    }

    /** 限价委托：挂单、撤单与每个行情步的触发。 */
    public OrderBook orders() {
        return orders;
    }

    /** 从存档恢复：池子、仓位、成交历史、委托和编号接着用，玩家余额仍在传入的账本里。 */
    public static TradingBook restore(AccountBook accounts, TradingState state) {
        TradingBook book = new TradingBook(accounts, new LiquidityPool(state.poolBalance()),
                new InsuranceFund(state.fundBalance()));
        book.lastTradeId = state.lastTradeId();
        book.orders.restore(state);
        state.positions().forEach((player, owned) -> {
            Map<String, Position> bySymbol = new HashMap<>();
            owned.forEach(position -> bySymbol.put(position.symbol(), position));
            book.positions.put(player, bySymbol);
        });
        state.trades().forEach((player, history) -> book.trades.put(player, new ArrayList<>(history)));
        state.marginModes().forEach((player, bySymbol) -> book.modes.put(player, new HashMap<>(bySymbol)));
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
        Map<UUID, Map<String, MarginMode>> savedModes = new HashMap<>();
        modes.forEach((player, bySymbol) -> {
            if (!bySymbol.isEmpty()) {
                savedModes.put(player, Map.copyOf(bySymbol));
            }
        });
        return new TradingState(pool.balance(), lastTradeId, Map.copyOf(ownedPositions), Map.copyOf(history),
                orders.lastOrderId(), orders.state(), Map.copyOf(savedModes), fund.balance());
    }

    /**
     * 玩家的资产：钱包余额含仓位占用的保证金；保证金余额 = 钱包余额 + 未实现盈亏；可用余额是还能拿来开新仓的钱。
     * <ul>
     * <li>逐仓仓位的未实现盈亏最多亏到它的保证金（和平仓结算一致），不影响可用余额，要平仓实现了才能动用。</li>
     * <li>全仓仓位的未实现盈亏不设下限；全仓浮亏会占用可用余额（可用余额 = 空闲余额 + 全仓浮亏，不低于 0），
     * 全仓浮盈不算可用（比币安保守：浮盈要平仓实现了才能动用，免得凭空造出还没从流动性池收到的钱）。</li>
     * </ul>
     *
     * @param markPrice 某个合约当前的标记价格；返回 NaN（合约已不在配置里）时该仓位按开仓均价算，盈亏为 0
     */
    public Assets assets(UUID player, ToDoubleFunction<String> markPrice) {
        Money free = accounts.balance(player);
        Money margins = Money.ZERO;
        Money unrealized = Money.ZERO;
        Money crossUnrealized = Money.ZERO;
        for (Position position : positions(player)) {
            margins = margins.plus(position.margin());
            double mark = markPrice.applyAsDouble(position.symbol());
            Money pnl = Double.isFinite(mark) ? position.pnlAt(mark) : Money.ZERO;
            if (position.mode() == MarginMode.CROSS) {
                crossUnrealized = crossUnrealized.plus(pnl);
                unrealized = unrealized.plus(pnl);
            } else {
                // 逐仓亏损最多亏到保证金（和平仓结算一致）
                Money floor = Money.ZERO.minus(position.margin());
                unrealized = unrealized.plus(pnl.compareTo(floor) < 0 ? floor : pnl);
            }
        }
        // 挂单冻结的资金仍属于玩家（钱包余额），但已从空闲余额划走，所以不可用
        Money wallet = free.plus(margins).plus(orders.frozen(player));
        Money available = crossUnrealized.compareTo(Money.ZERO) < 0 ? free.plus(crossUnrealized) : free;
        if (!available.isPositive()) {
            available = Money.ZERO;
        }
        return new Assets(wallet, unrealized, wallet.plus(unrealized), available);
    }

    /** 这名玩家在这个合约上设置的保证金模式（缺省逐仓）；有仓位时就是仓位的模式。 */
    public MarginMode marginMode(UUID player, String symbol) {
        return modes.getOrDefault(player, Map.of()).getOrDefault(symbol, MarginMode.ISOLATED);
    }

    /**
     * 这名玩家在这个合约上是否有仓位或委托：有的话不能切换保证金模式。
     * 它是「有没有敞口」的唯一判断点：仓位或挂着的限价委托都算。
     */
    public boolean hasOpenExposure(UUID player, String symbol) {
        return position(player, symbol).isPresent()
                || orders.open(player).stream().anyMatch(order -> order.symbol().equals(symbol));
    }

    /**
     * 切换保证金模式（币安规则：该合约有仓位或委托时不能切换）。设成和现在一样也算生效。
     */
    public MarginResult setMarginMode(UUID player, String symbol, MarginMode mode) {
        if (hasOpenExposure(player, symbol)) {
            return MarginResult.reject(TradeResult.Reason.MARGIN_MODE_LOCKED);
        }
        if (mode == MarginMode.CROSS) {
            modes.computeIfAbsent(player, p -> new HashMap<>()).put(symbol, mode);
        } else {
            Map<String, MarginMode> own = modes.get(player);
            if (own != null) {
                own.remove(symbol);
            }
        }
        return new MarginResult.Applied();
    }

    /**
     * 调整已有仓位的杠杆（币安规则：有仓位时也可以调，受当前名义价值所在档位的杠杆上限和可用保证金约束）。
     * <p>
     * 仓位的保证金按 开仓名义价值 / 新杠杆 重算：调低杠杆要补保证金，从可用余额里扣，不够就拒绝；调高杠杆退回多余的保证金。
     * 逐仓仓位调高杠杆后，保证金率不能达到 100%（否则一调就被强平）。和币安的差别：币安的逐仓仓位有仓位时不能调低杠杆
     * （因为不会自动补保证金），这里自动从可用余额补，所以两个方向都允许。
     *
     * @param markPrice 各合约当前的标记价格（当前名义价值与可用余额按它算）
     */
    public MarginResult adjustLeverage(UUID player, ContractSpec spec, ToDoubleFunction<String> markPrice, int leverage) {
        Position position = position(player, spec.symbol()).orElse(null);
        if (position == null) {
            return MarginResult.reject(TradeResult.Reason.NO_POSITION);
        }
        if (leverage < 1 || leverage > spec.maxLeverage()) {
            return MarginResult.reject(TradeResult.Reason.INVALID_LEVERAGE, String.valueOf(spec.maxLeverage()));
        }
        MarginTiers tiers = spec.marginTiers();
        double mark = markPrice.applyAsDouble(spec.symbol());
        double price = Double.isFinite(mark) && mark > 0 ? mark : position.entryPrice();
        Money currentNotional = notional(price, position.lots(), position.quantityDecimals());
        int tierLimit = tiers.maxLeverageAt(currentNotional);
        if (leverage > tierLimit) {
            return MarginResult.reject(TradeResult.Reason.LEVERAGE_ABOVE_TIER, String.valueOf(tierLimit),
                    currentNotional.toDisplayString());
        }
        if (leverage == position.leverage()) {
            return new MarginResult.Applied();
        }
        Money newMargin = Money.ofUnits(ceilDiv(
                notional(position.entryPrice(), position.lots(), position.quantityDecimals()).units(), leverage));
        Money delta = newMargin.minus(position.margin());
        if (delta.isPositive()) {
            Money available = assets(player, markPrice).availableBalance();
            if (available.compareTo(delta) < 0) {
                return MarginResult.reject(TradeResult.Reason.INSUFFICIENT_BALANCE, delta.toDisplayString(),
                        available.toDisplayString());
            }
        } else if (position.mode() == MarginMode.ISOLATED) {
            // 逐仓退回保证金后，仓位自己的保证金余额要仍然覆盖维持保证金
            Money balance = newMargin.plus(position.pnlAt(price));
            double ratio = MarginMath.marginRatio(tiers.maintenanceMargin(currentNotional), balance);
            if (ratio >= 1.0) {
                return MarginResult.reject(TradeResult.Reason.LEVERAGE_UNSAFE, MarginMath.formatRatio(ratio));
            }
        }
        if (delta.isPositive()) {
            accounts.withdraw(player, delta);
        } else if (delta.units() < 0) {
            accounts.deposit(player, Money.ZERO.minus(delta));
        }
        positions.get(player).put(spec.symbol(), position.withLeverage(leverage, newMargin));
        return new MarginResult.Applied();
    }

    public LiquidityPool pool() {
        return pool;
    }

    /** 风险保障基金：强平剩余的保证金和清算费流入，穿仓时先由它垫付。 */
    public InsuranceFund fund() {
        return fund;
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
        // 只知道这一个合约的行情：其他合约的全仓仓位按开仓均价算（盈亏为 0）
        return open(player, spec, snapshot, side, lots, leverage,
                symbol -> symbol.equals(snapshot.symbol()) ? snapshot.markPrice() : Double.NaN);
    }

    /**
     * 市价开仓，可用余额按全部合约的标记价格算（全仓浮亏会占用可用余额）。
     * 仓位的保证金模式是玩家在这个合约上设置的（已有仓位时是仓位的）；开仓后仓位的名义价值不能超过该杠杆下的档位上限。
     *
     * @param markPrice 各合约当前的标记价格
     */
    public TradeResult open(UUID player, ContractSpec spec, MarketSnapshot snapshot, Side side, long lots, int leverage,
            ToDoubleFunction<String> markPrice) {
        TradeResult invalid = validateOpen(player, spec, side, lots, leverage);
        if (invalid != null) {
            return invalid;
        }
        double price = fillPrice(spec, snapshot, side, lots);
        if (!(price > 0)) {
            return TradeResult.reject(TradeResult.Reason.INVALID_PRICE);
        }
        return openAt(player, spec, snapshot.time().totalSeconds(), side, lots, leverage, price, TAKER_FEE, markPrice);
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

    /**
     * 以给定的成交价和手续费率开仓（调用方已通过 {@link #validateOpen}）：市价是 taker 费率，限价被动成交是 maker 费率。
     * 限价委托成交走这个重载，拿不到各合约的标记价格，所以其他合约的全仓浮亏按 0 算（可用余额偏乐观，见 pending-cross-checks）。
     */
    TradeResult openAt(UUID player, ContractSpec spec, long marketSeconds, Side side, long lots, int leverage,
            double price, BigDecimal feeRate) {
        return openAt(player, spec, marketSeconds, side, lots, leverage, price, feeRate, symbol -> Double.NaN);
    }

    /** 同 {@link #openAt}，可用余额按传入的各合约标记价格算（全仓浮亏占用可用余额）。 */
    TradeResult openAt(UUID player, ContractSpec spec, long marketSeconds, Side side, long lots, int leverage,
            double price, BigDecimal feeRate, ToDoubleFunction<String> markPrice) {
        Position existing = position(player, spec.symbol()).orElse(null);
        Money margin;
        Money fee;
        Money required;
        Position updated;
        try {
            Money notional = notional(price, lots, spec.quantityDecimals());
            Money total = existing == null ? notional
                    : notional(price, Math.addExact(existing.lots(), lots), spec.quantityDecimals());
            Money limit = spec.marginTiers().maxNotionalAt(leverage);
            if (total.compareTo(limit) > 0) {
                return TradeResult.reject(TradeResult.Reason.POSITION_LIMIT, limit.toDisplayString());
            }
            margin = Money.ofUnits(ceilDiv(notional.units(), leverage));
            fee = fee(notional, feeRate);
            required = margin.plus(fee);
            // 所有可能溢出的运算都在动钱之前做完，溢出时整笔不成交、账不变
            updated = existing == null
                    ? new Position(spec.symbol(), side, lots, price, leverage, margin, spec.quantityDecimals(),
                            marginMode(player, spec.symbol()))
                    : addTo(existing, lots, price, margin);
        } catch (ArithmeticException e) {
            return TradeResult.reject(TradeResult.Reason.INVALID_QUANTITY);
        }
        Money available = assets(player, markPrice).availableBalance();
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
        return closeAt(player, position, spec, snapshot.time().totalSeconds(), lots, price, TAKER_FEE);
    }

    /**
     * 市价只减仓平仓：数量超过仓位时缩减到仓位，没有仓位拒绝（{@link TradeResult.Reason#NO_POSITION}）。
     * 和 {@link #close} 的差别只在于数量超过仓位不报错。
     */
    public TradeResult closeUpTo(UUID player, ContractSpec spec, MarketSnapshot snapshot, long lots) {
        Position position = position(player, spec.symbol()).orElse(null);
        if (position == null) {
            return TradeResult.reject(TradeResult.Reason.NO_POSITION);
        }
        return close(player, spec, snapshot, Math.min(lots, position.lots()));
    }

    /**
     * 以给定成交价和手续费率结算一次平仓（调用方已校验数量与价格）：市价是 taker 费率，限价平仓被动成交是 maker 费率。
     * 仓位被平光时，只减仓 / 止盈止损委托随之失效。
     */
    TradeResult closeAt(UUID player, Position position, ContractSpec spec, long marketSeconds, long lots, double price,
            BigDecimal feeRate) {
        Money releasedMargin = lots == position.lots() ? position.margin()
                : Money.ofUnits(BigDecimal.valueOf(position.margin().units()).multiply(BigDecimal.valueOf(lots))
                        .divide(BigDecimal.valueOf(position.lots()), 0, RoundingMode.FLOOR).longValueExact());
        Money pnl = Position.pnl(position.side(), position.entryPrice(), price, lots, spec.quantityDecimals());
        Money tradeFee = fee(notional(price, lots, spec.quantityDecimals()), feeRate);
        // 逐仓：亏损最多亏到这部分仓位的保证金；全仓：亏损最多亏到保证金加账户里的空闲余额（没有强平前余额不会为负）。
        // 盈利由池子付，池子不够就付出它的全部余额
        boolean cross = position.mode() == MarginMode.CROSS;
        Money free = cross ? accounts.balance(player) : Money.ZERO;
        Money realized;
        if (pnl.isPositive()) {
            realized = pool.payUpTo(pnl);
        } else {
            Money loss = Money.ZERO.minus(releasedMargin.plus(free));
            realized = pnl.compareTo(loss) < 0 ? loss : pnl;
            pool.receive(Money.ZERO.minus(realized));
        }
        Money gross = releasedMargin.plus(realized);
        // 手续费从还剩的钱里收，不够就收剩下的
        Money cushion = gross.plus(free);
        Money fee = tradeFee.compareTo(cushion) > 0 ? cushion : tradeFee;
        pool.receive(fee);
        Money payout = gross.minus(fee);
        if (payout.isPositive()) {
            accounts.deposit(player, payout);
        } else if (payout.units() < 0) {
            accounts.withdraw(player, Money.ZERO.minus(payout));
        }

        Map<String, Position> own = positions.get(player);
        if (lots == position.lots()) {
            own.remove(spec.symbol());
            orders.onPositionClosed(player, spec.symbol(), marketSeconds);
        } else {
            own.put(spec.symbol(), new Position(position.symbol(), position.side(), position.lots() - lots,
                    position.entryPrice(), position.leverage(), position.margin().minus(releasedMargin),
                    position.quantityDecimals(), position.mode()));
        }
        return record(new PlayerTrade(0, player, spec.symbol(), position.side(), TradeKind.CLOSE, price, lots, fee,
                realized, marketSeconds));
    }

    // ---- 强平（#12） ----

    /** 强平结算结果：见 {@link LiquidationEvent} 里同名字段。 */
    private record Settlement(Money toFund, Money shortfall, Money fundCovered, Money poolBorne) {
    }

    /**
     * 强平检查：每个行情步、在委托触发和资金费结算之后调用一次。遍历账本里的<b>全部</b>仓位（离线玩家同样会被强平），
     * 用 {@link MarginMath#assess} 算保证金率（仓位页展示的强平价和这里触发强平的是同一套公式），达到 100% 就强平：
     * <ul>
     * <li>逐仓：只看这个仓位自己，强平它；该合约的挂单一并撤销（冻结退回可用余额）。玩家抵押的是这个仓位的保证金。</li>
     * <li>全仓：所有全仓仓位共用一个保证金率，一起强平；玩家全部挂单撤销（冻结本来就计入全仓钱包），
     * 抵押是整个全仓钱包（全仓仓位的保证金 + 全部可用余额），强平后可用余额归零。</li>
     * </ul>
     * 强平按标记价格成交（不走流动性曲线，也没有滑点）。结算：抵押先抵亏损（亏损归池子，池子是对手方），剩下的（含清算费）全进风险保障基金；
     * 亏损超过抵押即穿仓，超出部分先由基金垫付给池子，基金不够池子就少收，玩家余额不会为负。盈利（分档变化导致盈利仓位也到强平线的极端情形）
     * 由池子付，付不出就付全部余额，等 #17 的自动减仓接管。
     *
     * @param markPrice     各合约当前的标记价格；返回 NaN 或非正数（合约已不在配置里）的仓位不参与检查
     * @param tiers         各合约的维持保证金分档
     * @param marketSeconds 此刻的市场时间（市场秒），记入成交与事件
     * @return 这一步发生的强平，按玩家 UUID、再按结算先后排列；没有强平时为空
     */
    public List<LiquidationEvent> checkLiquidations(ToDoubleFunction<String> markPrice,
            Function<String, MarginTiers> tiers, long marketSeconds) {
        return checkLiquidations(player -> true, markPrice, tiers, marketSeconds);
    }

    /** 同上，但只检查 {@code players} 允许的玩家（GameTest 共用一个活账本，借此把注入的极端价格限定在测试自己的玩家上）。 */
    public List<LiquidationEvent> checkLiquidations(Predicate<UUID> players0, ToDoubleFunction<String> markPrice,
            Function<String, MarginTiers> tiers, long marketSeconds) {
        List<LiquidationEvent> events = new ArrayList<>();
        List<UUID> players = new ArrayList<>(positions.keySet());
        players.removeIf(player -> !players0.test(player));
        players.sort(Comparator.naturalOrder());
        for (UUID player : players) {
            List<Position> priced = positions(player).stream().filter(p -> hasMark(markPrice, p.symbol()))
                    .sorted(Comparator.comparing(Position::symbol)).toList();
            if (priced.isEmpty()) {
                continue;
            }
            Map<String, MarginMath.PositionRisk> risks = MarginMath.assess(priced,
                    assets(player, markPrice).walletBalance(), markPrice, tiers);
            List<Position> cross = new ArrayList<>();
            for (Position position : priced) {
                if (risks.get(position.symbol()).marginRatio() < 1.0) {
                    continue;
                }
                if (position.mode() == MarginMode.CROSS) {
                    cross.add(position);
                } else {
                    events.add(liquidate(player, MarginMode.ISOLATED, List.of(position), markPrice, marketSeconds));
                }
            }
            if (!cross.isEmpty()) {
                events.add(liquidate(player, MarginMode.CROSS, cross, markPrice, marketSeconds));
            }
        }
        return events;
    }

    private static boolean hasMark(ToDoubleFunction<String> markPrice, String symbol) {
        double mark = markPrice.applyAsDouble(symbol);
        return Double.isFinite(mark) && mark > 0;
    }

    private LiquidationEvent liquidate(UUID player, MarginMode mode, List<Position> closing,
            ToDoubleFunction<String> markPrice, long marketSeconds) {
        // 先撤挂单：冻结的资金退回可用余额。全仓钱包本来就含冻结，所以全仓撤全部，逐仓只撤这个合约的
        Predicate<String> scope = mode == MarginMode.CROSS ? symbol -> true : closing.get(0).symbol()::equals;
        List<OrderEvent> cancelled = orders.cancelWhere(player, scope, CancelReason.LIQUIDATED, marketSeconds);

        Money collateral = Money.ZERO;
        Money pnl = Money.ZERO;
        for (Position position : closing) {
            collateral = collateral.plus(position.margin());
            pnl = pnl.plus(position.pnlAt(markPrice.applyAsDouble(position.symbol())));
        }
        if (mode == MarginMode.CROSS) {
            // 全仓抵押的是整个全仓钱包：可用余额也押上，强平后归零
            Money free = accounts.balance(player);
            if (free.isPositive()) {
                accounts.withdraw(player, free);
                collateral = collateral.plus(free);
            }
        }
        Settlement settlement = settle(collateral, pnl);

        // 清算费从进基金的金额里划出一部分单独记在成交上，不超过剩余；总额不变
        Money feeRoom = settlement.toFund();
        List<LiquidationEvent.Liquidated> liquidated = new ArrayList<>();
        for (Position position : closing) {
            double mark = markPrice.applyAsDouble(position.symbol());
            Money target = fee(notional(mark, position.lots(), position.quantityDecimals()), LIQUIDATION_FEE);
            Money fee = target.compareTo(feeRoom) > 0 ? feeRoom : target;
            feeRoom = feeRoom.minus(fee);
            positions.get(player).remove(position.symbol());
            // 附带的止盈止损 / 只减仓委托跟着仓位失效
            orders.onPositionClosed(player, position.symbol(), marketSeconds);
            PlayerTrade trade = ((TradeResult.Filled) record(new PlayerTrade(0, player, position.symbol(),
                    position.side(), TradeKind.LIQUIDATION, mark, position.lots(), fee, position.pnlAt(mark),
                    marketSeconds))).trade();
            liquidated.add(new LiquidationEvent.Liquidated(position.symbol(), position.side(), position.lots(), mark,
                    trade.realizedPnl(), fee, trade.id()));
        }
        return new LiquidationEvent(player, mode, List.copyOf(liquidated), collateral, settlement.toFund(),
                settlement.shortfall(), settlement.fundCovered(), settlement.poolBorne(), List.copyOf(cancelled),
                marketSeconds);
    }

    /**
     * 强平的资金结算：{@code collateral} 是玩家押上的钱（已不在玩家账上），{@code pnl} 是按标记价格平仓的总盈亏。
     * 玩家的钱只会去池子（亏损）或基金（剩余），永远不会为负。
     */
    private Settlement settle(Money collateral, Money pnl) {
        if (pnl.units() >= 0) {
            // 盈利（只在分档让维持保证金超过保证金时才会发生）：池子付，付不出就付全部余额；玩家的抵押加盈利全部进基金
            Money equity = collateral.plus(pool.payUpTo(pnl));
            fund.receive(equity);
            return new Settlement(equity, Money.ZERO, Money.ZERO, Money.ZERO);
        }
        Money loss = Money.ZERO.minus(pnl);
        if (collateral.compareTo(loss) >= 0) {
            Money equity = collateral.minus(loss);
            pool.receive(loss);
            fund.receive(equity);
            return new Settlement(equity, Money.ZERO, Money.ZERO, Money.ZERO);
        }
        // 穿仓：池子是对手方，本该赚 loss；玩家抵押只够付 collateral，缺口先由基金垫付，基金不够池子就少收
        Money shortfall = loss.minus(collateral);
        Money covered = fund.payUpTo(shortfall);
        pool.receive(collateral.plus(covered));
        return new Settlement(Money.ZERO, shortfall, covered, shortfall.minus(covered));
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
        tradeListeners.forEach(listener -> listener.accept(numbered));
        return new TradeResult.Filled(numbered);
    }

    /** 订阅玩家成交：成交记录的源头每记下一笔就通知一次（全服「最新成交」广播挂在这里，开仓、平仓、以后的强平与限价成交都会经过）。 */
    public void addTradeListener(Consumer<PlayerTrade> listener) {
        tradeListeners.add(listener);
    }

    private static Position addTo(Position existing, long lots, double price, Money margin) {
        long total = Math.addExact(existing.lots(), lots);
        BigDecimal cost = BigDecimal.valueOf(existing.entryPrice()).multiply(BigDecimal.valueOf(existing.lots()))
                .add(BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(lots)));
        double average = cost.divide(BigDecimal.valueOf(total), 12, RoundingMode.HALF_UP).doubleValue();
        return new Position(existing.symbol(), existing.side(), total, average, existing.leverage(),
                existing.margin().plus(margin), existing.quantityDecimals(), existing.mode());
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
    public static Money notional(double price, long lots, int quantityDecimals) {
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
