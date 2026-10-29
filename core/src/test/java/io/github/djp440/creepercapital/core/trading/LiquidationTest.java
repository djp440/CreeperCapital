package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarginTiers;
import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 强平（#12）：保证金率达到 100% 时按标记价格平仓、扣清算费，剩余保证金和清算费进风险保障基金，穿仓先由基金、再由池子承担。
 * 钻石：点差 0.5、数量精度 3 位（1 手 = 0.001 个）、125x，无限深（成交价恰好是最新价 ± 半个点差）；火药：点差 0.05、数量精度 0 位、50x。
 * 期望值全部手算。2.000 个钻石在最新价 1000 开多：成交价 1000.25，名义价值 2000.5，10 倍保证金 200.05，手续费 1.00025；
 * 第一档（名义价值 ≤ 5000）维持保证金率 0.4%、速算额 0，所以逐仓强平价 = 1800.45 / 1.992 ≈ 903.825。
 */
class LiquidationTest {
    private static final ContractSpec DIAMOND = unlimitedDepth(ContractSpec.defaults().get(0));
    private static final ContractSpec GUNPOWDER = unlimitedDepth(ContractSpec.defaults().get(1));
    private static final UUID ALICE = new UUID(0, 1);
    private static final long SECONDS = 600;
    private static final Function<String, MarginTiers> TIERS =
            symbol -> symbol.equals("DIAMONDCRP") ? DIAMOND.marginTiers() : GUNPOWDER.marginTiers();

    private AccountBook accounts;
    private LiquidityPool pool;
    private TradingBook book;

    @BeforeEach
    void setUp() {
        accounts = new AccountBook();
        pool = new LiquidityPool();
        book = new TradingBook(accounts, pool);
    }

    private static ContractSpec unlimitedDepth(ContractSpec spec) {
        return new ContractSpec(spec.symbol(), spec.underlying(), spec.initialPrice(), spec.baseVolume(), spec.volatility(),
                spec.spread(), spec.maxLeverage(), spec.quantityDecimals(), 0.0);
    }

    private static MarketSnapshot snapshot(ContractSpec spec, double last) {
        return new MarketSnapshot(100, spec.symbol(), last, last, last, 0.0001, 0);
    }

    private static ToDoubleFunction<String> marks(double diamond, double gunpowder) {
        return symbol -> symbol.equals("DIAMONDCRP") ? diamond : gunpowder;
    }

    private void deposit(String amount) {
        accounts.deposit(ALICE, Money.parse(amount));
    }

    private void open(ContractSpec spec, Side side, long lots, int leverage, double last) {
        assertInstanceOf(TradeResult.Filled.class,
                book.open(ALICE, spec, snapshot(spec, last), side, lots, leverage, marks(1000, 100)));
    }

    private void openCross(ContractSpec spec, Side side, long lots, int leverage, double last) {
        book.setMarginMode(ALICE, spec.symbol(), MarginMode.CROSS);
        open(spec, side, lots, leverage, last);
    }

    private List<LiquidationEvent> check(double diamond, double gunpowder) {
        return book.checkLiquidations(marks(diamond, gunpowder), TIERS, SECONDS);
    }

    /** 2.000 个钻石在 1000 逐仓 10x 开多：保证金 200.05，可用余额 1000 − 200.05 − 1.00025 = 798.94975。 */
    private void isolatedLong() {
        deposit("1000");
        open(DIAMOND, Side.LONG, 2000, 10, 1000);
    }

    // ---- 逐仓 ----

    @Test
    @DisplayName("逐仓：保证金率没到 100% 不动，到了就在这一步按标记价格平仓，剩余保证金全部进基金")
    void isolatedLongIsLiquidatedAtTheMarkPrice() {
        isolatedLong();

        assertTrue(check(903.9, 100).isEmpty(), "保证金率 0.984，还没到强平线");
        assertTrue(book.position(ALICE, "DIAMONDCRP").isPresent());

        List<LiquidationEvent> events = check(903.8, 100);

        // 903.8：盈亏 (903.8 − 1000.25) × 2 = −192.9，保证金余额 200.05 − 192.9 = 7.15，清算费 0.5% × 1807.6 = 9.038 超过余额，只收 7.15
        assertEquals(1, events.size());
        LiquidationEvent event = events.get(0);
        assertEquals(ALICE, event.player());
        assertEquals(MarginMode.ISOLATED, event.mode());
        assertEquals(SECONDS, event.marketSeconds());
        assertEquals(1, event.positions().size());
        LiquidationEvent.Liquidated closed = event.positions().get(0);
        assertEquals("DIAMONDCRP", closed.symbol());
        assertEquals(Side.LONG, closed.side());
        assertEquals(2000, closed.lots());
        assertEquals(903.8, closed.price());
        assertEquals(Money.parse("-192.9"), closed.realizedPnl());
        assertEquals(Money.parse("7.15"), closed.fee());
        assertEquals(Money.parse("200.05"), event.collateralLost());
        assertEquals(Money.parse("7.15"), event.toFund());
        assertEquals(Money.ZERO, event.shortfall());

        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        assertEquals(Money.parse("798.94975"), accounts.balance(ALICE), "逐仓强平不动可用余额");
        assertEquals(Money.parse("1000193.90025"), pool.balance(), "池子收到 1.00025 开仓费和 192.9 的亏损");
        assertEquals(Money.parse("7.15"), book.fund().balance());

        PlayerTrade trade = book.trades(ALICE).get(book.trades(ALICE).size() - 1);
        assertEquals(TradeKind.LIQUIDATION, trade.kind());
        assertEquals(closed.tradeId(), trade.id());
        assertEquals(Side.LONG, trade.side());
        assertEquals(903.8, trade.price());
        assertEquals(Money.parse("7.15"), trade.fee());
        assertEquals(Money.parse("-192.9"), trade.realizedPnl());
    }

    @Test
    @DisplayName("逐仓做空：价格涨到强平线同样触发")
    void isolatedShortIsLiquidatedWhenThePriceRises() {
        deposit("1000");
        // 2.000 个钻石在 1000 开空：成交价 999.75，名义价值 1999.5，保证金 199.95，手续费 0.99975
        open(DIAMOND, Side.SHORT, 2000, 10, 1000);

        assertTrue(check(1095.3, 100).isEmpty(), "保证金率 0.990");
        List<LiquidationEvent> events = check(1095.4, 100);

        // 盈亏 (999.75 − 1095.4) × 2 = −191.3，保证金余额 8.65，清算费 0.5% × 2190.8 = 10.954 超过余额，只收 8.65
        assertEquals(1, events.size());
        assertEquals(Money.parse("-191.3"), events.get(0).positions().get(0).realizedPnl());
        assertEquals(Money.parse("8.65"), events.get(0).toFund());
        assertEquals(Money.parse("8.65"), book.fund().balance());
        assertEquals(Money.parse("1000192.29975"), pool.balance());
    }

    @Test
    @DisplayName("清算费按标记价格的名义价值收 0.5%，但不超过保证金余额")
    void feeIsCappedAtTheRemainingMargin() {
        deposit("20000");
        // 火药 0 位精度：400 手在 100 开多，成交价 100.025，名义价值 40010，20 倍保证金 2000.5；落在第三档（≤100000）：费率 2.5%，速算额 325
        open(GUNPOWDER, Side.LONG, 400, 20, 100);

        // 标记价 95.5：名义价值 38200，维持保证金 38200 × 2.5% − 325 = 630；保证金余额 2000.5 + (95.5 − 100.025) × 400 = 190.5 < 630，已强平
        // 盈亏 −1810，剩余 190.5；清算费 0.5% × 38200 = 191 超过剩余，封顶 190.5。费用不封顶的情形见下一个用例
        List<LiquidationEvent> events = check(1000, 95.5);
        assertEquals(1, events.size());
        assertEquals(Money.parse("-1810"), events.get(0).positions().get(0).realizedPnl());
        assertEquals(Money.parse("190.5"), events.get(0).positions().get(0).fee());
        assertEquals(Money.parse("190.5"), events.get(0).toFund());
    }

    @Test
    @DisplayName("清算费不封顶的情形：费用单独计在成交上，进基金的总额仍是全部剩余保证金")
    void feeBelowTheRemainingMarginIsReportedInFull() {
        deposit("20000");
        open(GUNPOWDER, Side.LONG, 400, 20, 100);
        // 火药 400 手 100 开多，保证金 2000.5；标记价 96.5：名义价值 38600，维持保证金 38600 × 2.5% − 325 = 640，
        // 保证金余额 2000.5 + (96.5 − 100.025) × 400 = 590.5 < 640，强平；盈亏 −1410，剩余 590.5，清算费 0.5% × 38600 = 193 < 590.5
        List<LiquidationEvent> events = check(1000, 96.5);

        assertEquals(1, events.size());
        LiquidationEvent event = events.get(0);
        assertEquals(Money.parse("193"), event.positions().get(0).fee());
        assertEquals(Money.parse("590.5"), event.toFund(), "清算费和剩余保证金一起进基金");
        assertEquals(Money.parse("590.5"), book.fund().balance());
        assertEquals(Money.parse("2000.5"), event.collateralLost());
    }

    // ---- 穿仓 ----

    @Test
    @DisplayName("穿仓：基金是空的，亏损超出保证金的部分由池子承担（池子少收），玩家余额不动也不为负")
    void shortfallIsBorneByThePoolWhenTheFundIsEmpty() {
        isolatedLong();

        // 800：盈亏 (800 − 1000.25) × 2 = −400.5，保证金 200.05，穿仓 200.45
        List<LiquidationEvent> events = check(800, 100);

        LiquidationEvent event = events.get(0);
        assertEquals(Money.parse("-400.5"), event.positions().get(0).realizedPnl());
        assertEquals(Money.ZERO, event.positions().get(0).fee());
        assertEquals(Money.ZERO, event.toFund());
        assertEquals(Money.parse("200.45"), event.shortfall());
        assertEquals(Money.ZERO, event.fundCovered());
        assertEquals(Money.parse("200.45"), event.poolBorne());
        assertEquals(Money.parse("798.94975"), accounts.balance(ALICE));
        assertEquals(Money.parse("1000201.05025"), pool.balance(), "池子只收到 200.05 的保证金，少收 200.45");
        assertEquals(Money.ZERO, book.fund().balance());
    }

    @Test
    @DisplayName("穿仓：基金先承担，不够的部分才轮到池子")
    void theFundCoversTheShortfallBeforeThePool() {
        isolatedLong();
        book.fund().receive(Money.parse("100"));

        // 800：穿仓 200.45，基金出 100，池子承担 100.45
        LiquidationEvent event = check(800, 100).get(0);

        assertEquals(Money.parse("200.45"), event.shortfall());
        assertEquals(Money.parse("100"), event.fundCovered());
        assertEquals(Money.parse("100.45"), event.poolBorne());
        assertEquals(Money.ZERO, book.fund().balance());
        assertEquals(Money.parse("1000301.05025"), pool.balance(), "池子收到 200.05 保证金 + 基金的 100");
    }

    @Test
    @DisplayName("穿仓金额小于基金余额时全部由基金承担，池子的收入和正常平仓一样多")
    void aSmallShortfallIsEntirelyCoveredByTheFund() {
        isolatedLong();
        book.fund().receive(Money.parse("100"));

        // 897.725：盈亏 (897.725 − 1000.25) × 2 = −205.05，保证金 200.05，穿仓 5
        LiquidationEvent event = check(897.725, 100).get(0);

        assertEquals(Money.parse("5"), event.shortfall());
        assertEquals(Money.parse("5"), event.fundCovered());
        assertEquals(Money.ZERO, event.poolBorne());
        assertEquals(Money.parse("95"), book.fund().balance());
        assertEquals(Money.parse("1000206.05025"), pool.balance(), "池子拿到全部 205.05 的盈利 + 1.00025 开仓费");
    }

    // ---- 全仓 ----

    @Test
    @DisplayName("全仓：保证金率按整个账户算，到 100% 时全仓钱包（含可用余额）全部结算，剩余进基金")
    void crossLiquidationSettlesTheWholeCrossWallet() {
        deposit("1000");
        openCross(DIAMOND, Side.LONG, 2000, 10, 1000);
        // 全仓钱包 = 可用 798.94975 + 保证金 200.05 = 998.99975；强平价 = 1001.50025 / 1.992 ≈ 502.76

        assertTrue(check(502.8, 100).isEmpty(), "保证金率 0.981");
        List<LiquidationEvent> events = check(502.7, 100);

        // 盈亏 (502.7 − 1000.25) × 2 = −995.1，剩余 3.89975，清算费 0.5% × 1005.4 = 5.027 超过剩余，只收 3.89975
        assertEquals(1, events.size());
        LiquidationEvent event = events.get(0);
        assertEquals(MarginMode.CROSS, event.mode());
        assertEquals(Money.parse("998.99975"), event.collateralLost());
        assertEquals(Money.parse("-995.1"), event.positions().get(0).realizedPnl());
        assertEquals(Money.parse("3.89975"), event.positions().get(0).fee());
        assertEquals(Money.parse("3.89975"), event.toFund());
        assertEquals(Money.ZERO, accounts.balance(ALICE), "全仓强平后全仓钱包归零");
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        assertEquals(Money.parse("1000996.10025"), pool.balance());
        assertEquals(Money.parse("3.89975"), book.fund().balance());
    }

    @Test
    @DisplayName("全仓穿仓：亏损超出全仓钱包的部分先由基金、再由池子承担，玩家余额归零而不是为负")
    void crossShortfallNeverMakesTheBalanceNegative() {
        deposit("1000");
        openCross(DIAMOND, Side.LONG, 2000, 10, 1000);

        // 400：盈亏 (400 − 1000.25) × 2 = −1200.5，全仓钱包 998.99975，穿仓 201.50025
        LiquidationEvent event = check(400, 100).get(0);

        assertEquals(Money.parse("201.50025"), event.shortfall());
        assertEquals(Money.ZERO, event.fundCovered());
        assertEquals(Money.parse("201.50025"), event.poolBorne());
        assertEquals(Money.ZERO, accounts.balance(ALICE));
        assertEquals(Money.parse("1001000"), pool.balance(), "池子收到 1.00025 开仓费 + 998.99975 钱包");
    }

    @Test
    @DisplayName("全仓所有仓位共用一个保证金率：一起强平，一次事件，每个仓位各记一笔强平成交")
    void allCrossPositionsAreLiquidatedTogether() {
        deposit("1000");
        // 1 个钻石在 1000 开多 10x：成交价 1000.25，保证金 100.025，手续费 0.500125
        openCross(DIAMOND, Side.LONG, 1000, 10, 1000);
        // 10 个火药在 100 开多 5x：成交价 100.025，保证金 200.05，手续费 0.500125
        openCross(GUNPOWDER, Side.LONG, 10, 5, 100);

        // 钻石 500、火药 50：盈亏各 −500.25；全仓钱包 998.99975，余额 −1.50025 < 0，保证金率无穷大
        assertTrue(check(900, 90).isEmpty(), "两个仓位合计浮亏 200.5，远没到强平线");
        List<LiquidationEvent> events = check(500, 50);

        assertEquals(1, events.size());
        LiquidationEvent event = events.get(0);
        assertEquals(List.of("DIAMONDCRP", "GUNPOWDERCRP"), event.symbols());
        assertEquals(Money.parse("-500.25"), event.positions().get(0).realizedPnl());
        assertEquals(Money.parse("-500.25"), event.positions().get(1).realizedPnl());
        assertEquals(Money.parse("1.50025"), event.shortfall());
        assertTrue(book.positions(ALICE).isEmpty());
        assertEquals(Money.ZERO, accounts.balance(ALICE));
        long liquidations = book.trades(ALICE).stream().filter(t -> t.kind() == TradeKind.LIQUIDATION).count();
        assertEquals(2, liquidations);
    }

    @Test
    @DisplayName("逐仓强平不牵连同一玩家的全仓仓位和可用余额")
    void isolatedLiquidationLeavesTheCrossAccountAlone() {
        deposit("1000");
        openCross(DIAMOND, Side.LONG, 1000, 10, 1000);
        // 火药逐仓：10 个 5x，保证金 200.05
        open(GUNPOWDER, Side.LONG, 10, 5, 100);

        // 火药崩到 10：盈亏 (10 − 100.025) × 10 = −900.25，穿仓 700.2；钻石在 1000，几乎没亏
        List<LiquidationEvent> events = check(1000, 10);

        assertEquals(1, events.size());
        assertEquals(MarginMode.ISOLATED, events.get(0).mode());
        assertEquals(List.of("GUNPOWDERCRP"), events.get(0).symbols());
        assertEquals(Money.parse("700.2"), events.get(0).shortfall());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isPresent());
        assertEquals(Money.parse("698.92475"), accounts.balance(ALICE));
    }

    // ---- 挂单 ----

    @Test
    @DisplayName("逐仓强平撤销这个合约的挂单并退回冻结资金，其他合约的挂单不动")
    void isolatedLiquidationCancelsOnlyThatContractsOrders() {
        deposit("2000");
        open(DIAMOND, Side.LONG, 2000, 10, 1000);
        // 钻石 1000 手在 900 挂买单：冻结 90 + 0.18；火药 10 手在 50 挂买单：冻结 50 + 0.1
        book.orders().placeLimit(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 1000, 10, 900);
        book.orders().placeLimit(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100), Side.LONG, 10, 10, 50);
        assertEquals(Money.parse("1658.66975"), accounts.balance(ALICE));

        LiquidationEvent event = check(800, 100).get(0);

        assertEquals(1, event.cancelledOrders().size());
        OrderEvent cancelled = event.cancelledOrders().get(0);
        assertEquals(OrderEvent.Kind.CANCELLED, cancelled.kind());
        assertEquals(CancelReason.LIQUIDATED, cancelled.cancelReason());
        assertEquals("DIAMONDCRP", cancelled.symbol());
        assertEquals(Money.parse("90.18"), cancelled.amount());
        assertEquals(1, book.orders().open(ALICE).size());
        assertEquals("GUNPOWDERCRP", book.orders().open(ALICE).get(0).symbol());
        assertEquals(Money.parse("1748.84975"), accounts.balance(ALICE));
        assertEquals(CancelReason.LIQUIDATED, book.orders().history(ALICE).get(0).cancelReason());
    }

    @Test
    @DisplayName("全仓强平撤销全仓合约的挂单（冻结资金本来就计入全仓钱包），一并结算")
    void crossLiquidationCancelsCrossScopeOrders() {
        deposit("1000");
        openCross(DIAMOND, Side.LONG, 2000, 10, 1000);
        book.setMarginMode(ALICE, "GUNPOWDERCRP", MarginMode.CROSS);
        book.orders().placeLimit(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100), Side.LONG, 10, 10, 50);

        LiquidationEvent event = check(400, 100).get(0);

        // 全仓钱包仍是 998.99975（冻结的 50.1 也算在内），穿仓 1200.5 − 998.99975 = 201.50025
        assertEquals(1, event.cancelledOrders().size());
        assertTrue(book.orders().open(ALICE).isEmpty());
        assertEquals(Money.parse("998.99975"), event.collateralLost());
        assertEquals(Money.parse("201.50025"), event.shortfall());
        assertEquals(Money.ZERO, accounts.balance(ALICE));
        assertEquals(Money.ZERO, book.orders().frozen(ALICE));
    }

    @Test
    @DisplayName("全仓强平不动逐仓合约的挂单：它冻结的保证金不是全仓抵押，不被没收，挂单照旧")
    void crossLiquidationLeavesIsolatedOrdersAlone() {
        deposit("1000");
        openCross(DIAMOND, Side.LONG, 2000, 10, 1000);
        // 火药是逐仓（缺省）：10 手在 50 挂买单，冻结 50 + 0.1 = 50.1，可用余额 798.94975 − 50.1 = 748.84975
        Order resting = ((TradeResult.Placed) book.orders().placeLimit(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100),
                Side.LONG, 10, 10, 50)).order();

        LiquidationEvent event = check(400, 100).get(0);

        // 全仓抵押 = 钻石保证金 200.05 + 可用余额 748.84975 = 948.89975，不含逐仓挂单的 50.1；穿仓 1200.5 − 948.89975 = 251.60025
        assertEquals(MarginMode.CROSS, event.mode());
        assertTrue(event.cancelledOrders().isEmpty());
        assertEquals(Money.parse("948.89975"), event.collateralLost());
        assertEquals(Money.parse("251.60025"), event.shortfall());
        assertEquals(List.of(resting.id()), book.orders().open(ALICE).stream().map(Order::id).toList());
        assertEquals(Money.parse("50.1"), book.orders().frozen(ALICE));
        assertEquals(Money.ZERO, accounts.balance(ALICE));
    }

    // ---- 仓位页的预估强平价与实际触发一致 ----

    /** 强平价取自 MarginMath.assess（仓位页展示的就是这个数）；标记价格比它安全一点不触发，越过它一点就触发。 */
    private void assertTriggersExactlyAtTheDisplayedLiquidationPrice(MarginMode mode, Side side) {
        deposit("1000");
        if (mode == MarginMode.CROSS) {
            book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS);
        }
        open(DIAMOND, side, 2000, 10, 1000);
        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(mode, position.mode());
        double displayed = MarginMath.assess(book.positions(ALICE), book.assets(ALICE, marks(1000, 100)).walletBalance(),
                marks(1000, 100), TIERS).get("DIAMONDCRP").liquidationPrice().orElseThrow();
        double safe = side == Side.LONG ? displayed * (1 + 1e-6) : displayed * (1 - 1e-6);
        double hit = side == Side.LONG ? displayed * (1 - 1e-6) : displayed * (1 + 1e-6);

        assertTrue(check(safe, 100).isEmpty(), mode + " " + side + " 在展示的强平价 " + displayed + " 之内不应强平");
        List<LiquidationEvent> events = check(hit, 100);
        assertEquals(1, events.size(), mode + " " + side + " 越过展示的强平价 " + displayed + " 就应强平");
        assertEquals(hit, events.get(0).positions().get(0).price(), "按触发强平的标记价格成交");
    }

    @Test
    @DisplayName("预估强平价与实际触发强平的标记价格一致：逐仓多头")
    void displayedPriceMatchesTriggerIsolatedLong() {
        assertTriggersExactlyAtTheDisplayedLiquidationPrice(MarginMode.ISOLATED, Side.LONG);
    }

    @Test
    @DisplayName("预估强平价与实际触发强平的标记价格一致：逐仓空头")
    void displayedPriceMatchesTriggerIsolatedShort() {
        assertTriggersExactlyAtTheDisplayedLiquidationPrice(MarginMode.ISOLATED, Side.SHORT);
    }

    @Test
    @DisplayName("预估强平价与实际触发强平的标记价格一致：全仓多头")
    void displayedPriceMatchesTriggerCrossLong() {
        assertTriggersExactlyAtTheDisplayedLiquidationPrice(MarginMode.CROSS, Side.LONG);
    }

    @Test
    @DisplayName("预估强平价与实际触发强平的标记价格一致：全仓空头")
    void displayedPriceMatchesTriggerCrossShort() {
        assertTriggersExactlyAtTheDisplayedLiquidationPrice(MarginMode.CROSS, Side.SHORT);
    }

    // ---- 极端行情（jump） ----

    @Test
    @DisplayName("用行情跳跃制造极端下跌：逐仓多头穿仓，基金为空时池子承担，总额守恒")
    void marketJumpCausesAShortfall() {
        Market market = new Market(20260101L, ContractSpec.defaults());
        market.advanceSteps(5);
        MarketSnapshot before = market.snapshot("DIAMONDCRP");
        ContractSpec spec = market.spec("DIAMONDCRP");
        deposit("100000");
        assertInstanceOf(TradeResult.Filled.class,
                book.open(ALICE, spec, before, Side.LONG, 20000, 10, symbol -> before.markPrice()));
        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        Money total = totalOf(ALICE);

        assertTrue(book.checkLiquidations(symbol -> market.snapshot(symbol).markPrice(), s -> market.spec(s).marginTiers(),
                before.time().totalSeconds()).isEmpty(), "跳跃之前仓位是健康的");

        market.queueJump("DIAMONDCRP", -0.5);
        MarketSnapshot after = market.advanceSteps(1).stream().filter(s -> s.symbol().equals("DIAMONDCRP")).findFirst()
                .orElseThrow();
        assertTrue(after.markPrice() < position.entryPrice() * 0.6, "跳跃之后的标记价格 " + after.markPrice() + " 应已大幅下跌");

        List<LiquidationEvent> events = book.checkLiquidations(symbol -> market.snapshot(symbol).markPrice(),
                s -> market.spec(s).marginTiers(), after.time().totalSeconds());

        assertEquals(1, events.size());
        LiquidationEvent event = events.get(0);
        assertEquals(after.markPrice(), event.positions().get(0).price());
        assertTrue(event.shortfall().isPositive(), "跌幅远超保证金，应穿仓");
        assertEquals(event.shortfall(), event.poolBorne(), "基金为空，穿仓全由池子承担");
        assertEquals(total, totalOf(ALICE), "强平前后玩家 + 池子 + 基金总额不变");
        assertFalse(accounts.balance(ALICE).units() < 0);
    }

    // ---- 其他 ----

    @Test
    @DisplayName("没有标记价格的合约（已不在配置里）不参与强平检查")
    void positionsWithoutAMarkPriceAreSkipped() {
        isolatedLong();

        assertTrue(book.checkLiquidations(symbol -> Double.NaN, TIERS, SECONDS).isEmpty());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isPresent());
    }

    @Test
    @DisplayName("强平成交经过成交监听（全服最新成交广播挂在这里）")
    void liquidationTradesReachTheTradeListeners() {
        isolatedLong();
        List<PlayerTrade> seen = new ArrayList<>();
        book.addTradeListener(seen::add);

        check(800, 100);

        assertEquals(1, seen.size());
        assertEquals(TradeKind.LIQUIDATION, seen.get(0).kind());
    }

    @Test
    @DisplayName("离线的玩家也在遍历范围内：检查只看账本里的仓位，不看谁在线")
    void everyPlayerWithAPositionIsChecked() {
        isolatedLong();
        UUID bob = new UUID(0, 2);
        accounts.deposit(bob, Money.parse("1000"));
        book.open(bob, DIAMOND, snapshot(DIAMOND, 1000), Side.SHORT, 2000, 10, marks(1000, 100));

        // 价格跌到 800：Alice（多）穿仓被强平，Bob（空）赚钱不动
        List<LiquidationEvent> events = check(800, 100);

        assertEquals(1, events.size());
        assertEquals(ALICE, events.get(0).player());
        assertTrue(book.position(bob, "DIAMONDCRP").isPresent());
    }

    @Test
    @DisplayName("基金余额随状态导出、恢复；旧存档（没有基金）恢复成 0")
    void fundSurvivesRestore() {
        isolatedLong();
        check(903.8, 100);

        TradingBook restored = TradingBook.restore(accounts, book.state());

        assertEquals(Money.parse("7.15"), restored.fund().balance());
        TradingState legacy = new TradingState(LiquidityPool.INITIAL_BALANCE, 0, Map.of(), Map.of());
        assertEquals(Money.ZERO, TradingBook.restore(accounts, legacy).fund().balance());
    }

    @Test
    @DisplayName("资金费把逐仓保证金扣到 0 之后，同一步的强平检查接手（先资金费、后强平）")
    void fundingDrainedPositionIsLiquidatedByTheSameStepsCheck() {
        isolatedLong();
        Money before = totalOf(ALICE);

        // 结算点、费率 500%：应付 2000 × 5 = 10000，远超保证金 200.05，保证金扣到 0
        book.settleFunding(new MarketSnapshot(4800L * 1_000_000, "DIAMONDCRP", 1000, 1000, 1000, 5.0, 0));
        assertEquals(Money.ZERO, book.position(ALICE, "DIAMONDCRP").orElseThrow().margin());
        assertEquals(Money.parse("1000201.05025"), pool.balance(), "池子收到开仓费 1.00025 和被扣光的保证金 200.05");

        // 标记价格 1000：盈亏 (1000 − 1000.25) × 2 = −0.5，保证金余额 −0.5 ≥ 维持保证金，强平；没有抵押可没收，亏损 0.5 是穿仓
        List<LiquidationEvent> events = check(1000, 100);

        assertEquals(1, events.size());
        assertEquals(Money.ZERO, events.get(0).collateralLost());
        assertEquals(Money.ZERO, events.get(0).toFund());
        assertEquals(Money.parse("0.5"), events.get(0).shortfall());
        assertEquals(Money.parse("0.5"), events.get(0).poolBorne(), "基金是空的，池子少收");
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        assertEquals(Money.parse("798.94975"), accounts.balance(ALICE), "可用余额不动");
        assertEquals(before, totalOf(ALICE), "总额守恒");
    }

    @Test
    @DisplayName("新账本的风险保障基金是 0")
    void fundStartsEmpty() {
        assertEquals(Money.ZERO, book.fund().balance());
    }

    private Money totalOf(UUID... players) {
        Money total = pool.balance().plus(book.fund().balance());
        for (UUID player : players) {
            total = total.plus(accounts.balance(player)).plus(book.orders().frozen(player));
            for (Position position : book.positions(player)) {
                total = total.plus(position.margin());
            }
        }
        return total;
    }
}
