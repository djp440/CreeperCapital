package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 限价委托：买单在「最新价 + 半个点差」不高于委托价时、卖单在「最新价 − 半个点差」不低于委托价时穿越，
 * 穿越（含跳空）按委托价成交、收 maker 费 0.02%；下单时就能成交的按当前可成交价成交、收 taker 费 0.05%。
 * 挂单冻结保证金和按 maker 费率预估的手续费。钻石：点差 0.5、数量精度 3 位。期望值都是手算的。
 */
class OrderBookTest {
    private static final ContractSpec DEEP_DIAMOND = ContractSpec.defaults().get(0);
    // 手算期望值用无限深的合约（成交价就是最新价 ± 半个点差）；滑点见 slippage 一节
    private static final ContractSpec DIAMOND = unlimitedDepth(DEEP_DIAMOND);
    private static final ContractSpec GUNPOWDER = unlimitedDepth(ContractSpec.defaults().get(1));
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

    private static ContractSpec unlimitedDepth(ContractSpec spec) {
        return new ContractSpec(spec.symbol(), spec.underlying(), spec.initialPrice(), spec.baseVolume(), spec.volatility(),
                spec.spread(), spec.maxLeverage(), spec.quantityDecimals(), 0.0);
    }

    private static MarketSnapshot snapshot(ContractSpec spec, double last, long step) {
        return new MarketSnapshot(step, spec.symbol(), last, last, last, 0.0001, 0);
    }

    private static MarketSnapshot snapshot(ContractSpec spec, double last) {
        return snapshot(spec, last, 100);
    }

    private void deposit(String amount) {
        accounts.deposit(ALICE, Money.parse(amount));
    }

    private static Order placed(TradeResult result) {
        return assertInstanceOf(TradeResult.Placed.class, result).order();
    }

    private static TradeResult.Reason rejection(TradeResult result) {
        return assertInstanceOf(TradeResult.Rejected.class, result).reason();
    }

    /** 在最新价 1000 时挂 2.000 个钻石、10 倍杠杆的限价买单。 */
    private Order restingBuy(double limit) {
        return placed(orders.placeLimit(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 2000, 10, limit));
    }

    @Test
    @DisplayName("挂买单：冻结保证金与 maker 预估手续费，可用余额减少、钱包余额不变")
    void restingOrderFreezesMarginAndFee() {
        deposit("1000");

        // 委托价 990：名义价值 1980，10 倍杠杆保证金 198，maker 手续费 0.02% × 1980 = 0.396
        Order order = restingBuy(990);

        assertEquals(OrderStatus.OPEN, order.status());
        assertEquals(OrderType.LIMIT, order.type());
        assertEquals(1, order.id());
        assertEquals(Money.parse("198.396"), order.frozen());
        assertEquals(Money.parse("801.604"), accounts.balance(ALICE));
        Assets assets = book.assets(ALICE, symbol -> 1000);
        assertEquals(Money.parse("801.604"), assets.availableBalance());
        assertEquals(Money.parse("1000"), assets.walletBalance());
        assertEquals(List.of(order), orders.open(ALICE));
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
    }

    @Test
    @DisplayName("价格穿越委托价：按委托价成交，收 maker 费，冻结转为仓位保证金")
    void crossingFillsAtLimitPriceAsMaker() {
        deposit("1000");
        restingBuy(990);

        // 最新价 989.7 → 可成交买价 989.95 ≤ 990
        List<OrderEvent> events = orders.onSnapshot(DIAMOND, snapshot(DIAMOND, 989.7, 120));

        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(990, position.entryPrice());
        assertEquals(2000, position.lots());
        assertEquals(Money.parse("198"), position.margin());
        assertEquals(Money.parse("801.604"), accounts.balance(ALICE));
        assertEquals(Money.parse("1000000.396"), pool.balance());
        assertTrue(orders.open(ALICE).isEmpty());
        Order done = orders.history(ALICE).get(0);
        assertEquals(OrderStatus.FILLED, done.status());
        assertEquals(990, done.fillPrice());
        assertEquals(Money.parse("0.396"), done.fee());
        assertEquals(Money.ZERO, done.frozen());
        assertEquals(720, done.closedSeconds());
        // 成交记录里也有一笔
        PlayerTrade trade = book.trades(ALICE).get(0);
        assertEquals(990, trade.price());
        assertEquals(Money.parse("0.396"), trade.fee());
        // 结构化事件
        assertEquals(1, events.size());
        OrderEvent event = events.get(0);
        assertEquals(new OrderEvent(ALICE, OrderEvent.Kind.FILLED, done.id(), "DIAMONDCRP", Side.LONG, 990, 2000,
                Money.parse("0.396"), 720, CancelReason.NONE), event);
    }

    @Test
    @DisplayName("一个行情步跳过了委托价（跳空）：仍按委托价成交")
    void gapStillFillsAtLimitPrice() {
        deposit("1000");
        restingBuy(990);

        orders.onSnapshot(DIAMOND, snapshot(DIAMOND, 900, 120));

        assertEquals(990, book.position(ALICE, "DIAMONDCRP").orElseThrow().entryPrice());
        assertEquals(990, orders.history(ALICE).get(0).fillPrice());
    }

    @Test
    @DisplayName("可成交买价恰等于委托价算穿越；还没到委托价则继续挂着")
    void touchFillsAndNotYetKeepsResting() {
        deposit("1000");
        restingBuy(990);

        assertTrue(orders.onSnapshot(DIAMOND, snapshot(DIAMOND, 995, 110)).isEmpty());
        assertEquals(1, orders.open(ALICE).size());

        // 最新价 989.75 → 可成交买价 990.0，恰等于委托价
        assertEquals(1, orders.onSnapshot(DIAMOND, snapshot(DIAMOND, 989.75, 120)).size());
        assertTrue(orders.open(ALICE).isEmpty());
    }

    @Test
    @DisplayName("别的合约的行情步不会触发委托")
    void otherSymbolDoesNotTrigger() {
        deposit("1000");
        restingBuy(990);

        assertTrue(orders.onSnapshot(GUNPOWDER, snapshot(GUNPOWDER, 1, 120)).isEmpty());
        assertEquals(1, orders.open(ALICE).size());
    }

    @Test
    @DisplayName("限价卖单（开空）：可成交卖价不低于委托价时按委托价成交")
    void sellLimitFillsAtLimitPrice() {
        deposit("1000");

        // 最新价 1000，委托价 1010：名义价值 2020，保证金 202，maker 手续费 0.404
        Order order = placed(orders.placeLimit(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.SHORT, 2000, 10, 1010));
        assertEquals(Money.parse("202.404"), order.frozen());

        // 最新价 1010.5 → 可成交卖价 1010.25 ≥ 1010
        orders.onSnapshot(DIAMOND, snapshot(DIAMOND, 1010.5, 120));

        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(Side.SHORT, position.side());
        assertEquals(1010, position.entryPrice());
        assertEquals(Money.parse("202"), position.margin());
        assertEquals(Money.parse("1000000.404"), pool.balance());
    }

    @Test
    @DisplayName("下单时就能成交：按当前可成交价成交，收 taker 费，进历史委托")
    void immediatelyFillableOrderTakes() {
        deposit("1000");

        // 最新价 1000，可成交买价 1000.25 ≤ 委托价 1001：名义价值 2000.5，保证金 200.05，taker 手续费 1.00025
        TradeResult result = orders.placeLimit(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 2000, 10, 1001);

        PlayerTrade trade = assertInstanceOf(TradeResult.Filled.class, result).trade();
        assertEquals(1000.25, trade.price());
        assertEquals(Money.parse("1.00025"), trade.fee());
        assertEquals(Money.parse("798.94975"), accounts.balance(ALICE));
        assertTrue(orders.open(ALICE).isEmpty());
        Order done = orders.history(ALICE).get(0);
        assertEquals(OrderStatus.FILLED, done.status());
        assertEquals(1001, done.price());
        assertEquals(1000.25, done.fillPrice());
        assertEquals(Money.parse("1.00025"), done.fee());
    }

    @Test
    @DisplayName("滑点：第一档（卖一）穿越委托价就成交；下单即成交的价格不比委托价差，委托价够高才按曲线均价成交（taker）")
    void bestLevelDecidesWhetherOrderCrosses() {
        deposit("1000");
        MarketSnapshot at = snapshot(DEEP_DIAMOND, 1000);
        // 卖一 1000.25；2.000 个 = 4 档（每档 500 手），相邻档差约 0.0526，均价约 1000.33
        double average = TradingBook.fillPrice(DEEP_DIAMOND, at, Side.LONG, TradeKind.OPEN, 2000);
        assertTrue(average > 1000.30 && average < 1000.40, "均价应约 1000.33：" + average);

        // 委托价 1000.30 高于卖一但低于均价：第一档已穿越，立即成交，但成交价封顶在委托价（不比限价差）
        TradeResult capped = orders.placeLimit(ALICE, DEEP_DIAMOND, at, Side.LONG, 2000, 10, 1000.30);
        assertEquals(1000.30, assertInstanceOf(TradeResult.Filled.class, capped).trade().price());

        // 委托价 1000.5 覆盖均价：按曲线均价成交，和同数量市价单同一个成交价
        TradeResult taken = orders.placeLimit(ALICE, DEEP_DIAMOND, at, Side.LONG, 2000, 10, 1000.5);
        assertEquals(average, assertInstanceOf(TradeResult.Filled.class, taken).trade().price());

        // 委托价 1000.2 低于卖一：挂着
        TradeResult resting = orders.placeLimit(ALICE, DEEP_DIAMOND, at, Side.LONG, 2000, 10, 1000.20);
        assertInstanceOf(TradeResult.Placed.class, resting);
    }

    @Test
    @DisplayName("滑点合约：卖一低于委托价就成交，不看整单均价（火药 0.3% 滑点，1 万 CRP 买入限价 99.00，最新价 98.95）")
    void restingBuyFillsOnceBestAskCrosses() {
        ContractSpec deep = ContractSpec.defaults().get(1); // 火药：点差 0.05、0 位精度、滑点 0.3%
        deposit("100000");
        // 最新价 100 时挂：卖一 100.025 高于 99，挂着。101 手约 1 万 CRP
        Order order = placed(orders.placeLimit(ALICE, deep, snapshot(deep, 100), Side.LONG, 101, 10, 99.00));

        // 最新价 98.95：卖一 98.975 已低于委托价；整单均价约 99.27 高于 99，但只看卖一就该成交
        List<OrderEvent> events = orders.onSnapshot(deep, snapshot(deep, 98.95, 120));

        assertEquals(1, events.size());
        assertEquals(OrderEvent.Kind.FILLED, events.get(0).kind());
        assertEquals(order.id(), events.get(0).orderId());
        assertEquals(99.00, events.get(0).price());
        assertEquals(99.00, book.position(ALICE, "GUNPOWDERCRP").orElseThrow().entryPrice());
    }

    @Test
    @DisplayName("滑点合约：卖出限价同理，买一高于委托价就成交；只减仓限价单也按第一档判断")
    void sellAndReduceOnlyUseBestLevel() {
        ContractSpec deep = ContractSpec.defaults().get(1);
        deposit("100000");
        Order sell = placed(orders.placeLimit(ALICE, deep, snapshot(deep, 100), Side.SHORT, 101, 10, 101.00));
        // 买一 = 最新价 − 0.025：最新价 101.05 时买一 101.025 ≥ 101，整单均价约 100.7 < 101，仍应成交
        List<OrderEvent> events = orders.onSnapshot(deep, snapshot(deep, 101.05, 120));
        assertEquals(1, events.size());
        assertEquals(sell.id(), events.get(0).orderId());
        assertEquals(OrderEvent.Kind.FILLED, events.get(0).kind());

        // 空仓的只减仓限价买单（买入平空）：挂在 100.00，卖一随最新价 99.95 降到 99.975 ≤ 100 就成交
        Order close = placed(orders.placeReduceOnlyLimit(ALICE, deep, snapshot(deep, 101.05, 121), 101, 100.00));
        List<OrderEvent> closed = orders.onSnapshot(deep, snapshot(deep, 99.95, 122));
        assertEquals(1, closed.size());
        assertEquals(close.id(), closed.get(0).orderId());
        assertEquals(OrderEvent.Kind.FILLED, closed.get(0).kind());
        assertTrue(book.position(ALICE, "GUNPOWDERCRP").isEmpty());
    }

    @Test
    @DisplayName("撤单：释放冻结资金，可用余额恢复，进历史委托；别人的或不存在的委托撤不了")
    void cancelReleasesFrozenFunds() {
        deposit("1000");
        Order order = restingBuy(990);

        assertEquals(Optional.empty(), orders.cancel(BOB, order.id(), 700));
        assertEquals(Optional.empty(), orders.cancel(ALICE, 99, 700));
        assertEquals(Money.parse("801.604"), accounts.balance(ALICE));

        OrderEvent event = orders.cancel(ALICE, order.id(), 700).orElseThrow();

        assertEquals(Money.parse("1000"), accounts.balance(ALICE));
        assertEquals(new OrderEvent(ALICE, OrderEvent.Kind.CANCELLED, order.id(), "DIAMONDCRP", Side.LONG, 990, 2000,
                Money.parse("198.396"), 700, CancelReason.USER), event);
        Order done = orders.history(ALICE).get(0);
        assertEquals(OrderStatus.CANCELLED, done.status());
        assertEquals(CancelReason.USER, done.cancelReason());
        assertTrue(orders.open(ALICE).isEmpty());
        // 撤销后价格穿越也不再成交
        assertTrue(orders.onSnapshot(DIAMOND, snapshot(DIAMOND, 900, 130)).isEmpty());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        assertEquals(Optional.empty(), orders.cancel(ALICE, order.id(), 700));
    }

    @Test
    @DisplayName("可用余额不够冻结时拒单，不留委托")
    void insufficientBalanceIsRejected() {
        deposit("100");

        TradeResult result = orders.placeLimit(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 2000, 10, 990);

        assertEquals(TradeResult.Reason.INSUFFICIENT_BALANCE, rejection(result));
        assertEquals(Money.parse("100"), accounts.balance(ALICE));
        assertTrue(orders.open(ALICE).isEmpty());
        assertTrue(orders.history(ALICE).isEmpty());
    }

    @Test
    @DisplayName("冻结的资金不能再用于其他开仓")
    void frozenFundsAreNotSpendable() {
        deposit("300");
        restingBuy(990); // 冻结 198.396，可用 101.604

        TradeResult result = book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 2000, 10);

        assertEquals(TradeResult.Reason.INSUFFICIENT_BALANCE, rejection(result));
    }

    @Test
    @DisplayName("参数不合法：数量、杠杆、价格、反向仓位、杠杆不一致都拒单")
    void invalidOrdersAreRejected() {
        deposit("100000");
        MarketSnapshot at = snapshot(DIAMOND, 1000);

        assertEquals(TradeResult.Reason.INVALID_QUANTITY, rejection(orders.placeLimit(ALICE, DIAMOND, at, Side.LONG, 0, 10, 990)));
        assertEquals(TradeResult.Reason.INVALID_LEVERAGE, rejection(orders.placeLimit(ALICE, DIAMOND, at, Side.LONG, 1, 126, 990)));
        assertEquals(TradeResult.Reason.INVALID_PRICE, rejection(orders.placeLimit(ALICE, DIAMOND, at, Side.LONG, 1, 10, 0)));
        assertEquals(TradeResult.Reason.INVALID_PRICE, rejection(orders.placeLimit(ALICE, DIAMOND, at, Side.LONG, 1, 10, Double.NaN)));

        book.open(ALICE, DIAMOND, at, Side.LONG, 1000, 10);
        assertEquals(TradeResult.Reason.OPPOSITE_POSITION, rejection(orders.placeLimit(ALICE, DIAMOND, at, Side.SHORT, 1, 10, 1100)));
        assertEquals(TradeResult.Reason.LEVERAGE_MISMATCH, rejection(orders.placeLimit(ALICE, DIAMOND, at, Side.LONG, 1, 5, 990)));
        assertTrue(orders.open(ALICE).isEmpty());
    }

    @Test
    @DisplayName("每名玩家最多同时挂 20 张委托")
    void openOrdersAreCapped() {
        deposit("100000");
        MarketSnapshot at = snapshot(GUNPOWDER, 100);
        for (int i = 0; i < OrderBook.MAX_OPEN_ORDERS_PER_PLAYER; i++) {
            placed(orders.placeLimit(ALICE, GUNPOWDER, at, Side.LONG, 1, 5, 50));
        }

        assertEquals(TradeResult.Reason.TOO_MANY_ORDERS,
                rejection(orders.placeLimit(ALICE, GUNPOWDER, at, Side.LONG, 1, 5, 50)));
    }

    @Test
    @DisplayName("触发时开仓条件已不满足（其间开了反向仓位）：撤单并释放冻结资金，不动仓位")
    void fillRejectedCancelsAndReleases() {
        deposit("1000");
        Order order = restingBuy(990);
        // 挂单之后又市价开空
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.SHORT, 1000, 10);
        Money before = accounts.balance(ALICE);

        List<OrderEvent> events = orders.onSnapshot(DIAMOND, snapshot(DIAMOND, 980, 120));

        assertEquals(1, events.size());
        assertEquals(OrderEvent.Kind.CANCELLED, events.get(0).kind());
        assertEquals(CancelReason.FILL_REJECTED, events.get(0).cancelReason());
        assertEquals(order.frozen(), events.get(0).amount());
        assertEquals(before.plus(order.frozen()), accounts.balance(ALICE));
        assertEquals(Side.SHORT, book.position(ALICE, "DIAMONDCRP").orElseThrow().side());
        assertEquals(OrderStatus.CANCELLED, orders.history(ALICE).get(0).status());
    }

    @Test
    @DisplayName("同一个行情步里多张委托按编号先后成交")
    void multipleOrdersFillInIdOrder() {
        deposit("5000");
        orders.placeLimit(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 1000, 10, 990);
        orders.placeLimit(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 1000, 10, 980);

        List<OrderEvent> events = orders.onSnapshot(DIAMOND, snapshot(DIAMOND, 900, 120));

        assertEquals(List.of(1L, 2L), events.stream().map(OrderEvent::orderId).toList());
        // 1.000 @990 与 1.000 @980 加权：均价 985，保证金 99 + 98
        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(2000, position.lots());
        assertEquals(985, position.entryPrice());
        assertEquals(Money.parse("197"), position.margin());
    }

    @Test
    @DisplayName("委托与历史委托随状态导出、恢复；恢复后接着编号，挂单照常触发")
    void ordersSurviveRestore() {
        deposit("1000");
        Order resting = restingBuy(990);
        Order cancelled = restingBuy(980);
        orders.cancel(ALICE, cancelled.id(), 650);

        TradingState state = book.state();
        TradingBook restored = TradingBook.restore(accounts, state);

        assertEquals(List.of(resting), restored.orders().open(ALICE));
        assertEquals(1, restored.orders().history(ALICE).size());
        assertEquals(CancelReason.USER, restored.orders().history(ALICE).get(0).cancelReason());

        restored.orders().onSnapshot(DIAMOND, snapshot(DIAMOND, 900, 120));
        assertEquals(990, restored.position(ALICE, "DIAMONDCRP").orElseThrow().entryPrice());
        Order next = placed(restored.orders().placeLimit(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 1000, 10, 900));
        assertEquals(3, next.id());
    }

    @Test
    @DisplayName("旧存档（没有委托）的状态可以恢复成没有委托的账")
    void legacyStateHasNoOrders() {
        TradingState legacy = new TradingState(LiquidityPool.INITIAL_BALANCE, 0, java.util.Map.of(), java.util.Map.of());

        TradingBook restored = TradingBook.restore(accounts, legacy);

        assertTrue(restored.orders().open(ALICE).isEmpty());
        assertTrue(restored.orders().history(ALICE).isEmpty());
    }

    @Test
    @DisplayName("历史委托每人最多保留 100 条最近的")
    void historyIsCapped() {
        deposit("100000");
        for (int i = 0; i < OrderBook.MAX_HISTORY_PER_PLAYER + 5; i++) {
            Order order = placed(orders.placeLimit(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100), Side.LONG, 1, 5, 50));
            orders.cancel(ALICE, order.id(), 700);
        }

        List<Order> history = orders.history(ALICE);
        assertEquals(OrderBook.MAX_HISTORY_PER_PLAYER, history.size());
        assertEquals(OrderBook.MAX_HISTORY_PER_PLAYER + 5, history.get(history.size() - 1).id());
    }

    @Test
    @DisplayName("随机挂单、撤单、行情步、市价单、强平（含穿仓、撤销挂单）：可用余额 + 冻结 + 仓位保证金 + 池子 + 基金 的总额不变")
    void totalIsConserved() {
        Random random = new Random(11);
        UUID[] players = {ALICE, BOB, new UUID(0, 3)};
        for (UUID player : players) {
            accounts.deposit(player, Money.parse("5000"));
        }
        Money expected = Money.parse("1015000");
        ContractSpec[] specs = {DIAMOND, GUNPOWDER};
        int fills = 0;
        int cancels = 0;
        int liquidations = 0;
        int liquidatedOrders = 0;
        long step = 100;

        for (int i = 0; i < 3000; i++) {
            UUID player = players[random.nextInt(players.length)];
            ContractSpec spec = specs[random.nextInt(specs.length)];
            double last = spec.initialPrice() * (0.9 + random.nextDouble() * 0.2);
            MarketSnapshot at = snapshot(spec, last, step);
            // 数量按合约大小取，让名义价值都在几千以内，余额够用、委托才能成交
            int maxLots = spec == DIAMOND ? 2000 : 20;
            if (random.nextInt(10) == 0) {
                // 价格暴涨暴跌：10 倍杠杆的仓位被强平，同合约的挂单一并撤销；账被清空的玩家补钱（开发命令式调账，总额同步调整）
                double diamond = DIAMOND.initialPrice() * (0.6 + random.nextDouble() * 0.8);
                double gunpowder = GUNPOWDER.initialPrice() * (0.6 + random.nextDouble() * 0.8);
                for (LiquidationEvent event : book.checkLiquidations(
                        symbol -> symbol.equals("DIAMONDCRP") ? diamond : gunpowder,
                        symbol -> symbol.equals("DIAMONDCRP") ? DIAMOND.marginTiers() : GUNPOWDER.marginTiers(),
                        step * 6)) {
                    liquidations++;
                    liquidatedOrders += event.cancelledOrders().size();
                }
                for (UUID p : players) {
                    if (accounts.balance(p).compareTo(Money.parse("1000")) < 0) {
                        accounts.deposit(p, Money.parse("5000"));
                        expected = expected.plus(Money.parse("5000"));
                    }
                }
            }
            switch (random.nextInt(4)) {
                case 0 -> orders.placeLimit(player, spec, at, random.nextBoolean() ? Side.LONG : Side.SHORT,
                        1 + random.nextInt(maxLots), 10,
                        spec.initialPrice() * (0.9 + random.nextDouble() * 0.2));
                case 1 -> {
                    List<Order> open = orders.open(player);
                    if (!open.isEmpty()) {
                        orders.cancel(player, open.get(random.nextInt(open.size())).id(), step * 6);
                        cancels++;
                    }
                }
                case 2 -> {
                    step++;
                    fills += orders.onSnapshot(spec, snapshot(spec, last, step)).size();
                }
                default -> {
                    if (random.nextBoolean()) {
                        book.open(player, spec, at, random.nextBoolean() ? Side.LONG : Side.SHORT,
                                1 + random.nextInt(maxLots / 4), 10);
                    } else {
                        long held = book.position(player, spec.symbol()).map(Position::lots).orElse(1L);
                        book.close(player, spec, at, 1 + random.nextInt((int) held));
                    }
                }
            }
            assertEquals(expected, total(players), "第 " + i + " 步之后总额变了");
        }
        assertTrue(fills > 50, "应有足够多的委托成交：" + fills);
        assertTrue(cancels > 50, "应有足够多的主动撤单：" + cancels);
        assertTrue(liquidations > 10, "应有足够多的强平：" + liquidations);
        assertTrue(liquidatedOrders > 0, "强平时应撤销过挂单：" + liquidatedOrders);
    }

    private Money total(UUID[] players) {
        Money total = pool.balance().plus(book.fund().balance());
        for (UUID player : players) {
            total = total.plus(accounts.balance(player)).plus(orders.frozen(player));
            for (Position position : book.positions(player)) {
                total = total.plus(position.margin());
            }
        }
        return total;
    }
}
