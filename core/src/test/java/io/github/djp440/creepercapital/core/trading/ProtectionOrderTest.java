package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 止盈止损与只减仓。钻石：点差 0.5（可成交买价 = 最新价 + 0.25，卖价 = 最新价 − 0.25）、数量精度 3 位（2000 手 = 2.000 个）；
 * taker 费 0.05%、maker 费 0.02%。手算期望值用无限深的合约；「触发成交价 = 同数量市价单成交价」用有滑点的默认合约。
 * 触发价来源分别按最新价格、标记价格判定；一个行情步内跳空越过触发价也算触发。
 */
class ProtectionOrderTest {
    private static final ContractSpec DEEP_DIAMOND = ContractSpec.defaults().get(0);
    private static final ContractSpec DIAMOND = new ContractSpec(DEEP_DIAMOND.symbol(), DEEP_DIAMOND.underlying(),
            DEEP_DIAMOND.initialPrice(), DEEP_DIAMOND.baseVolume(), DEEP_DIAMOND.volatility(), DEEP_DIAMOND.spread(),
            DEEP_DIAMOND.maxLeverage(), DEEP_DIAMOND.quantityDecimals(), 0.0);
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);

    private AccountBook accounts;
    private LiquidityPool pool;
    private TradingBook book;
    private OrderBook orders;

    @BeforeEach
    void setUp() {
        accounts = new AccountBook();
        pool = new LiquidityPool();
        book = new TradingBook(accounts, pool);
        orders = book.orders();
    }

    private static MarketSnapshot snapshot(double last, double mark, long step) {
        return new MarketSnapshot(step, DIAMOND.symbol(), last, last, mark, 0.0001, 0);
    }

    private static MarketSnapshot snapshot(double last) {
        return snapshot(last, last, 100);
    }

    private static TradeResult.Reason rejection(TradeResult result) {
        return assertInstanceOf(TradeResult.Rejected.class, result).reason();
    }

    private static Protection last(double takeProfit, double stopLoss) {
        return new Protection(takeProfit, stopLoss, TriggerSource.LAST);
    }

    private static Protection mark(double takeProfit, double stopLoss) {
        return new Protection(takeProfit, stopLoss, TriggerSource.MARK);
    }

    private Money total(UUID... players) {
        Money sum = pool.balance();
        for (UUID player : players) {
            sum = sum.plus(accounts.balance(player)).plus(orders.frozen(player));
            for (Position position : book.positions(player)) {
                sum = sum.plus(position.margin());
            }
        }
        return sum;
    }

    /** 在最新价 1000 时给玩家开 2.000 个钻石、10 倍杠杆的多仓，成交价 1000.25。 */
    private void openLong(UUID player, ContractSpec spec, long lots) {
        accounts.deposit(player, Money.parse("100000"));
        assertInstanceOf(TradeResult.Filled.class, book.open(player, spec, snapshot(1000), Side.LONG, lots, 10));
    }

    private void openLong() {
        openLong(ALICE, DIAMOND, 2000);
    }

    private Order historyOf(OrderType type) {
        return orders.history(ALICE).stream().filter(order -> order.type() == type).findFirst().orElseThrow();
    }

    private List<Order> placeProtection(UUID player, long lots, Protection protection) {
        return assertInstanceOf(TradeResult.ProtectionPlaced.class,
                orders.placeProtection(player, DIAMOND, snapshot(1000), lots, protection)).orders();
    }

    @Test
    @DisplayName("止盈按最新价格触发：按市价平仓（卖价 = 最新价 − 半个点差）、收 taker 费，附带的止损随仓位平掉自动失效")
    void takeProfitTriggersOnLastPriceAndClosesAtMarket() {
        openLong();
        Money before = total(ALICE);
        List<Order> placed = placeProtection(ALICE, 2000, last(1100, 900));
        assertEquals(List.of(OrderType.TAKE_PROFIT, OrderType.STOP_LOSS), placed.stream().map(Order::type).toList());
        assertEquals(Money.ZERO, placed.get(0).frozen());
        assertTrue(placed.get(0).reduceOnly());

        // 最新价还没到 1100：不触发
        assertTrue(orders.onSnapshot(DIAMOND, snapshot(1099.9)).isEmpty());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isPresent());

        List<OrderEvent> events = orders.onSnapshot(DIAMOND, snapshot(1100, 1000, 120));

        // 成交价 1099.75；盈亏 (1099.75 − 1000.25) × 2 = 199；手续费 2199.5 × 0.05% = 1.09975
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        PlayerTrade trade = book.trades(ALICE).get(1);
        assertEquals(TradeKind.CLOSE, trade.kind());
        assertEquals(1099.75, trade.price());
        assertEquals(Money.parse("199"), trade.realizedPnl());
        assertEquals(Money.parse("1.09975"), trade.fee());
        // 100000 + 199 − 开仓费 1.00025 − 平仓费 1.09975
        assertEquals(Money.parse("100196.9"), accounts.balance(ALICE));
        assertEquals(before, total(ALICE));

        assertEquals(List.of(OrderEvent.Kind.TRIGGERED, OrderEvent.Kind.FILLED, OrderEvent.Kind.CANCELLED),
                events.stream().map(OrderEvent::kind).toList());
        OrderEvent triggered = events.get(0);
        assertEquals(OrderType.TAKE_PROFIT, triggered.type());
        assertEquals(1100, triggered.price());
        assertEquals(TriggerSource.LAST, triggered.triggerSource());
        assertEquals(720, triggered.marketSeconds());
        OrderEvent filled = events.get(1);
        assertEquals(1099.75, filled.price());
        assertEquals(2000, filled.lots());
        assertEquals(Money.parse("1.09975"), filled.amount());
        assertEquals(Money.parse("199"), filled.realizedPnl());
        OrderEvent invalidated = events.get(2);
        assertEquals(OrderType.STOP_LOSS, invalidated.type());
        assertEquals(CancelReason.POSITION_CLOSED, invalidated.cancelReason());

        assertTrue(orders.open(ALICE).isEmpty());
        Order takeProfit = historyOf(OrderType.TAKE_PROFIT);
        assertEquals(OrderStatus.FILLED, takeProfit.status());
        assertEquals(1099.75, takeProfit.fillPrice());
        Order stopLoss = historyOf(OrderType.STOP_LOSS);
        assertEquals(OrderStatus.CANCELLED, stopLoss.status());
        assertEquals(CancelReason.POSITION_CLOSED, stopLoss.cancelReason());
    }

    @Test
    @DisplayName("止损按标记价格触发：最新价格没到不触发，标记价格到了才触发")
    void stopLossTriggersOnMarkPrice() {
        openLong();
        placeProtection(ALICE, 2000, mark(1100, 900));

        // 最新价 880 已经低于 900，但触发价来源是标记价格（950）：不触发
        assertTrue(orders.onSnapshot(DIAMOND, snapshot(880, 950, 120)).isEmpty());
        assertEquals(2, orders.open(ALICE).size());

        // 标记价格 899 ≤ 900：触发，成交价按最新价格 880 − 0.25
        List<OrderEvent> events = orders.onSnapshot(DIAMOND, snapshot(880, 899, 126));

        assertEquals(OrderEvent.Kind.TRIGGERED, events.get(0).kind());
        assertEquals(OrderType.STOP_LOSS, events.get(0).type());
        assertEquals(TriggerSource.MARK, events.get(0).triggerSource());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        assertEquals(879.75, book.trades(ALICE).get(1).price());
    }

    @Test
    @DisplayName("默认的最新价格来源：标记价格到了触发价但最新价格没到，不触发")
    void lastPriceSourceIgnoresMarkPrice() {
        openLong();
        placeProtection(ALICE, 2000, last(1100, 900));

        assertTrue(orders.onSnapshot(DIAMOND, snapshot(1000, 1200, 120)).isEmpty());
        assertTrue(orders.onSnapshot(DIAMOND, snapshot(1000, 800, 126)).isEmpty());

        assertEquals(2, orders.open(ALICE).size());
    }

    @Test
    @DisplayName("一个行情步跳空越过触发价：越过即触发，按那一步的市价成交")
    void gapAcrossTriggerPriceStillTriggers() {
        openLong();
        placeProtection(ALICE, 2000, last(0, 990));

        // 990 的止损，价格一步从 1000 跳到 960：卖价 959.75，盈亏 (959.75 − 1000.25) × 2 = −81
        orders.onSnapshot(DIAMOND, snapshot(960, 960, 120));

        PlayerTrade trade = book.trades(ALICE).get(1);
        assertEquals(959.75, trade.price());
        assertEquals(Money.parse("-81"), trade.realizedPnl());
    }

    @Test
    @DisplayName("空仓：止盈在价格跌到触发价时触发，按买价（最新价 + 半个点差）平仓")
    void shortPositionTakeProfitTriggersDownward() {
        accounts.deposit(ALICE, Money.parse("10000"));
        book.open(ALICE, DIAMOND, snapshot(1000), Side.SHORT, 2000, 10);
        placeProtection(ALICE, 2000, last(900, 1100));

        assertTrue(orders.onSnapshot(DIAMOND, snapshot(900.1)).isEmpty());
        orders.onSnapshot(DIAMOND, snapshot(890, 890, 120));

        // 开空成交价 999.75；平仓买价 890.25；盈亏 (999.75 − 890.25) × 2 = 219
        PlayerTrade trade = book.trades(ALICE).get(1);
        assertEquals(890.25, trade.price());
        assertEquals(Money.parse("219"), trade.realizedPnl());
        assertTrue(orders.open(ALICE).isEmpty());
    }

    @Test
    @DisplayName("触发成交价 = 同数量同方向市价平仓的成交价（含滑点），手续费也相同")
    void triggeredFillEqualsMarketCloseFill() {
        // 有滑点的默认合约，20 个标的物的大单会吃好几档
        openLong(ALICE, DEEP_DIAMOND, 20000);
        openLong(BOB, DEEP_DIAMOND, 20000);
        orders.placeProtection(ALICE, DEEP_DIAMOND, new MarketSnapshot(100, "DIAMONDCRP", 1000, 1000, 1000, 0.0001, 0),
                20000, last(1100, 0));
        MarketSnapshot later = new MarketSnapshot(120, "DIAMONDCRP", 1100, 1100, 1100, 0.0001, 0);

        orders.onSnapshot(DEEP_DIAMOND, later);
        TradeResult market = book.close(BOB, DEEP_DIAMOND, later, 20000);

        PlayerTrade viaMarket = assertInstanceOf(TradeResult.Filled.class, market).trade();
        PlayerTrade viaTrigger = book.trades(ALICE).get(1);
        assertEquals(viaMarket.price(), viaTrigger.price());
        assertEquals(viaMarket.fee(), viaTrigger.fee());
        assertEquals(viaMarket.realizedPnl(), viaTrigger.realizedPnl());
        // 有滑点：成交价比「最新价 − 半个点差」更低
        assertTrue(viaTrigger.price() < 1100 - DEEP_DIAMOND.spread() / 2);
    }

    @Test
    @DisplayName("触发价必须不会立即触发：多仓止盈须高于、止损须低于参考价；非正数、空设置都拒绝，账不变")
    void invalidTriggerPricesAreRejected() {
        openLong();
        Money before = total(ALICE);

        assertEquals(TradeResult.Reason.INVALID_TRIGGER_PRICE,
                rejection(orders.placeProtection(ALICE, DIAMOND, snapshot(1000), 2000, last(999, 0))));
        assertEquals(TradeResult.Reason.INVALID_TRIGGER_PRICE,
                rejection(orders.placeProtection(ALICE, DIAMOND, snapshot(1000), 2000, last(0, 1000))));
        assertEquals(TradeResult.Reason.INVALID_TRIGGER_PRICE,
                rejection(orders.placeProtection(ALICE, DIAMOND, snapshot(1000), 2000, last(-5, 0))));
        assertEquals(TradeResult.Reason.INVALID_TRIGGER_PRICE,
                rejection(orders.placeProtection(ALICE, DIAMOND, snapshot(1000), 2000, Protection.NONE)));
        // 标记价格来源按标记价格比：最新价 1000，标记价 1200，止盈 1100 已经到了
        assertEquals(TradeResult.Reason.INVALID_TRIGGER_PRICE,
                rejection(orders.placeProtection(ALICE, DIAMOND, snapshot(1000, 1200, 100), 2000, mark(1100, 0))));
        assertTrue(orders.open(ALICE).isEmpty());
        assertEquals(before, total(ALICE));
    }

    @Test
    @DisplayName("没有仓位不能设止盈止损；数量必须为正")
    void protectionNeedsPositionAndQuantity() {
        accounts.deposit(ALICE, Money.parse("1000"));

        assertEquals(TradeResult.Reason.NO_POSITION,
                rejection(orders.placeProtection(ALICE, DIAMOND, snapshot(1000), 2000, last(1100, 900))));
        openLong();
        assertEquals(TradeResult.Reason.INVALID_QUANTITY,
                rejection(orders.placeProtection(ALICE, DIAMOND, snapshot(1000), 0, last(1100, 900))));
    }

    @Test
    @DisplayName("止盈止损是固定数量：只平一部分，剩下的仓位和另一张委托继续有效；数量超过仓位时按仓位缩减")
    void quantityIsFixedAndShrinksToPosition() {
        openLong();
        placeProtection(ALICE, 1000, last(1100, 900));
        // 超过仓位的止盈：触发时按仓位缩减
        placeProtection(ALICE, 5000, last(1200, 0));

        orders.onSnapshot(DIAMOND, snapshot(1100, 1100, 120));

        Position left = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(1000, left.lots());
        // 止盈 1100（1000 手）成交，止损 900（1000 手）和止盈 1200 仍挂着
        assertEquals(2, orders.open(ALICE).size());

        orders.onSnapshot(DIAMOND, snapshot(1200, 1200, 126));

        // 止盈 1200 的 5000 手缩减到仓位剩余的 1000 手
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        Order shrunk = orders.history(ALICE).stream().filter(order -> order.price() == 1200).findFirst().orElseThrow();
        assertEquals(OrderStatus.FILLED, shrunk.status());
        assertEquals(1000, shrunk.lots());
        assertTrue(orders.open(ALICE).isEmpty());
    }

    @Test
    @DisplayName("市价开仓附带止盈止损：随开仓立即生效，数量 = 开仓数量；仓位被市价全平后自动失效")
    void marketOpenWithAttachedProtection() {
        accounts.deposit(ALICE, Money.parse("10000"));

        TradeResult result = orders.openWithProtection(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10, last(1100, 900));

        assertInstanceOf(TradeResult.Filled.class, result);
        List<Order> open = orders.open(ALICE);
        assertEquals(List.of(OrderType.TAKE_PROFIT, OrderType.STOP_LOSS), open.stream().map(Order::type).toList());
        assertEquals(List.of(2000L, 2000L), open.stream().map(Order::lots).toList());
        assertEquals(List.of(1100.0, 900.0), open.stream().map(Order::price).toList());

        book.close(ALICE, DIAMOND, snapshot(1000), 2000);

        assertTrue(orders.open(ALICE).isEmpty());
        assertEquals(List.of(CancelReason.POSITION_CLOSED, CancelReason.POSITION_CLOSED),
                orders.history(ALICE).stream().map(Order::cancelReason).toList());
        List<OrderEvent> events = orders.takeEvents();
        assertEquals(2, events.size());
        assertEquals(CancelReason.POSITION_CLOSED, events.get(0).cancelReason());
        assertTrue(orders.takeEvents().isEmpty());
    }

    @Test
    @DisplayName("附带的触发价不合法时整笔开仓被拒，账不变")
    void marketOpenWithInvalidProtectionIsRejectedBeforeOpening() {
        accounts.deposit(ALICE, Money.parse("10000"));

        TradeResult result = orders.openWithProtection(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10, last(950, 0));

        assertEquals(TradeResult.Reason.INVALID_TRIGGER_PRICE, rejection(result));
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        assertEquals(Money.parse("10000"), accounts.balance(ALICE));
        assertTrue(orders.open(ALICE).isEmpty());
    }

    @Test
    @DisplayName("限价开仓附带止盈止损：挂单期间不生效，成交成仓位时才生效；撤单则没有")
    void limitOpenWithAttachedProtectionActivatesOnFill() {
        accounts.deposit(ALICE, Money.parse("10000"));
        Order limit = assertInstanceOf(TradeResult.Placed.class,
                orders.placeLimit(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10, 990, mark(1100, 900))).order();
        assertEquals(List.of(limit), orders.open(ALICE));

        // 委托价 990 成交
        orders.onSnapshot(DIAMOND, snapshot(989.7, 989.7, 120));

        assertEquals(List.of(OrderType.TAKE_PROFIT, OrderType.STOP_LOSS), orders.open(ALICE).stream().map(Order::type).toList());
        assertEquals(List.of(2000L, 2000L), orders.open(ALICE).stream().map(Order::lots).toList());
        assertEquals(TriggerSource.MARK, orders.open(ALICE).get(0).triggerSource());

        // 另一张限价单被撤：什么都不生成
        Order other = assertInstanceOf(TradeResult.Placed.class,
                orders.placeLimit(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10, 980, last(1100, 900))).order();
        orders.cancel(ALICE, other.id(), 130);
        assertEquals(2, orders.open(ALICE).size());
    }

    @Test
    @DisplayName("限价开仓附带的触发价按委托价检查：委托价 990 的多单，止损 995 不合法")
    void limitOpenProtectionIsCheckedAgainstTheLimitPrice() {
        accounts.deposit(ALICE, Money.parse("10000"));

        TradeResult result = orders.placeLimit(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10, 990, last(0, 995));

        assertEquals(TradeResult.Reason.INVALID_TRIGGER_PRICE, rejection(result));
        assertEquals(Money.parse("10000"), accounts.balance(ALICE));
    }

    @Test
    @DisplayName("只减仓限价单：没有仓位拒绝；数量超过仓位时成交按仓位缩减，按委托价成交收 maker 费")
    void reduceOnlyLimitShrinksToPosition() {
        accounts.deposit(ALICE, Money.parse("10000"));
        assertEquals(TradeResult.Reason.NO_POSITION,
                rejection(orders.placeReduceOnlyLimit(ALICE, DIAMOND, snapshot(1000), 2000, 1100)));
        book.open(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10);

        Order resting = assertInstanceOf(TradeResult.Placed.class,
                orders.placeReduceOnlyLimit(ALICE, DIAMOND, snapshot(1000), 5000, 1100)).order();

        assertTrue(resting.reduceOnly());
        assertEquals(Side.LONG, resting.side());
        assertEquals(Money.ZERO, resting.frozen());
        // 卖价 1100.05 ≥ 1100：穿越，按委托价 1100 平仓；盈亏 (1100 − 1000.25) × 2 = 199.5；maker 费 2200 × 0.02% = 0.44
        orders.onSnapshot(DIAMOND, snapshot(1100.3, 1100.3, 120));

        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        PlayerTrade trade = book.trades(ALICE).get(1);
        assertEquals(1100, trade.price());
        assertEquals(2000, trade.lots());
        assertEquals(Money.parse("199.5"), trade.realizedPnl());
        assertEquals(Money.parse("0.44"), trade.fee());
        Order done = orders.history(ALICE).get(0);
        assertEquals(OrderStatus.FILLED, done.status());
        assertEquals(2000, done.lots());
    }

    @Test
    @DisplayName("只减仓限价单下单时已能成交：立即按市价平仓（taker），数量按仓位缩减")
    void reduceOnlyLimitThatCrossesFillsImmediately() {
        openLong();

        TradeResult result = orders.placeReduceOnlyLimit(ALICE, DIAMOND, snapshot(1000), 5000, 990);

        PlayerTrade trade = assertInstanceOf(TradeResult.Filled.class, result).trade();
        assertEquals(999.75, trade.price());
        assertEquals(2000, trade.lots());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
    }

    @Test
    @DisplayName("只减仓挂单遇到仓位被平掉：自动失效，永远不会成交、也不会开出反向仓位")
    void reduceOnlyOrderNeverFillsWithoutPosition() {
        openLong();
        orders.placeReduceOnlyLimit(ALICE, DIAMOND, snapshot(1000), 2000, 1100);
        book.close(ALICE, DIAMOND, snapshot(1000), 2000);
        assertTrue(orders.open(ALICE).isEmpty());

        orders.onSnapshot(DIAMOND, snapshot(1200, 1200, 120));

        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        assertEquals(CancelReason.POSITION_CLOSED, orders.history(ALICE).get(0).cancelReason());
        assertEquals(2, book.trades(ALICE).size());
    }

    @Test
    @DisplayName("市价只减仓：数量超过仓位时缩减到仓位；没有仓位拒绝")
    void marketReduceOnlyShrinksToPosition() {
        accounts.deposit(ALICE, Money.parse("10000"));
        assertEquals(TradeResult.Reason.NO_POSITION, rejection(book.closeUpTo(ALICE, DIAMOND, snapshot(1000), 2000)));
        book.open(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10);

        PlayerTrade trade = assertInstanceOf(TradeResult.Filled.class, book.closeUpTo(ALICE, DIAMOND, snapshot(1000), 9000)).trade();

        assertEquals(2000, trade.lots());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
    }

    @Test
    @DisplayName("全部委托跨存档保留：恢复后止盈止损照常触发")
    void protectionOrdersSurviveSaveAndRestore() {
        openLong();
        placeProtection(ALICE, 2000, mark(1100, 900));
        orders.placeReduceOnlyLimit(ALICE, DIAMOND, snapshot(1000), 1000, 1500);

        TradingBook restored = TradingBook.restore(accounts, book.state());

        List<Order> open = restored.orders().open(ALICE);
        assertEquals(3, open.size());
        assertEquals(TriggerSource.MARK, open.get(0).triggerSource());
        assertTrue(open.get(0).reduceOnly());
        restored.orders().onSnapshot(DIAMOND, snapshot(1000, 1100, 120));
        assertTrue(restored.position(ALICE, "DIAMONDCRP").isEmpty());
        assertTrue(restored.orders().open(ALICE).isEmpty());
    }

    @Test
    @DisplayName("随机操作下资金守恒：可用余额 + 冻结 + 仓位保证金 + 池子 不变")
    void totalIsConservedAcrossTriggersAndInvalidations() {
        accounts.deposit(ALICE, Money.parse("5000"));
        accounts.deposit(BOB, Money.parse("5000"));
        Money before = total(ALICE, BOB);
        book.open(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10);
        orders.openWithProtection(BOB, DIAMOND, snapshot(1000), Side.SHORT, 3000, 5, last(800, 1200));
        placeProtection(ALICE, 2000, last(1050, 950));
        orders.placeReduceOnlyLimit(BOB, DIAMOND, snapshot(1000), 1000, 900);

        double[] path = {1010, 1030, 940, 905, 1100, 870, 1250};
        long step = 120;
        for (double price : path) {
            orders.onSnapshot(DIAMOND, snapshot(price, price, step));
            step += 6;
            assertEquals(before, total(ALICE, BOB));
        }
        assertTrue(book.positions(ALICE).isEmpty());
        assertTrue(book.positions(BOB).isEmpty());
        assertTrue(orders.open(ALICE).isEmpty());
        assertTrue(orders.open(BOB).isEmpty());
    }
}
