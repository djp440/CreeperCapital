package io.github.djp440.creepercapital.core.trading;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 全服的限价委托：挂单、撤单，以及每个行情步按新价格触发成交。由 {@link TradingBook#orders()} 持有。
 * <p>
 * 价格口径：买单在可成交买价（最新价 + 半个点差）不高于委托价时穿越，卖单在可成交卖价（最新价 − 半个点差）
 * 不低于委托价时穿越；穿越（含一步跳过委托价的跳空）一律按委托价成交，收 maker 费。下单时就能成交的委托
 * 按当前可成交价成交，收 taker 费（和市价单走同一个成交价入口，不在这里另算）。
 * <p>
 * 资金：挂单时把保证金与预估手续费（{@link TradingBook#frozenFor}）从可用余额划进委托的 {@link Order#frozen()}，
 * 守恒恒等式变为 可用余额 + 冻结 + 仓位保证金 + 池子 不变。撤单把冻结原数划回；成交时先划回再按委托价开仓，
 * 开仓所需正好等于冻结。其间开仓条件变了（反向仓位、杠杆不一致）则撤单（{@link CancelReason#FILL_REJECTED}）。
 * <p>
 * 有效期只有 GTC。委托类型 / 状态是枚举，{@link Order} 留了扩展位，#15 的止盈止损、只减仓往这里加。
 */
public final class OrderBook {
    /** 每名玩家最多同时挂多少张委托。 */
    public static final int MAX_OPEN_ORDERS_PER_PLAYER = 20;

    /** 每名玩家的历史委托最多保留多少条（最近的）。 */
    public static final int MAX_HISTORY_PER_PLAYER = 100;

    private static final BigDecimal MAKER_FEE = BigDecimal.valueOf(TradingBook.MAKER_FEE_RATE);

    private final TradingBook book;
    private final AccountBook accounts;
    /** 挂着的委托，按编号排序，保证同一步里先下的先成交。 */
    private final TreeMap<Long, Order> open = new TreeMap<>();
    private final Map<UUID, List<Order>> history = new HashMap<>();
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
        TradeResult invalid = book.validateOpen(player, spec, side, lots, leverage);
        if (invalid != null) {
            return invalid;
        }
        if (!(price > 0) || Double.isInfinite(price)) {
            return TradeResult.reject(TradeResult.Reason.INVALID_PRICE);
        }
        long now = snapshot.time().totalSeconds();
        if (crosses(spec, snapshot, side, lots, price)) {
            TradeResult result = book.open(player, spec, snapshot, side, lots, leverage);
            if (result instanceof TradeResult.Filled filled) {
                PlayerTrade trade = filled.trade();
                remember(new Order(++lastOrderId, player, spec.symbol(), side, OrderType.LIMIT, price, lots, leverage,
                        Money.ZERO, OrderStatus.FILLED, trade.price(), trade.fee(), now, now, CancelReason.NONE));
            }
            return result;
        }
        if (open(player).size() >= MAX_OPEN_ORDERS_PER_PLAYER) {
            return TradeResult.reject(TradeResult.Reason.TOO_MANY_ORDERS, String.valueOf(MAX_OPEN_ORDERS_PER_PLAYER));
        }
        Money frozen;
        try {
            frozen = TradingBook.frozenFor(spec, price, lots, leverage);
        } catch (ArithmeticException e) {
            return TradeResult.reject(TradeResult.Reason.INVALID_QUANTITY);
        }
        Money available = accounts.balance(player);
        if (available.compareTo(frozen) < 0) {
            return TradeResult.reject(TradeResult.Reason.INSUFFICIENT_BALANCE, frozen.toDisplayString(),
                    available.toDisplayString());
        }
        if (frozen.isPositive()) {
            accounts.withdraw(player, frozen);
        }
        Order order = new Order(++lastOrderId, player, spec.symbol(), side, OrderType.LIMIT, price, lots, leverage, frozen,
                OrderStatus.OPEN, 0, Money.ZERO, now, 0, CancelReason.NONE);
        open.put(order.id(), order);
        return new TradeResult.Placed(order);
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
     * 某个合约新的行情步到来：触发穿越了委托价的挂单，按编号先后成交（离线玩家的委托同样触发）。
     *
     * @return 这一步成交 / 撤销的委托事件，按触发顺序
     */
    public List<OrderEvent> onSnapshot(ContractSpec spec, MarketSnapshot snapshot) {
        List<OrderEvent> events = new ArrayList<>();
        for (Order order : List.copyOf(open.values())) {
            if (order.symbol().equals(spec.symbol()) && crosses(spec, snapshot, order.side(), order.lots(), order.price())) {
                events.add(fill(order, spec, snapshot.time().totalSeconds()));
            }
        }
        return events;
    }

    /**
     * 此刻是否穿越：这么多手按市价吃单的均价（买入含半个点差和滑点，唯一成交价入口）不比委托价差，
     * 即买单均价不高于委托价、卖单均价不低于委托价。用数量算而不是只看第一档，保证“下单即成交”的 taker 成交价
     * 永远不会比委托价差；代价是大单要等价格更深地越过委托价才会触发。
     */
    private static boolean crosses(ContractSpec spec, MarketSnapshot snapshot, Side side, long lots, double price) {
        double quote = TradingBook.fillPrice(spec, snapshot, side, lots);
        if (!(quote > 0)) {
            return false;
        }
        return side == Side.LONG ? quote <= price : quote >= price;
    }

    /** 委托价成交：冻结的资金先划回可用余额，再按委托价以 maker 费率开仓（所需正好等于冻结）。 */
    private OrderEvent fill(Order order, ContractSpec spec, long marketSeconds) {
        open.remove(order.id());
        release(order);
        TradeResult invalid = book.validateOpen(order.player(), spec, order.side(), order.lots(), order.leverage());
        TradeResult result = invalid != null ? invalid
                : book.openAt(order.player(), spec, marketSeconds, order.side(), order.lots(), order.leverage(),
                        order.price(), MAKER_FEE);
        if (result instanceof TradeResult.Filled filled) {
            PlayerTrade trade = filled.trade();
            remember(order.filled(trade.price(), trade.fee(), marketSeconds));
            return new OrderEvent(order.player(), OrderEvent.Kind.FILLED, order.id(), order.symbol(), order.side(),
                    trade.price(), order.lots(), trade.fee(), marketSeconds, CancelReason.NONE);
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
        return new OrderEvent(order.player(), OrderEvent.Kind.CANCELLED, order.id(), order.symbol(), order.side(),
                order.price(), order.lots(), order.frozen(), marketSeconds, reason);
    }

    private void remember(Order finished) {
        List<Order> list = history.computeIfAbsent(finished.player(), p -> new ArrayList<>());
        list.add(finished);
        if (list.size() > MAX_HISTORY_PER_PLAYER) {
            list.remove(0);
        }
    }
}
