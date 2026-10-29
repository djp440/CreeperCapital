package io.github.djp440.creepercapital.core.trading;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 全服的委托：限价单、只减仓限价单、止盈止损的挂单、撤单，以及每个行情步按新价格触发成交。由 {@link TradingBook#orders()} 持有。
 * <p>
 * 价格口径：买单在盘口第一档（卖一价 = 最新价 + 半个点差）不高于委托价时穿越，卖单在第一档（买一价 = 最新价 − 半个点差）
 * 不低于委托价时穿越，只看最优价、不看整单吃单的均价；穿越（含一步跳过委托价的跳空）一律按委托价成交，收 maker 费。
 * 下单时就能成交的委托收 taker 费，成交价是同数量市价单的曲线均价（和市价单走同一个成交价入口，不在这里另算），
 * 但不比委托价差：均价越过委托价时封顶在委托价。
 * <p>
 * 资金：挂单时把保证金与预估手续费（{@link TradingBook#frozenFor}）从可用余额划进委托的 {@link Order#frozen()}，
 * 守恒恒等式变为 可用余额 + 冻结 + 仓位保证金 + 池子 不变。撤单把冻结原数划回；成交时先划回再按委托价开仓，
 * 开仓所需正好等于冻结。其间开仓条件变了（反向仓位、杠杆不一致）则撤单（{@link CancelReason#FILL_REJECTED}）。
 * <p>
 * 平仓类委托（只减仓限价单、止盈、止损，#15）不冻结资金，只占用仓位的数量：数量超过仓位时成交按仓位缩减，仓位没了（全平、
 * 触发、强平）就自动失效（{@link CancelReason#POSITION_CLOSED}）。止盈止损用最新价格或标记价格判断触发（一步内跳空越过即触发），
 * 触发后按市价平仓：成交价走 {@link TradingBook#fillPrice}（和市价单同一入口，含滑点），收 taker 费。
 * 限价开仓可以附带止盈止损，成交成仓位时才生成对应的止盈止损委托（数量 = 开仓数量）。
 * <p>
 * 有效期只有 GTC。
 */
public final class OrderBook {
    /** 每名玩家最多同时挂多少张委托。 */
    public static final int MAX_OPEN_ORDERS_PER_PLAYER = 20;

    /** 每名玩家的历史委托最多保留多少条（最近的）。 */
    public static final int MAX_HISTORY_PER_PLAYER = 100;

    private static final BigDecimal MAKER_FEE = BigDecimal.valueOf(TradingBook.MAKER_FEE_RATE);
    private static final BigDecimal TAKER_FEE = BigDecimal.valueOf(TradingBook.TAKER_FEE_RATE);

    private final TradingBook book;
    private final AccountBook accounts;
    /** 挂着的委托，按编号排序，保证同一步里先下的先成交。 */
    private final TreeMap<Long, Order> open = new TreeMap<>();
    private final Map<UUID, List<Order>> history = new HashMap<>();
    /** 非行情步触发的失效事件（例如玩家市价平掉仓位后，附带的止盈止损随之失效），等下一次 {@link #takeEvents} 或行情步取走。 */
    private final List<OrderEvent> deferred = new ArrayList<>();
    private long lastOrderId;

    OrderBook(TradingBook book, AccountBook accounts) {
        this.book = book;
        this.accounts = accounts;
    }

    void restore(TradingState state) {
        lastOrderId = state.lastOrderId();
        state.orders().forEach((player, owned) -> {
            for (Order order : owned) {
                if (order.isOpen()) {
                    open.put(order.id(), order);
                } else {
                    history.computeIfAbsent(player, p -> new ArrayList<>()).add(order);
                }
            }
        });
    }

    long lastOrderId() {
        return lastOrderId;
    }

    /** 存档用：每位玩家的挂单和历史委托，按编号从小到大。 */
    Map<UUID, List<Order>> state() {
        Map<UUID, List<Order>> all = new HashMap<>();
        history.forEach((player, list) -> all.computeIfAbsent(player, p -> new ArrayList<>()).addAll(list));
        open.values().forEach(order -> all.computeIfAbsent(order.player(), p -> new ArrayList<>()).add(order));
        Map<UUID, List<Order>> sorted = new HashMap<>();
        all.forEach((player, list) -> {
            list.sort(Comparator.comparingLong(Order::id));
            sorted.put(player, List.copyOf(list));
        });
        return Map.copyOf(sorted);
    }

    /** 这名玩家挂着的委托，按编号从小到大（旧的在前）。 */
    public List<Order> open(UUID player) {
        return open.values().stream().filter(order -> order.player().equals(player)).toList();
    }

    /** 这名玩家最近的已成交 / 已撤销委托，旧的在前。 */
    public List<Order> history(UUID player) {
        return List.copyOf(history.getOrDefault(player, List.of()));
    }

    /** 这名玩家挂单冻结的资金合计。 */
    public Money frozen(UUID player) {
        Money total = Money.ZERO;
        for (Order order : open.values()) {
            if (order.player().equals(player)) {
                total = total.plus(order.frozen());
            }
        }
        return total;
    }

    /**
     * 下限价委托：下单时就能成交的立即以当前可成交价成交（taker，返回 {@link TradeResult.Filled}），
     * 否则冻结资金挂单（返回 {@link TradeResult.Placed}）。
     *
     * @param lots     数量，以最小数量单位（手）计
     * @param leverage 杠杆倍数
     * @param price    委托价
     */
    public TradeResult placeLimit(UUID player, ContractSpec spec, MarketSnapshot snapshot, Side side, long lots,
            int leverage, double price) {
        return placeLimit(player, spec, snapshot, side, lots, leverage, price, Protection.NONE);
    }

    /**
     * 下限价委托并附带止盈止损：附带的触发价按委托价检查（多单止盈须高于、止损须低于委托价），挂单期间不生效，
     * 成交成仓位时才生成止盈止损委托（数量 = 本单数量）；下单即成交则立即生成。
     */
    public TradeResult placeLimit(UUID player, ContractSpec spec, MarketSnapshot snapshot, Side side, long lots,
            int leverage, double price, Protection protection) {
        return placeLimit(player, spec, snapshot, side, lots, leverage, price, protection, onlyThisContract(snapshot));
    }

    /** 同 {@link #placeLimit(UUID, ContractSpec, MarketSnapshot, Side, long, int, double, Protection)}，下单即成交时可用余额按各合约的标记价格算。 */
    public TradeResult placeLimit(UUID player, ContractSpec spec, MarketSnapshot snapshot, Side side, long lots,
            int leverage, double price, Protection protection, ToDoubleFunction<String> markPrice) {
        TradeResult invalid = book.validateOpen(player, spec, side, lots, leverage);
        if (invalid != null) {
            return invalid;
        }
        if (!(price > 0) || Double.isInfinite(price)) {
            return TradeResult.reject(TradeResult.Reason.INVALID_PRICE);
        }
        if (!protection.isEmpty()) {
            TradeResult badTrigger = checkProtection(side, protection, price);
            if (badTrigger != null) {
                return badTrigger;
            }
        }
        OrderExtras extras = new OrderExtras(false, protection.source(), protection.takeProfit(), protection.stopLoss());
        long now = snapshot.time().totalSeconds();
        if (crosses(spec, snapshot, side, TradeKind.OPEN, price)) {
            TradeResult full = checkCapacity(player, count(protection));
            if (full != null) {
                return full;
            }
            double fill = takerPrice(spec, snapshot, side, TradeKind.OPEN, lots, price);
            if (!(fill > 0)) {
                return TradeResult.reject(TradeResult.Reason.INVALID_PRICE);
            }
            TradeResult result = book.openAt(player, spec, now, side, lots, leverage, fill, TAKER_FEE, markPrice);
            if (result instanceof TradeResult.Filled filled) {
                PlayerTrade trade = filled.trade();
                remember(new Order(++lastOrderId, player, spec.symbol(), side, OrderType.LIMIT, price, lots, leverage,
                        Money.ZERO, OrderStatus.FILLED, trade.price(), trade.fee(), now, now, CancelReason.NONE, extras));
                attach(player, spec.symbol(), side, lots, protection, now);
            }
            return result;
        }
        TradeResult full = checkCapacity(player, 1);
        if (full != null) {
            return full;
        }
        Money frozen;
        try {
            frozen = TradingBook.frozenFor(spec, price, lots, leverage);
            // 持仓总量上限：挂单时按委托价检查一次，不让注定超限的单子占着冻结的资金；成交时 openAt 还会复检
            TradeResult overCap = book.checkOpenInterest(spec, lots, price, markPrice);
            if (overCap != null) {
                return overCap;
            }
        } catch (ArithmeticException e) {
            return TradeResult.reject(TradeResult.Reason.INVALID_QUANTITY);
        }
        // 和 openAt 同一个口径：全仓浮亏占用可用余额，否则挂得出去的单子成交时会因余额不足被撤
        Money available = book.assets(player, markPrice).availableBalance();
        if (available.compareTo(frozen) < 0) {
            return TradeResult.reject(TradeResult.Reason.INSUFFICIENT_BALANCE, frozen.toDisplayString(),
                    available.toDisplayString());
        }
        if (frozen.isPositive()) {
            accounts.withdraw(player, frozen);
        }
        Order order = new Order(++lastOrderId, player, spec.symbol(), side, OrderType.LIMIT, price, lots, leverage, frozen,
                OrderStatus.OPEN, 0, Money.ZERO, now, 0, CancelReason.NONE, extras);
        open.put(order.id(), order);
        return new TradeResult.Placed(order);
    }

    /** 只知道这一个合约的行情：其他合约的全仓仓位按开仓均价算（盈亏为 0）。 */
    private static ToDoubleFunction<String> onlyThisContract(MarketSnapshot snapshot) {
        return symbol -> symbol.equals(snapshot.symbol()) ? snapshot.markPrice() : Double.NaN;
    }

    /** 市价开仓并附带止盈止损：随开仓一起生效（数量 = 开仓数量）。附带的触发价不合法时整笔不成交、账不变。 */
    public TradeResult openWithProtection(UUID player, ContractSpec spec, MarketSnapshot snapshot, Side side, long lots,
            int leverage, Protection protection) {
        return openWithProtection(player, spec, snapshot, side, lots, leverage, protection, onlyThisContract(snapshot));
    }

    /** 同 {@link #openWithProtection(UUID, ContractSpec, MarketSnapshot, Side, long, int, Protection)}，可用余额按各合约的标记价格算（全仓浮亏占用可用余额）。 */
    public TradeResult openWithProtection(UUID player, ContractSpec spec, MarketSnapshot snapshot, Side side, long lots,
            int leverage, Protection protection, ToDoubleFunction<String> markPrice) {
        if (protection.isEmpty()) {
            return book.open(player, spec, snapshot, side, lots, leverage, markPrice);
        }
        TradeResult invalid = book.validateOpen(player, spec, side, lots, leverage);
        if (invalid != null) {
            return invalid;
        }
        TradeResult badTrigger = checkProtection(side, protection, reference(snapshot, protection.source()));
        if (badTrigger != null) {
            return badTrigger;
        }
        TradeResult full = checkCapacity(player, count(protection));
        if (full != null) {
            return full;
        }
        TradeResult result = book.open(player, spec, snapshot, side, lots, leverage, markPrice);
        if (result instanceof TradeResult.Filled) {
            attach(player, spec.symbol(), side, lots, protection, snapshot.time().totalSeconds());
        }
        return result;
    }

    /**
     * 给已有仓位补设止盈止损：固定数量 {@code lots}（触发时若超过仓位，按仓位缩减），不冻结资金。
     * 没有仓位拒绝（{@link TradeResult.Reason#NO_POSITION}）。
     */
    public TradeResult placeProtection(UUID player, ContractSpec spec, MarketSnapshot snapshot, long lots,
            Protection protection) {
        Position position = book.position(player, spec.symbol()).orElse(null);
        if (position == null) {
            return TradeResult.reject(TradeResult.Reason.NO_POSITION);
        }
        if (lots <= 0) {
            return TradeResult.reject(TradeResult.Reason.INVALID_QUANTITY);
        }
        TradeResult badTrigger = checkProtection(position.side(), protection, reference(snapshot, protection.source()));
        if (badTrigger != null) {
            return badTrigger;
        }
        TradeResult full = checkCapacity(player, count(protection));
        if (full != null) {
            return full;
        }
        return new TradeResult.ProtectionPlaced(
                attach(player, spec.symbol(), position.side(), lots, protection, snapshot.time().totalSeconds()));
    }

    /**
     * 只减仓限价单（限价平仓）：平当前仓位，数量超过仓位时成交按仓位缩减；没有仓位拒绝。下单时就能成交的立即按市价平仓（taker），
     * 否则挂单，价格穿越委托价时按委托价成交（maker）。不冻结资金。
     */
    public TradeResult placeReduceOnlyLimit(UUID player, ContractSpec spec, MarketSnapshot snapshot, long lots, double price) {
        Position position = book.position(player, spec.symbol()).orElse(null);
        if (position == null) {
            return TradeResult.reject(TradeResult.Reason.NO_POSITION);
        }
        if (lots <= 0) {
            return TradeResult.reject(TradeResult.Reason.INVALID_QUANTITY);
        }
        if (!(price > 0) || Double.isInfinite(price)) {
            return TradeResult.reject(TradeResult.Reason.INVALID_PRICE);
        }
        long now = snapshot.time().totalSeconds();
        long executable = Math.min(lots, position.lots());
        OrderExtras extras = new OrderExtras(true, TriggerSource.LAST, 0, 0);
        if (crosses(spec, snapshot, position.side(), TradeKind.CLOSE, price)) {
            double fill = takerPrice(spec, snapshot, position.side(), TradeKind.CLOSE, executable, price);
            if (!(fill > 0)) {
                return TradeResult.reject(TradeResult.Reason.INVALID_PRICE);
            }
            TradeResult result = book.closeAt(player, position, spec, now, executable, fill, TAKER_FEE);
            if (result instanceof TradeResult.Filled filled) {
                PlayerTrade trade = filled.trade();
                remember(new Order(++lastOrderId, player, spec.symbol(), position.side(), OrderType.LIMIT, price, executable,
                        0, Money.ZERO, OrderStatus.FILLED, trade.price(), trade.fee(), now, now, CancelReason.NONE, extras));
            }
            return result;
        }
        TradeResult full = checkCapacity(player, 1);
        if (full != null) {
            return full;
        }
        Order order = new Order(++lastOrderId, player, spec.symbol(), position.side(), OrderType.LIMIT, price, lots, 0,
                Money.ZERO, OrderStatus.OPEN, 0, Money.ZERO, now, 0, CancelReason.NONE, extras);
        open.put(order.id(), order);
        return new TradeResult.Placed(order);
    }

    /** 取走（并清空）非行情步产生的失效事件，例如玩家市价平仓后附带的止盈止损随之失效。 */
    public List<OrderEvent> takeEvents() {
        List<OrderEvent> taken = List.copyOf(deferred);
        deferred.clear();
        return taken;
    }

    private static int count(Protection protection) {
        return (protection.takeProfit() != 0 ? 1 : 0) + (protection.stopLoss() != 0 ? 1 : 0);
    }

    private TradeResult checkCapacity(UUID player, int additional) {
        if (open(player).size() + additional > MAX_OPEN_ORDERS_PER_PLAYER) {
            return TradeResult.reject(TradeResult.Reason.TOO_MANY_ORDERS, String.valueOf(MAX_OPEN_ORDERS_PER_PLAYER));
        }
        return null;
    }

    private static double reference(MarketSnapshot snapshot, TriggerSource source) {
        return source == TriggerSource.MARK ? snapshot.markPrice() : snapshot.lastPrice();
    }

    /** 触发价必须是正数且按参考价不会立即触发：多仓止盈须高于、止损须低于参考价，空仓相反。合法返回 null。 */
    private static TradeResult checkProtection(Side side, Protection protection, double reference) {
        boolean ok = !protection.isEmpty() && Double.isFinite(reference);
        if (ok && protection.takeProfit() != 0) {
            ok = Protection.validTrigger(side, OrderType.TAKE_PROFIT, protection.takeProfit(), reference);
        }
        if (ok && protection.stopLoss() != 0) {
            ok = Protection.validTrigger(side, OrderType.STOP_LOSS, protection.stopLoss(), reference);
        }
        return ok ? null : TradeResult.reject(TradeResult.Reason.INVALID_TRIGGER_PRICE,
                String.format(Locale.ROOT, "%,.2f", reference));
    }

    /** 生成止盈、止损委托（设了哪项生成哪项）；固定数量、只减仓、不冻结资金。 */
    private List<Order> attach(UUID player, String symbol, Side side, long lots, Protection protection, long now) {
        List<Order> created = new ArrayList<>();
        OrderExtras extras = new OrderExtras(true, protection.source(), 0, 0);
        if (protection.takeProfit() != 0) {
            created.add(new Order(++lastOrderId, player, symbol, side, OrderType.TAKE_PROFIT, protection.takeProfit(), lots,
                    0, Money.ZERO, OrderStatus.OPEN, 0, Money.ZERO, now, 0, CancelReason.NONE, extras));
        }
        if (protection.stopLoss() != 0) {
            created.add(new Order(++lastOrderId, player, symbol, side, OrderType.STOP_LOSS, protection.stopLoss(), lots, 0,
                    Money.ZERO, OrderStatus.OPEN, 0, Money.ZERO, now, 0, CancelReason.NONE, extras));
        }
        created.forEach(order -> open.put(order.id(), order));
        return created;
    }

    /**
     * 撤单：释放冻结的资金。
     *
     * @param marketSeconds 此刻的市场时间（市场秒）
     * @return 撤销事件；没有这张挂单（或不是这名玩家的）时为空
     */
    public Optional<OrderEvent> cancel(UUID player, long orderId, long marketSeconds) {
        Order order = open.get(orderId);
        if (order == null || !order.player().equals(player)) {
            return Optional.empty();
        }
        return Optional.of(cancel(order, CancelReason.USER, marketSeconds));
    }

    /**
     * 撤销这名玩家符合条件的开仓挂单并退回冻结资金（强平用，见 {@link CancelReason#LIQUIDATED}）。
     * 只减仓 / 止盈止损委托不在这里处理：它们跟着仓位走，由 {@link #onPositionClosed} 在仓位平光时让它们失效。
     *
     * @param symbols 哪些合约的挂单要撤销
     * @return 撤销事件，按委托编号
     */
    List<OrderEvent> cancelWhere(UUID player, Predicate<String> symbols, CancelReason reason, long marketSeconds) {
        List<OrderEvent> events = new ArrayList<>();
        for (Order order : List.copyOf(open.values())) {
            if (order.player().equals(player) && !order.reduceOnly() && symbols.test(order.symbol())) {
                events.add(cancel(order, reason, marketSeconds));
            }
        }
        return events;
    }

    /**
     * 某个合约新的行情步到来：触发穿越了委托价的限价单、到了触发价的止盈止损，按编号先后处理（离线玩家的委托同样触发）；
     * 仓位已经没有了的只减仓 / 止盈止损委托先失效。
     *
     * @return 这一步触发 / 成交 / 撤销的委托事件，按发生顺序（含此前玩家平仓留下的失效事件）
     */
    public List<OrderEvent> onSnapshot(ContractSpec spec, MarketSnapshot snapshot) {
        return onSnapshot(spec, snapshot, onlyThisContract(snapshot));
    }

    /**
     * 同上，限价委托成交时的持仓总量上限复检用全部合约的标记价格估算池子权益（上限随池子权益变化，成交时重新算）；
     * 超限的挂单撤销（{@link CancelReason#FILL_REJECTED}）。
     */
    public List<OrderEvent> onSnapshot(ContractSpec spec, MarketSnapshot snapshot, ToDoubleFunction<String> markPrice) {
        List<OrderEvent> events = new ArrayList<>(takeEvents());
        long now = snapshot.time().totalSeconds();
        for (Order order : List.copyOf(open.values())) {
            // 前面的委托平仓可能已经让这一张失效
            if (!order.symbol().equals(spec.symbol()) || !open.containsKey(order.id())) {
                continue;
            }
            if (order.reduceOnly()) {
                Position position = book.position(order.player(), order.symbol()).orElse(null);
                if (position == null || position.side() != order.side()) {
                    events.add(cancel(order, CancelReason.POSITION_CLOSED, now));
                } else if (order.type().isTrigger()) {
                    trigger(order, position, spec, snapshot, events);
                } else if (crosses(spec, snapshot, position.side(), TradeKind.CLOSE, order.price())) {
                    fillReduceOnlyLimit(order, position, spec, now, events);
                }
            } else if (crosses(spec, snapshot, order.side(), TradeKind.OPEN, order.price())) {
                events.add(fill(order, spec, now, markPrice));
            }
            events.addAll(takeEvents());
        }
        return events;
    }

    /** 这名玩家在这个合约的仓位被平光了：它的只减仓 / 止盈止损委托失效（事件留待下次取走）。 */
    void onPositionClosed(UUID player, String symbol, long marketSeconds) {
        for (Order order : List.copyOf(open.values())) {
            if (order.reduceOnly() && order.player().equals(player) && order.symbol().equals(symbol)) {
                deferred.add(cancel(order, CancelReason.POSITION_CLOSED, marketSeconds));
            }
        }
    }

    /**
     * 此刻是否穿越：只看盘口第一档（最优价，含半个点差，不含滑点）不比委托价差，
     * 即买入（开多、平空）的卖一价不高于委托价、卖出（开空、平多）的买一价不低于委托价。
     * 不看整单吃单的均价：委托价是被动成交的价格，大单的滑点只影响下单即成交（taker）那部分，见 {@link #takerPrice}。
     */
    private static boolean crosses(ContractSpec spec, MarketSnapshot snapshot, Side side, TradeKind kind, double price) {
        double last = snapshot.lastPrice();
        if (!(last > 0) || !Double.isFinite(last)) {
            return false;
        }
        boolean buy = (side == Side.LONG) == (kind == TradeKind.OPEN);
        double touch = spec.liquidityCurve(last).touchPrice(buy);
        return buy ? touch <= price : touch >= price;
    }

    /**
     * 下单即成交（taker）的成交价：同数量市价单的曲线均价（{@link TradingBook#fillPrice}，唯一成交价入口），
     * 但限价单不会比委托价更差，均价越过委托价时封顶在委托价。算不出有限正价格时返回 NaN。
     */
    private static double takerPrice(ContractSpec spec, MarketSnapshot snapshot, Side side, TradeKind kind, long lots,
            double limit) {
        double average = TradingBook.fillPrice(spec, snapshot, side, kind, lots);
        if (!(average > 0)) {
            return Double.NaN;
        }
        boolean buy = (side == Side.LONG) == (kind == TradeKind.OPEN);
        return buy ? Math.min(average, limit) : Math.max(average, limit);
    }

    /** 止盈止损：触发价到了就按市价平仓（数量按仓位缩减），产出触发与成交事件；价格算不出来时保持挂着，下个行情步再试。 */
    private void trigger(Order order, Position position, ContractSpec spec, MarketSnapshot snapshot, List<OrderEvent> events) {
        if (!reached(order, reference(snapshot, order.triggerSource()))) {
            return;
        }
        long lots = Math.min(order.lots(), position.lots());
        double price = TradingBook.fillPrice(spec, snapshot, position.side(), TradeKind.CLOSE, lots);
        if (!(price > 0)) {
            return;
        }
        long now = snapshot.time().totalSeconds();
        open.remove(order.id());
        events.add(event(order, OrderEvent.Kind.TRIGGERED, order.price(), order.lots(), Money.ZERO, now, CancelReason.NONE,
                Money.ZERO));
        PlayerTrade trade = ((TradeResult.Filled) book.closeAt(order.player(), position, spec, now, lots, price, TAKER_FEE))
                .trade();
        remember(order.filled(trade.price(), trade.fee(), lots, now));
        events.add(event(order, OrderEvent.Kind.FILLED, trade.price(), lots, trade.fee(), now, CancelReason.NONE,
                trade.realizedPnl()));
    }

    /** 止盈：多仓价格涨到触发价、空仓跌到触发价；止损相反。跳空越过触发价同样算到达。 */
    private static boolean reached(Order order, double current) {
        if (!Double.isFinite(current)) {
            return false;
        }
        boolean upward = (order.type() == OrderType.TAKE_PROFIT) == (order.side() == Side.LONG);
        return upward ? current >= order.price() : current <= order.price();
    }

    /** 只减仓限价单穿越：按委托价、maker 费率平仓（数量按仓位缩减）。 */
    private void fillReduceOnlyLimit(Order order, Position position, ContractSpec spec, long now, List<OrderEvent> events) {
        long lots = Math.min(order.lots(), position.lots());
        open.remove(order.id());
        PlayerTrade trade = ((TradeResult.Filled) book.closeAt(order.player(), position, spec, now, lots, order.price(),
                MAKER_FEE)).trade();
        remember(order.filled(trade.price(), trade.fee(), lots, now));
        events.add(event(order, OrderEvent.Kind.FILLED, trade.price(), lots, trade.fee(), now, CancelReason.NONE,
                trade.realizedPnl()));
    }

    /** 委托价成交：冻结的资金先划回可用余额，再按委托价以 maker 费率开仓（所需正好等于冻结）；附带的止盈止损此时生效。 */
    private OrderEvent fill(Order order, ContractSpec spec, long marketSeconds, ToDoubleFunction<String> markPrice) {
        open.remove(order.id());
        release(order);
        TradeResult invalid = book.validateOpen(order.player(), spec, order.side(), order.lots(), order.leverage());
        TradeResult result = invalid != null ? invalid
                : book.openAt(order.player(), spec, marketSeconds, order.side(), order.lots(), order.leverage(),
                        order.price(), MAKER_FEE, markPrice);
        if (result instanceof TradeResult.Filled filled) {
            PlayerTrade trade = filled.trade();
            remember(order.filled(trade.price(), trade.fee(), marketSeconds));
            Protection attached = order.extras().attached();
            if (!attached.isEmpty()) {
                attach(order.player(), order.symbol(), order.side(), order.lots(), attached, marketSeconds);
            }
            return event(order, OrderEvent.Kind.FILLED, trade.price(), order.lots(), trade.fee(), marketSeconds,
                    CancelReason.NONE, Money.ZERO);
        }
        return cancelled(order, CancelReason.FILL_REJECTED, marketSeconds);
    }

    private OrderEvent cancel(Order order, CancelReason reason, long marketSeconds) {
        open.remove(order.id());
        release(order);
        return cancelled(order, reason, marketSeconds);
    }

    private void release(Order order) {
        if (order.frozen().isPositive()) {
            accounts.deposit(order.player(), order.frozen());
        }
    }

    /** 冻结的资金已划回，记入历史并产出撤销事件。 */
    private OrderEvent cancelled(Order order, CancelReason reason, long marketSeconds) {
        remember(order.cancelled(reason, marketSeconds));
        return event(order, OrderEvent.Kind.CANCELLED, order.price(), order.lots(), order.frozen(), marketSeconds, reason,
                Money.ZERO);
    }

    private static OrderEvent event(Order order, OrderEvent.Kind kind, double price, long lots, Money amount,
            long marketSeconds, CancelReason reason, Money realizedPnl) {
        return new OrderEvent(order.player(), kind, order.id(), order.symbol(), order.side(), price, lots, amount,
                marketSeconds, reason, order.type(), order.triggerSource(), realizedPnl);
    }

    private void remember(Order finished) {
        List<Order> list = history.computeIfAbsent(finished.player(), p -> new ArrayList<>());
        list.add(finished);
        if (list.size() > MAX_HISTORY_PER_PLAYER) {
            list.remove(0);
        }
    }
}
