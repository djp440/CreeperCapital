package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 全仓模式、保证金模式切换、按名义价值分档的杠杆上限与调整杠杆。
 * 钻石：点差 0.5、数量精度 3 位（1 手 = 0.001 个）、125x；火药：点差 0.05、数量精度 0 位、50x。期望值全部手算。
 * 2.000 个钻石在最新价 1000 开多：成交价 1000.25，名义价值 2000.5，10 倍保证金 200.05，手续费 1.00025。
 */
class CrossMarginTest {
    private static final ContractSpec DEEP_DIAMOND = ContractSpec.defaults().get(0);
    private static final ContractSpec DEEP_GUNPOWDER = ContractSpec.defaults().get(1);
    private static final ContractSpec DIAMOND = unlimitedDepth(DEEP_DIAMOND);
    private static final ContractSpec GUNPOWDER = unlimitedDepth(DEEP_GUNPOWDER);
    private static final UUID ALICE = new UUID(0, 1);

    private AccountBook accounts;
    private LiquidityPool pool;
    private TradingBook book;

    @BeforeEach
    void setUp() {
        accounts = new AccountBook();
        pool = new LiquidityPool();
        book = new TradingBook(accounts, pool);
    }

    /** 无限深的变体：成交价恰好是最新价 ± 半个点差，手算值不受滑点影响（滑点本身见 LiquidityCurveTest）。 */
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

    private static PlayerTrade filled(TradeResult result) {
        return assertInstanceOf(TradeResult.Filled.class, result).trade();
    }

    private static TradeResult.Rejected rejected(TradeResult result) {
        return assertInstanceOf(TradeResult.Rejected.class, result);
    }

    private static MarginResult.Rejected rejected(MarginResult result) {
        return assertInstanceOf(MarginResult.Rejected.class, result);
    }

    private void openDiamond(Side side, long lots, int leverage, double last) {
        filled(book.open(ALICE, DIAMOND, snapshot(DIAMOND, last), side, lots, leverage, marks(last, 100)));
    }

    // ---- 保证金模式 ----

    @Test
    @DisplayName("保证金模式按合约设置，缺省逐仓；新开的仓位带着当时的模式")
    void modeIsPerContract() {
        deposit("1000");
        assertEquals(MarginMode.ISOLATED, book.marginMode(ALICE, "DIAMONDCRP"));

        assertInstanceOf(MarginResult.Applied.class, book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS));
        openDiamond(Side.LONG, 1000, 10, 1000);
        filled(book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100), Side.LONG, 1, 10, marks(1000, 100)));

        assertEquals(MarginMode.CROSS, book.position(ALICE, "DIAMONDCRP").orElseThrow().mode());
        assertEquals(MarginMode.ISOLATED, book.position(ALICE, "GUNPOWDERCRP").orElseThrow().mode());
        assertEquals(MarginMode.CROSS, book.marginMode(ALICE, "DIAMONDCRP"));
        assertEquals(MarginMode.ISOLATED, book.marginMode(new UUID(0, 2), "DIAMONDCRP"));
    }

    @Test
    @DisplayName("该合约有仓位时不能切换保证金模式；平仓后可以；别的合约不受影响")
    void modeIsLockedWhileThereIsExposure() {
        deposit("1000");
        openDiamond(Side.LONG, 1000, 10, 1000);

        assertTrue(book.hasOpenExposure(ALICE, "DIAMONDCRP"));
        assertFalse(book.hasOpenExposure(ALICE, "GUNPOWDERCRP"));
        assertEquals(TradeResult.Reason.MARGIN_MODE_LOCKED,
                rejected(book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS)).reason());
        assertEquals(MarginMode.ISOLATED, book.marginMode(ALICE, "DIAMONDCRP"));
        assertInstanceOf(MarginResult.Applied.class, book.setMarginMode(ALICE, "GUNPOWDERCRP", MarginMode.CROSS));

        filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 1000), 1000));
        assertFalse(book.hasOpenExposure(ALICE, "DIAMONDCRP"));
        assertInstanceOf(MarginResult.Applied.class, book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS));
    }

    @Test
    @DisplayName("挂着限价委托也算有敞口：不能切换保证金模式，撤单后可以")
    void pendingLimitOrderAlsoLocksTheMode() {
        deposit("10000");
        TradeResult placed = book.orders().placeLimit(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 1000, 10, 900.0);
        long orderId = assertInstanceOf(TradeResult.Placed.class, placed).order().id();

        assertTrue(book.hasOpenExposure(ALICE, "DIAMONDCRP"));
        assertFalse(book.hasOpenExposure(ALICE, "GUNPOWDERCRP"));
        assertEquals(TradeResult.Reason.MARGIN_MODE_LOCKED,
                rejected(book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS)).reason());

        assertTrue(book.orders().cancel(ALICE, orderId, 100).isPresent());
        assertFalse(book.hasOpenExposure(ALICE, "DIAMONDCRP"));
        assertInstanceOf(MarginResult.Applied.class, book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS));
    }

    // ---- 同一亏损在两种模式下 ----

    @Test
    @DisplayName("同一笔亏损：逐仓最多亏掉仓位的保证金，全仓可以亏到保证金之外（动用账户其余余额）")
    void sameLossInBothModes() {
        deposit("1000");
        book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS);
        openDiamond(Side.LONG, 2000, 10, 1000);
        // 价格跌到 800：卖出价 799.75，盈亏 (799.75 − 1000.25) × 2 = −401，名义价值 1599.5，手续费 0.79975

        PlayerTrade trade = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 800), 2000));

        assertEquals(Money.parse("-401"), trade.realizedPnl());
        assertEquals(Money.parse("0.79975"), trade.fee());
        // 1000 − 401 − 开仓手续费 1.00025 − 平仓手续费 0.79975（逐仓同样的亏损只亏 200.05，见 TradingBookTest.lossIsCappedAtMargin）
        assertEquals(Money.parse("597.2"), accounts.balance(ALICE));
        assertEquals(Money.parse("1000402.8"), pool.balance());
    }

    @Test
    @DisplayName("全仓亏损最多亏到账户里的全部余额，余额不会变成负数")
    void crossLossIsCappedAtTheAccount() {
        deposit("300");
        book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS);
        openDiamond(Side.LONG, 2000, 10, 1000); // 余额 300 − 200.05 − 1.00025 = 98.94975

        // 价格跌到 600：盈亏 (599.75 − 1000.25) × 2 = −801，远超账户的 200.05 + 98.94975 = 298.99975
        PlayerTrade trade = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 600), 2000));

        assertEquals(Money.parse("-298.99975"), trade.realizedPnl());
        assertEquals(Money.ZERO, trade.fee(), "账户已亏光，没有可收的手续费");
        assertEquals(Money.ZERO, accounts.balance(ALICE));
        assertEquals(Money.parse("1000300"), pool.balance());
    }

    // ---- 资产口径 ----

    @Test
    @DisplayName("全仓浮亏占用可用余额（保证金余额里计入未实现盈亏），逐仓浮亏不影响可用余额")
    void crossUnrealizedLossReducesAvailableBalance() {
        deposit("1000");
        book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS);
        openDiamond(Side.LONG, 2000, 10, 1000); // 可用 798.94975

        // 标记价格 900：未实现盈亏 (900 − 1000.25) × 2 = −200.5
        Assets assets = book.assets(ALICE, marks(900, 100));

        assertEquals(Money.parse("998.99975"), assets.walletBalance());
        assertEquals(Money.parse("-200.5"), assets.unrealizedPnl());
        assertEquals(Money.parse("798.49975"), assets.marginBalance());
        // 可用余额 = 798.94975 − 200.5
        assertEquals(Money.parse("598.44975"), assets.availableBalance());
    }

    @Test
    @DisplayName("全仓浮盈不能当可用余额（平仓实现后才能动用）；全仓浮亏不设下限（不像逐仓封顶在保证金）")
    void crossProfitIsNotAvailableAndLossIsNotCapped() {
        deposit("1000");
        book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS);
        openDiamond(Side.LONG, 2000, 10, 1000);

        Assets up = book.assets(ALICE, marks(1100, 100)); // 盈亏 (1100 − 1000.25) × 2 = 199.5
        assertEquals(Money.parse("199.5"), up.unrealizedPnl());
        assertEquals(Money.parse("798.94975"), up.availableBalance());

        Assets down = book.assets(ALICE, marks(500, 100)); // 盈亏 (500 − 1000.25) × 2 = −1000.5，超过保证金 200.05
        assertEquals(Money.parse("-1000.5"), down.unrealizedPnl());
        assertEquals(Money.parse("-1.50025"), down.marginBalance());
        assertEquals(Money.ZERO, down.availableBalance());
    }

    @Test
    @DisplayName("开仓要求的资金与全仓浮亏之后的可用余额比较，不够就拒单并说明需要多少、有多少")
    void openingChecksAvailableBalanceAfterCrossLosses() {
        deposit("1000");
        book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS);
        openDiamond(Side.LONG, 2000, 10, 1000);

        // 标记价格 900 时可用 598.44975；火药 7 个、1 倍杠杆：成交价 100.025，名义价值 700.175 = 保证金，手续费 0.350088
        TradeResult.Rejected rejection = rejected(
                book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100), Side.LONG, 7, 1, marks(900, 100)));

        assertEquals(TradeResult.Reason.INSUFFICIENT_BALANCE, rejection.reason());
        assertEquals(List.of("700.525088", "598.44975"), rejection.args());
        // 5 个：保证金 500.125 + 手续费 0.250063 = 500.375063 ≤ 598.44975
        filled(book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100), Side.LONG, 5, 1, marks(900, 100)));
    }

    // ---- 分档的杠杆上限 ----

    @Test
    @DisplayName("开仓：名义价值超过该杠杆下的档位上限就拒绝（钻石 125x 最多 5000 CRP），加仓同样按合计算")
    void positionLimitFollowsTheLeverageTier() {
        deposit("100000");

        // 5.000 个钻石成交价 1000.25，名义价值 5001.25 > 5000
        TradeResult.Rejected rejection = rejected(
                book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 5000, 125, marks(1000, 100)));
        assertEquals(TradeResult.Reason.POSITION_LIMIT, rejection.reason());
        assertEquals(List.of("5,000.00"), rejection.args());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());

        // 4.000 个 125x 可以（4001）；再加 1.000 个合计 5001.25 超限
        openDiamond(Side.LONG, 4000, 125, 1000);
        assertEquals(TradeResult.Reason.POSITION_LIMIT, rejected(
                book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 1000, 125, marks(1000, 100))).reason());

        // 火药 50x 的上限是 5000（只有第一档允许 50 倍）：50 个名义价值 5001.25 超限，49 个 4901.225 可以
        TradeResult.Rejected gunpowder = rejected(
                book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100), Side.LONG, 50, 50, marks(1000, 100)));
        assertEquals(List.of("5,000.00"), gunpowder.args());
        filled(book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100), Side.LONG, 49, 50, marks(1000, 100)));

        // 同样 6.000 个钻石，降到 100x 就在第二档的范围内（上限 25000）
        UUID bob = new UUID(0, 2);
        accounts.deposit(bob, Money.parse("10000"));
        filled(book.open(bob, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 6000, 100, marks(1000, 100)));
    }

    // ---- 调整杠杆 ----

    @Test
    @DisplayName("有仓位时调高杠杆：保证金按 开仓名义价值 / 新杠杆 重算，多出的保证金退回可用余额")
    void raiseLeverageReleasesMargin() {
        deposit("1000");
        openDiamond(Side.LONG, 2000, 10, 1000); // 保证金 200.05，可用 798.94975

        assertInstanceOf(MarginResult.Applied.class, book.adjustLeverage(ALICE, DIAMOND, marks(1000, 100), 20));

        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(20, position.leverage());
        assertEquals(Money.parse("100.025"), position.margin()); // 2000.5 / 20
        assertEquals(Money.parse("898.97475"), accounts.balance(ALICE));
    }

    @Test
    @DisplayName("有仓位时调低杠杆：补足保证金，从可用余额里扣")
    void lowerLeverageTakesMoreMargin() {
        deposit("1000");
        openDiamond(Side.LONG, 2000, 10, 1000);

        assertInstanceOf(MarginResult.Applied.class, book.adjustLeverage(ALICE, DIAMOND, marks(1000, 100), 5));

        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(5, position.leverage());
        assertEquals(Money.parse("400.1"), position.margin()); // 2000.5 / 5
        assertEquals(Money.parse("598.89975"), accounts.balance(ALICE));
        // 之后加仓必须用新杠杆
        assertEquals(TradeResult.Reason.LEVERAGE_MISMATCH, rejected(
                book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 1000, 10, marks(1000, 100))).reason());
    }

    @Test
    @DisplayName("可用余额不够补足新杠杆要求的保证金就拒绝调整，仓位和余额不变")
    void lowerLeverageNeedsAvailableMargin() {
        deposit("300");
        openDiamond(Side.LONG, 2000, 10, 1000); // 可用 98.94975

        // 1 倍杠杆需要保证金 2000.5，要多补 2000.5 − 200.05 = 1800.45
        MarginResult.Rejected rejection = rejected(book.adjustLeverage(ALICE, DIAMOND, marks(1000, 100), 1));

        assertEquals(TradeResult.Reason.INSUFFICIENT_BALANCE, rejection.reason());
        assertEquals(List.of("1,800.45", "98.94975"), rejection.args());
        assertEquals(10, book.position(ALICE, "DIAMONDCRP").orElseThrow().leverage());
        assertEquals(Money.parse("98.94975"), accounts.balance(ALICE));
    }

    @Test
    @DisplayName("新杠杆不能超过当前名义价值所在档位的上限，并告知该档的上限和当前名义价值")
    void leverageIsLimitedByTheTierOfTheCurrentNotional() {
        deposit("10000");
        // 6.000 个 10x：成交价 1000.25，名义价值 6001.5，在第二档 (5000, 25000]，最高 100 倍
        openDiamond(Side.LONG, 6000, 10, 1000);

        // 按标记价格 1000 算当前名义价值是 6000
        MarginResult.Rejected rejection = rejected(book.adjustLeverage(ALICE, DIAMOND, marks(1000, 100), 125));
        assertEquals(TradeResult.Reason.LEVERAGE_ABOVE_TIER, rejection.reason());
        assertEquals(List.of("100", "6,000.00"), rejection.args());
        assertEquals(10, book.position(ALICE, "DIAMONDCRP").orElseThrow().leverage());

        assertInstanceOf(MarginResult.Applied.class, book.adjustLeverage(ALICE, DIAMOND, marks(1000, 100), 100));
        assertEquals(100, book.position(ALICE, "DIAMONDCRP").orElseThrow().leverage());
    }

    @Test
    @DisplayName("调整杠杆的其他拒绝：没有仓位、杠杆超出合约范围；调成同一杠杆什么都不变")
    void leverageAdjustmentEdges() {
        deposit("1000");
        assertEquals(TradeResult.Reason.NO_POSITION, rejected(book.adjustLeverage(ALICE, DIAMOND, marks(1000, 100), 10)).reason());

        openDiamond(Side.LONG, 2000, 10, 1000);
        assertEquals(TradeResult.Reason.INVALID_LEVERAGE, rejected(book.adjustLeverage(ALICE, DIAMOND, marks(1000, 100), 0)).reason());
        assertEquals(TradeResult.Reason.INVALID_LEVERAGE, rejected(book.adjustLeverage(ALICE, DIAMOND, marks(1000, 100), 126)).reason());
        assertInstanceOf(MarginResult.Applied.class, book.adjustLeverage(ALICE, DIAMOND, marks(1000, 100), 10));
        assertEquals(Money.parse("200.05"), book.position(ALICE, "DIAMONDCRP").orElseThrow().margin());
    }

    @Test
    @DisplayName("逐仓：调高杠杆会让保证金率达到 100% 时拒绝；全仓同样的调整可以（钱还在全仓账户里）")
    void raisingLeverageMustNotLiquidateAnIsolatedPosition() {
        deposit("1000");
        openDiamond(Side.LONG, 2000, 10, 1000);
        // 标记价格 910：浮亏 (910 − 1000.25) × 2 = −180.5；调到 20x 保证金只剩 100.025，保证金余额 −80.475
        MarginResult.Rejected rejection = rejected(book.adjustLeverage(ALICE, DIAMOND, marks(910, 100), 20));
        assertEquals(TradeResult.Reason.LEVERAGE_UNSAFE, rejection.reason());
        assertEquals(10, book.position(ALICE, "DIAMONDCRP").orElseThrow().leverage());

        UUID bob = new UUID(0, 2);
        accounts.deposit(bob, Money.parse("1000"));
        book.setMarginMode(bob, "DIAMONDCRP", MarginMode.CROSS);
        filled(book.open(bob, DIAMOND, snapshot(DIAMOND, 1000), Side.LONG, 2000, 10, marks(1000, 100)));
        assertInstanceOf(MarginResult.Applied.class, book.adjustLeverage(bob, DIAMOND, marks(910, 100), 20));
        assertEquals(20, book.position(bob, "DIAMONDCRP").orElseThrow().leverage());
    }

    // ---- 部分平仓与加仓的保证金 ----

    @Test
    @DisplayName("全仓加仓、部分平仓：保证金按比例累加和释放，与逐仓一致")
    void crossAddAndPartialClose() {
        deposit("1000");
        book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS);
        openDiamond(Side.LONG, 1000, 10, 1000); // 1.000 @1000.25，保证金 100.025
        openDiamond(Side.LONG, 3000, 10, 1100); // 3.000 @1100.25，保证金 330.075

        filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 1100), 1000));

        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(3000, position.lots());
        assertEquals(MarginMode.CROSS, position.mode());
        // 430.1 × 1000 / 4000 = 107.525 释放，剩 322.575
        assertEquals(Money.parse("322.575"), position.margin());
    }

    // ---- 存档 ----

    @Test
    @DisplayName("保证金模式设置与仓位的模式随交易账状态导出、恢复")
    void marginModesRoundTrip() {
        deposit("1000");
        book.setMarginMode(ALICE, "DIAMONDCRP", MarginMode.CROSS);
        book.setMarginMode(ALICE, "GUNPOWDERCRP", MarginMode.CROSS);
        openDiamond(Side.LONG, 1000, 10, 1000);
        book.close(ALICE, DIAMOND, snapshot(DIAMOND, 1000), 1000);
        openDiamond(Side.SHORT, 1000, 10, 1000);

        TradingBook restored = TradingBook.restore(accounts, book.state());

        assertEquals(MarginMode.CROSS, restored.marginMode(ALICE, "GUNPOWDERCRP"));
        assertEquals(MarginMode.CROSS, restored.position(ALICE, "DIAMONDCRP").orElseThrow().mode());
        assertTrue(restored.hasOpenExposure(ALICE, "DIAMONDCRP"));
    }

    // ---- 守恒 ----

    @Test
    @DisplayName("全仓、逐仓混合，随机开平仓、切模式、调杠杆、强平（含穿仓）之后，玩家余额 + 仓位保证金 + 池子 + 基金的总额不变")
    void totalIsConservedWithCrossAndLeverageChanges() {
        Random random = new Random(11);
        UUID[] players = {ALICE, new UUID(0, 2), new UUID(0, 3)};
        for (UUID player : players) {
            accounts.deposit(player, Money.parse("5000"));
        }
        Money expected = Money.parse("1015000");
        ContractSpec[] specs = {DEEP_DIAMOND, DEEP_GUNPOWDER};
        int crossFills = 0;
        int adjustments = 0;
        int liquidations = 0;
        int crossLiquidations = 0;
        int settled = 0;
        int shortfalls = 0;
        int liquidationShortfalls = 0;

        for (int i = 0; i < 3000; i++) {
            UUID player = players[random.nextInt(players.length)];
            ContractSpec spec = specs[random.nextInt(specs.length)];
            double diamondPrice = DIAMOND.initialPrice() * (0.6 + random.nextDouble() * 0.8);
            double gunpowderPrice = GUNPOWDER.initialPrice() * (0.6 + random.nextDouble() * 0.8);
            ToDoubleFunction<String> mark = marks(diamondPrice, gunpowderPrice);
            double last = spec == DIAMOND ? diamondPrice : gunpowderPrice;
            MarketSnapshot at = snapshot(spec, last);
            int action = random.nextInt(10);
            if (action < 4) {
                if (book.marginMode(player, spec.symbol()) == MarginMode.ISOLATED && !book.hasOpenExposure(player, spec.symbol())
                        && random.nextBoolean()) {
                    book.setMarginMode(player, spec.symbol(), MarginMode.CROSS);
                }
                TradeResult result = book.open(player, spec, at, random.nextBoolean() ? Side.LONG : Side.SHORT,
                        1 + random.nextInt(500), 1 + random.nextInt(50), mark);
                if (result instanceof TradeResult.Filled
                        && book.position(player, spec.symbol()).orElseThrow().mode() == MarginMode.CROSS) {
                    crossFills++;
                }
            } else if (action < 7) {
                long open = book.position(player, spec.symbol()).map(Position::lots).orElse(1L);
                book.close(player, spec, at, 1 + random.nextInt((int) open));
            } else if (action < 9) {
                if (book.adjustLeverage(player, spec, mark, 1 + random.nextInt(50)) instanceof MarginResult.Applied) {
                    adjustments++;
                }
            } else if (!book.hasOpenExposure(player, spec.symbol())) {
                book.setMarginMode(player, spec.symbol(),
                        random.nextBoolean() ? MarginMode.CROSS : MarginMode.ISOLATED);
            }
            if (i % 20 == 19) {
                // 资金费结算：多数是正常费率，偶尔极端费率把逐仓保证金 / 全仓余额扣到 0 或让池子付不起
                double rate = i % 100 == 99 ? (random.nextBoolean() ? 3.0 : -3.0) : (random.nextDouble() - 0.5) * 0.006;
                long settleStep = 4800L * (i + 1);
                for (ContractSpec each : specs) {
                    double price = each == DEEP_DIAMOND ? diamondPrice : gunpowderPrice;
                    for (FundingRecord record : book.settleFunding(
                            new MarketSnapshot(settleStep, each.symbol(), price, price, price, rate, 0))) {
                        settled++;
                        if (record.shortfall().isPositive()) {
                            shortfalls++;
                        }
                    }
                }
            }
            // 同一步内顺序：资金费结算 → 强平检查。每一步的价格都在 0.6 到 1.4 倍之间乱跳，高杠杆仓位会被强平，跳得深的会穿仓
            if (random.nextInt(8) == 0) {
                for (LiquidationEvent event : book.checkLiquidations(mark, symbol -> symbol.equals("DIAMONDCRP")
                        ? DEEP_DIAMOND.marginTiers() : DEEP_GUNPOWDER.marginTiers(), i * 6L)) {
                    liquidations++;
                    crossLiquidations += event.mode() == MarginMode.CROSS ? 1 : 0;
                    liquidationShortfalls += event.shortfall().isPositive() ? 1 : 0;
                }
                // 账被清空的玩家补钱（开发命令式调账，总额同步调整），让后面的操作还有钱可用
                for (UUID p : players) {
                    if (accounts.balance(p).compareTo(Money.parse("1000")) < 0) {
                        accounts.deposit(p, Money.parse("5000"));
                        expected = expected.plus(Money.parse("5000"));
                    }
                }
            }
            assertEquals(expected, total(players), "第 " + i + " 次操作之后总额变了");
            for (UUID p : players) {
                assertFalse(accounts.balance(p).units() < 0, "第 " + i + " 次操作之后余额为负");
            }
        }
        assertTrue(crossFills > 50, "应有足够多的全仓成交：" + crossFills);
        assertTrue(adjustments > 50, "应有足够多的杠杆调整：" + adjustments);
        assertTrue(liquidations > 20, "应有足够多的强平：" + liquidations);
        assertTrue(crossLiquidations > 3, "应有全仓强平：" + crossLiquidations);
        assertTrue(liquidationShortfalls > 3, "应有穿仓：" + liquidationShortfalls);
        assertTrue(settled > 100, "应有足够多的资金费结算：" + settled);
        assertTrue(shortfalls > 0, "极端费率应触发过扣到 0 的情形：" + shortfalls);
    }

    private Money total(UUID[] players) {
        Money total = pool.balance().plus(book.fund().balance());
        for (UUID player : players) {
            total = total.plus(accounts.balance(player));
            for (Position position : book.positions(player)) {
                total = total.plus(position.margin());
            }
        }
        return total;
    }
}
