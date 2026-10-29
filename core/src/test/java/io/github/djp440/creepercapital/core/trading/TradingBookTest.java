package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
 * 市价开平仓（逐仓）：成交价沿虚拟流动性曲线从最新价格 ± 半个点差起逐档吃单，对手方是流动性池，taker 手续费 0.05% 归池子。
 * 钻石：点差 0.5、数量精度 3 位（1 手 = 0.001 个）；火药：点差 0.05、数量精度 0 位。期望值都是手算的。
 * <p>
 * 除「滑点」一节外，测试用的 {@code DIAMOND}、{@code GUNPOWDER} 把流动性深度设为无限（滑点 0），
 * 让成交价恰好是最新价格 ± 半个点差，专注验证保证金、盈亏、手续费、守恒；曲线本身见 {@code LiquidityCurveTest}。
 */
class TradingBookTest {
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

    private static ContractSpec unlimitedDepth(ContractSpec spec) {
        return new ContractSpec(spec.symbol(), spec.underlying(), spec.initialPrice(), spec.baseVolume(), spec.volatility(),
                spec.spread(), spec.maxLeverage(), spec.quantityDecimals(), 0.0);
    }

    private static MarketSnapshot snapshot(ContractSpec spec, double last, double mark) {
        return new MarketSnapshot(100, spec.symbol(), last, last, mark, 0.0001, 0);
    }

    private void deposit(String amount) {
        accounts.deposit(ALICE, Money.parse(amount));
    }

    private static PlayerTrade filled(TradeResult result) {
        return assertInstanceOf(TradeResult.Filled.class, result).trade();
    }

    @Test
    @DisplayName("流动性池初始 1,000,000 CRP")
    void poolStartsWithOneMillion() {
        assertEquals(Money.parse("1000000"), pool.balance());
    }

    @Test
    @DisplayName("开多：成交价 = 最新价格 + 半个点差，保证金 = 名义价值 / 杠杆，taker 手续费归池子")
    void openLong() {
        deposit("1000");

        // 买入 2.000 个钻石：成交价 1000 + 0.25 = 1000.25，名义价值 2000.5，10 倍杠杆保证金 200.05，手续费 0.05% × 2000.5 = 1.00025
        PlayerTrade trade = filled(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10));

        assertEquals(1000.25, trade.price());
        assertEquals(TradeKind.OPEN, trade.kind());
        assertEquals(Side.LONG, trade.side());
        assertEquals(2000, trade.lots());
        assertEquals(Money.parse("1.00025"), trade.fee());
        assertEquals(Money.ZERO, trade.realizedPnl());
        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(Side.LONG, position.side());
        assertEquals(2000, position.lots());
        assertEquals(1000.25, position.entryPrice());
        assertEquals(10, position.leverage());
        assertEquals(Money.parse("200.05"), position.margin());
        assertEquals(Money.parse("798.94975"), accounts.balance(ALICE));
        assertEquals(Money.parse("1000001.00025"), pool.balance());
    }

    @Test
    @DisplayName("开空：成交价 = 最新价格 − 半个点差")
    void openShort() {
        deposit("1000");

        // 卖出 2.000 个钻石：成交价 1000 − 0.25 = 999.75，名义价值 1999.5，保证金 199.95，手续费 0.99975
        PlayerTrade trade = filled(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.SHORT, 2000, 10));

        assertEquals(999.75, trade.price());
        assertEquals(Side.SHORT, trade.side());
        assertEquals(Money.parse("0.99975"), trade.fee());
        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(Side.SHORT, position.side());
        assertEquals(Money.parse("199.95"), position.margin());
        assertEquals(Money.parse("799.05025"), accounts.balance(ALICE));
        assertEquals(Money.parse("1000000.99975"), pool.balance());
    }

    @Test
    @DisplayName("保证金向上取整到最小单位，不会少收")
    void marginRoundsUp() {
        deposit("1000");

        // 买 1 个火药：成交价 100.025，3 倍杠杆保证金 33.341666… → 33.341667
        book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100, 100), Side.LONG, 1, 3);

        assertEquals(Money.parse("33.341667"), book.position(ALICE, "GUNPOWDERCRP").orElseThrow().margin());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
    }

    // ---- 平仓 ----

    @Test
    @DisplayName("平多盈利：卖出价 = 最新价格 − 半个点差，已实现盈亏由池子支付，保证金加盈亏减手续费退回余额")
    void closeLongWithProfit() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10);

        // 价格涨到 1100：卖出价 1099.75，盈亏 (1099.75 − 1000.25) × 2 = 199，名义价值 2199.5，手续费 1.09975
        PlayerTrade trade = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 1100, 1100), 2000));

        assertEquals(1099.75, trade.price());
        assertEquals(TradeKind.CLOSE, trade.kind());
        assertEquals(Side.LONG, trade.side());
        assertEquals(Money.parse("199"), trade.realizedPnl());
        assertEquals(Money.parse("1.09975"), trade.fee());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        // 798.94975 + 保证金 200.05 + 盈亏 199 − 手续费 1.09975
        assertEquals(Money.parse("1196.9"), accounts.balance(ALICE));
        // 1000001.00025 − 盈亏 199 + 手续费 1.09975
        assertEquals(Money.parse("999803.1"), pool.balance());
    }

    @Test
    @DisplayName("平多亏损：亏损流入池子")
    void closeLongWithLoss() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10);

        // 价格跌到 950：卖出价 949.75，盈亏 (949.75 − 1000.25) × 2 = −101，名义价值 1899.5，手续费 0.94975
        PlayerTrade trade = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 950, 950), 2000));

        assertEquals(Money.parse("-101"), trade.realizedPnl());
        assertEquals(Money.parse("0.94975"), trade.fee());
        // 798.94975 + 200.05 − 101 − 0.94975
        assertEquals(Money.parse("897.05"), accounts.balance(ALICE));
        // 1000001.00025 + 101 + 0.94975
        assertEquals(Money.parse("1000102.95"), pool.balance());
    }

    @Test
    @DisplayName("平空盈利：买入价 = 最新价格 + 半个点差")
    void closeShortWithProfit() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.SHORT, 2000, 10);

        // 价格跌到 900：买入价 900.25，盈亏 (999.75 − 900.25) × 2 = 199，名义价值 1800.5，手续费 0.90025
        PlayerTrade trade = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 900, 900), 2000));

        assertEquals(900.25, trade.price());
        assertEquals(Side.SHORT, trade.side());
        assertEquals(Money.parse("199"), trade.realizedPnl());
        assertEquals(Money.parse("0.90025"), trade.fee());
        // 799.05025 + 199.95 + 199 − 0.90025
        assertEquals(Money.parse("1197.1"), accounts.balance(ALICE));
        // 1000000.99975 − 199 + 0.90025
        assertEquals(Money.parse("999802.9"), pool.balance());
    }

    @Test
    @DisplayName("平空亏损")
    void closeShortWithLoss() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.SHORT, 2000, 10);

        // 价格涨到 1050：买入价 1050.25，盈亏 (999.75 − 1050.25) × 2 = −101，名义价值 2100.5，手续费 1.05025
        PlayerTrade trade = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 1050, 1050), 2000));

        assertEquals(Money.parse("-101"), trade.realizedPnl());
        // 799.05025 + 199.95 − 101 − 1.05025
        assertEquals(Money.parse("896.95"), accounts.balance(ALICE));
        // 1000000.99975 + 101 + 1.05025
        assertEquals(Money.parse("1000103.05"), pool.balance());
    }

    @Test
    @DisplayName("部分平仓：按比例退回保证金，剩余仓位的开仓均价不变")
    void partialClose() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10);

        // 平 0.500 个，价格 1100：卖出价 1099.75，盈亏 99.5 × 0.5 = 49.75，名义价值 549.875，手续费 0.2749375 → 0.274938
        PlayerTrade trade = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 1100, 1100), 500));

        assertEquals(500, trade.lots());
        assertEquals(Money.parse("49.75"), trade.realizedPnl());
        assertEquals(Money.parse("0.274938"), trade.fee());
        Position remaining = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(1500, remaining.lots());
        assertEquals(1000.25, remaining.entryPrice());
        // 保证金 200.05 × 500 / 2000 = 50.0125 退回，剩 150.0375
        assertEquals(Money.parse("150.0375"), remaining.margin());
        // 798.94975 + 50.0125 + 49.75 − 0.274938
        assertEquals(Money.parse("898.437312"), accounts.balance(ALICE));
    }

    @Test
    @DisplayName("亏损最多亏到仓位的保证金（逐仓），余额不会出现负数")
    void lossIsCappedAtMargin() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10);

        // 价格跌到 800，真实亏损 (799.75 − 1000.25) × 2 = −401 远超保证金 200.05
        PlayerTrade trade = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 800, 800), 2000));

        assertEquals(Money.parse("-200.05"), trade.realizedPnl());
        assertEquals(Money.ZERO, trade.fee(), "保证金已亏光，没有可收的手续费");
        assertEquals(Money.parse("798.94975"), accounts.balance(ALICE));
        assertEquals(Money.parse("1000201.05025"), pool.balance());
    }

    @Test
    @DisplayName("池子余额不够付盈利时，最多付出池子全部余额，池子不会为负")
    void poolPaysAtMostItsBalance() {
        pool = new LiquidityPool(Money.parse("10"));
        book = new TradingBook(accounts, pool);
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10);

        // 开仓后池子是 10 + 1.00025 = 11.00025，应付盈利 199 只能付出这 11.00025；手续费 1.09975 仍归池子
        PlayerTrade trade = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 1100, 1100), 2000));

        assertEquals(Money.parse("11.00025"), trade.realizedPnl());
        assertEquals(Money.parse("1.09975"), pool.balance());
        // 798.94975 + 保证金 200.05 + 盈亏 11.00025 − 手续费 1.09975
        assertEquals(Money.parse("1008.90025"), accounts.balance(ALICE));
    }

    // ---- 成交历史 ----

    @Test
    @DisplayName("每笔玩家成交都有全服递增的编号，并按先后记入成交历史")
    void tradesAreNumberedAndRecorded() {
        deposit("1000");

        PlayerTrade first = filled(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 1000, 10));
        PlayerTrade second = filled(book.close(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), 1000));

        assertEquals(1, first.id());
        assertEquals(2, second.id());
        assertEquals(List.of(first, second), book.trades(ALICE));
        assertEquals(List.of(), book.trades(new UUID(0, 2)));
    }

    @Test
    @DisplayName("成交历史只保留每位玩家最近的 200 笔")
    void tradeHistoryIsBounded() {
        deposit("1000");
        MarketSnapshot at = snapshot(DIAMOND, 1000, 1000);

        for (int i = 0; i < 150; i++) {
            book.open(ALICE, DIAMOND, at, Side.LONG, 1, 10);
            book.close(ALICE, DIAMOND, at, 1);
        }

        assertEquals(TradingBook.MAX_TRADES_PER_PLAYER, book.trades(ALICE).size());
        assertEquals(300, book.trades(ALICE).get(TradingBook.MAX_TRADES_PER_PLAYER - 1).id());
        assertEquals(101, book.trades(ALICE).get(0).id());
    }

    // ---- 拒单 ----

    @Test
    @DisplayName("可用余额不够付保证金和手续费就拒单，并说明需要多少、有多少；账户、池子、仓位都不变")
    void insufficientBalanceRejects() {
        deposit("100");

        // 2.000 个钻石、10 倍杠杆需要 200.05 + 1.00025
        TradeResult result = book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10);

        TradeResult.Rejected rejected = assertInstanceOf(TradeResult.Rejected.class, result);
        assertEquals(TradeResult.Reason.INSUFFICIENT_BALANCE, rejected.reason());
        assertEquals(List.of("201.05025", "100.00"), rejected.args());
        assertEquals(Money.parse("100"), accounts.balance(ALICE));
        assertEquals(Money.parse("1000000"), pool.balance());
        assertTrue(book.position(ALICE, "DIAMONDCRP").isEmpty());
        assertEquals(List.of(), book.trades(ALICE));
    }

    @Test
    @DisplayName("刚好够付保证金和手续费时可以开仓")
    void exactBalanceIsEnough() {
        deposit("201.05025");

        filled(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10));

        assertEquals(Money.ZERO, accounts.balance(ALICE));
    }

    @Test
    @DisplayName("杠杆必须在 1 倍到合约最高杠杆之间（钻石 125x、火药 50x）")
    void leverageIsBoundedByTheContract() {
        deposit("100000");

        assertEquals(TradeResult.Reason.INVALID_LEVERAGE,
                rejection(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 1000, 0)));
        assertEquals(TradeResult.Reason.INVALID_LEVERAGE,
                rejection(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 1000, 126)));
        assertEquals(TradeResult.Reason.INVALID_LEVERAGE,
                rejection(book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100, 100), Side.LONG, 10, 51)));
        assertEquals(List.of("125"), assertInstanceOf(TradeResult.Rejected.class,
                book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 1000, 126)).args());
        filled(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 1000, 125));
        filled(book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100, 100), Side.LONG, 10, 50));
    }

    @Test
    @DisplayName("数量必须是正整数手，大到无法结算的数量也被拒绝")
    void quantityMustBePositive() {
        deposit("1000");

        assertEquals(TradeResult.Reason.INVALID_QUANTITY,
                rejection(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 0, 10)));
        assertEquals(TradeResult.Reason.INVALID_QUANTITY,
                rejection(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, -5, 10)));
        assertEquals(TradeResult.Reason.INVALID_QUANTITY,
                rejection(book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, Long.MAX_VALUE, 10)));
    }

    @Test
    @DisplayName("单向持仓：已有多头时开空被拒绝，要先平仓；平仓数量不能超过仓位，没有仓位不能平")
    void oneWayModeAndCloseChecks() {
        deposit("1000");
        MarketSnapshot at = snapshot(DIAMOND, 1000, 1000);

        assertEquals(TradeResult.Reason.NO_POSITION, rejection(book.close(ALICE, DIAMOND, at, 1000)));
        book.open(ALICE, DIAMOND, at, Side.LONG, 1000, 10);

        assertEquals(TradeResult.Reason.OPPOSITE_POSITION, rejection(book.open(ALICE, DIAMOND, at, Side.SHORT, 1000, 10)));
        assertEquals(TradeResult.Reason.CLOSE_EXCEEDS_POSITION, rejection(book.close(ALICE, DIAMOND, at, 1001)));
        assertEquals(TradeResult.Reason.INVALID_QUANTITY, rejection(book.close(ALICE, DIAMOND, at, 0)));
        assertEquals(1000, book.position(ALICE, "DIAMONDCRP").orElseThrow().lots());
    }

    @Test
    @DisplayName("已有仓位时下单的杠杆必须与仓位一致，并告知仓位的杠杆")
    void leverageMustMatchTheExistingPosition() {
        deposit("1000");
        MarketSnapshot at = snapshot(DIAMOND, 1000, 1000);
        book.open(ALICE, DIAMOND, at, Side.LONG, 1000, 10);

        TradeResult.Rejected rejected = assertInstanceOf(TradeResult.Rejected.class,
                book.open(ALICE, DIAMOND, at, Side.LONG, 1000, 20));

        assertEquals(TradeResult.Reason.LEVERAGE_MISMATCH, rejected.reason());
        assertEquals(List.of("10"), rejected.args());
    }

    @Test
    @DisplayName("同合约、同向再开仓是加仓：开仓均价按数量加权，保证金累加")
    void addingToAPosition() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 1000, 10); // 1.000 个 @ 1000.25
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1100, 1100), Side.LONG, 3000, 10); // 3.000 个 @ 1100.25

        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();
        assertEquals(4000, position.lots());
        // (1000.25 × 1 + 1100.25 × 3) / 4 = 1075.25
        assertEquals(1075.25, position.entryPrice());
        // 100.025 + 330.075
        assertEquals(Money.parse("430.1"), position.margin());
    }

    @Test
    @DisplayName("不同合约的仓位互相独立；不同玩家的仓位互相独立")
    void positionsAreIndependent() {
        deposit("1000");
        UUID bob = new UUID(0, 2);
        accounts.deposit(bob, Money.parse("1000"));

        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 1000, 10);
        book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100, 100), Side.SHORT, 10, 5);
        book.open(bob, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.SHORT, 1000, 10);

        assertEquals(Side.LONG, book.position(ALICE, "DIAMONDCRP").orElseThrow().side());
        assertEquals(Side.SHORT, book.position(ALICE, "GUNPOWDERCRP").orElseThrow().side());
        assertEquals(Side.SHORT, book.position(bob, "DIAMONDCRP").orElseThrow().side());
        assertEquals(2, book.positions(ALICE).size());
        assertEquals(1, book.positions(bob).size());
    }

    private static TradeResult.Reason rejection(TradeResult result) {
        return assertInstanceOf(TradeResult.Rejected.class, result).reason();
    }

    // ---- 资产 ----

    @Test
    @DisplayName("资产：钱包余额含仓位保证金，保证金余额再加未实现盈亏（按标记价格），可用余额是还没占用的余额")
    void assetsIncludePositions() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10);

        // 标记价格 1050：未实现盈亏 (1050 − 1000.25) × 2 = 99.5
        Assets assets = book.assets(ALICE, symbol -> 1050.0);

        assertEquals(Money.parse("998.99975"), assets.walletBalance());
        assertEquals(Money.parse("99.5"), assets.unrealizedPnl());
        assertEquals(Money.parse("1098.49975"), assets.marginBalance());
        assertEquals(Money.parse("798.94975"), assets.availableBalance());
    }

    @Test
    @DisplayName("没有仓位的玩家，资产就是钱包余额；空头价格上涨时未实现盈亏为负；标记价格未知（合约已下线）时按开仓均价算、盈亏为 0")
    void assetsWithoutPositionsAndWithShorts() {
        deposit("500");
        Assets flat = book.assets(ALICE, symbol -> 1.0);
        assertEquals(Money.parse("500"), flat.walletBalance());
        assertEquals(Money.ZERO, flat.unrealizedPnl());
        assertEquals(Money.parse("500"), flat.availableBalance());

        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.SHORT, 1000, 10); // 1.000 个 @ 999.75
        assertEquals(Money.parse("-10.25"), book.assets(ALICE, symbol -> 1010.0).unrealizedPnl());
        assertEquals(Money.ZERO, book.assets(ALICE, symbol -> Double.NaN).unrealizedPnl());
    }

    @Test
    @DisplayName("资产里的浮亏最多算到保证金（与平仓结算一致）")
    void unrealizedLossInAssetsIsCappedAtMargin() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 1000, 10); // 保证金 100.025
        // 标记价格 500：浮亏 (500 − 1000.25) × 1 = −500.25，但最多亏保证金 100.025
        Assets assets = book.assets(ALICE, symbol -> 500.0);

        assertEquals(Money.parse("-100.025"), assets.unrealizedPnl());
        assertEquals(assets.availableBalance(), assets.marginBalance());
    }

    @Test
    @DisplayName("仓位的未实现盈亏与收益率按标记价格算：收益率 = 盈亏 / 保证金")
    void positionPnlAndReturn() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10); // 保证金 200.05
        Position position = book.position(ALICE, "DIAMONDCRP").orElseThrow();

        assertEquals(Money.parse("99.5"), position.pnlAt(1050));
        assertEquals(99.5 / 200.05, position.returnOnEquity(1050), 1e-12);
        assertEquals(Money.parse("-50.25"), position.pnlAt(975.125));
    }

    // ---- 守恒 ----

    @Test
    @DisplayName("任意一串开平仓之后，玩家余额 + 仓位保证金 + 池子的总额不变（盈亏和手续费只在玩家与池子之间流动）")
    void totalIsConserved() {
        Random random = new Random(7);
        UUID[] players = {ALICE, new UUID(0, 2), new UUID(0, 3)};
        for (UUID player : players) {
            accounts.deposit(player, Money.parse("5000"));
        }
        Money expected = Money.parse("1015000");
        ContractSpec[] specs = {DEEP_DIAMOND, DEEP_GUNPOWDER};
        int filledTrades = 0;

        for (int i = 0; i < 2000; i++) {
            UUID player = players[random.nextInt(players.length)];
            ContractSpec spec = specs[random.nextInt(specs.length)];
            double last = spec.initialPrice() * (0.6 + random.nextDouble() * 0.8);
            MarketSnapshot at = snapshot(spec, last, last);
            TradeResult result;
            if (random.nextBoolean()) {
                result = book.open(player, spec, at, random.nextBoolean() ? Side.LONG : Side.SHORT,
                        1 + random.nextInt(2000), 1 + random.nextInt(50));
            } else {
                long open = book.position(player, spec.symbol()).map(Position::lots).orElse(1L);
                result = book.close(player, spec, at, 1 + random.nextInt((int) open));
            }
            if (result instanceof TradeResult.Filled) {
                filledTrades++;
            }
            assertEquals(expected, total(players), "第 " + i + " 次下单之后总额变了");
        }
        assertTrue(filledTrades > 500, "应有足够多的成交：" + filledTrades);
    }

    private Money total(UUID[] players) {
        Money total = pool.balance();
        for (UUID player : players) {
            total = total.plus(accounts.balance(player));
            for (Position position : book.positions(player)) {
                total = total.plus(position.margin());
            }
        }
        return total;
    }

    // ---- 滑点 ----

    @Test
    @DisplayName("大单沿流动性曲线逐档吃单：开多均价高于买一价，成交价、手续费都按均价算")
    void bigOpenFillsAlongTheCurve() {
        deposit("100000");

        // 火药最新价 100，买一价 100.025，每档 5 手，步长 g = 0.006 / 19；买 12 手 = 5 + 5 + 2：
        // 均价 = 100.025 × (5 + 5 × (1 + g) + 2 × (1 + g)²) / 12 = 100.048692，名义价值 1200.584302
        PlayerTrade trade = filled(book.open(ALICE, DEEP_GUNPOWDER, snapshot(DEEP_GUNPOWDER, 100, 100), Side.LONG, 12, 10));

        assertEquals(100.048692, trade.price(), 1e-6);
        assertTrue(trade.price() > 100.025);
        assertEquals(trade.price(), book.position(ALICE, "GUNPOWDERCRP").orElseThrow().entryPrice());
        // 手续费 = 名义价值 × 0.05% = 0.600292151 → 0.600292
        assertEquals(Money.parse("0.600292"), trade.fee());
    }

    @Test
    @DisplayName("大单平仓同样滑点：平多卖出的均价低于买一价")
    void bigCloseFillsAlongTheCurve() {
        deposit("100000");
        book.open(ALICE, DEEP_GUNPOWDER, snapshot(DEEP_GUNPOWDER, 100, 100), Side.LONG, 12, 10);

        // 卖出 12 手：99.975 × (5 + 5 × (1 − g) + 2 × (1 − g)²) / 12 = 99.9513…
        PlayerTrade trade = filled(book.close(ALICE, DEEP_GUNPOWDER, snapshot(DEEP_GUNPOWDER, 100, 100), 12));

        assertEquals(99.9513, trade.price(), 5e-5);
        assertTrue(trade.price() < 99.975);
    }

    @Test
    @DisplayName("成交价与盘口一致：成交价 = LiquidityCurve 按同样数量算出的均价，买入侧的平空也走卖盘")
    void fillPriceIsTheCurveAverage() {
        MarketSnapshot at = snapshot(DEEP_DIAMOND, 1000, 1000);
        var curve = DEEP_DIAMOND.liquidityCurve(1000);

        assertEquals(curve.averageFillPrice(true, 3200), TradingBook.fillPrice(DEEP_DIAMOND, at, Side.LONG, TradeKind.OPEN, 3200));
        assertEquals(curve.averageFillPrice(false, 3200), TradingBook.fillPrice(DEEP_DIAMOND, at, Side.SHORT, TradeKind.OPEN, 3200));
        assertEquals(curve.averageFillPrice(false, 3200), TradingBook.fillPrice(DEEP_DIAMOND, at, Side.LONG, TradeKind.CLOSE, 3200));
        assertEquals(curve.averageFillPrice(true, 3200), TradingBook.fillPrice(DEEP_DIAMOND, at, Side.SHORT, TradeKind.CLOSE, 3200));
    }

    @Test
    @DisplayName("滑点是临时冲击：一个玩家的大单不改变快照里的最新价格，另一个玩家随后的小单仍按买一价成交")
    void slippageDoesNotMoveThePrice() {
        UUID bob = new UUID(0, 2);
        deposit("1000000");
        accounts.deposit(bob, Money.parse("1000"));
        MarketSnapshot at = snapshot(DEEP_DIAMOND, 1000, 1000);

        PlayerTrade big = filled(book.open(ALICE, DEEP_DIAMOND, at, Side.LONG, 20000, 10));
        PlayerTrade small = filled(book.open(bob, DEEP_DIAMOND, at, Side.LONG, 100, 10));

        assertTrue(big.price() > 1000.25, "大单有滑点：" + big.price());
        assertEquals(1000.25, small.price(), "别人的大单没有挪动价格");
        assertEquals(1000.0, at.lastPrice());
    }

    @Test
    @DisplayName("大到算不出价格的数量被拒绝，而不是成交在无穷大的价格上")
    void absurdQuantityIsRejected() {
        deposit("1000");

        TradeResult result = book.open(ALICE, DEEP_GUNPOWDER, snapshot(DEEP_GUNPOWDER, 100, 100), Side.LONG, Long.MAX_VALUE, 10);

        assertInstanceOf(TradeResult.Rejected.class, result);
        assertEquals(Money.parse("1000"), accounts.balance(ALICE));
    }

    // ---- 存档 ----
    @Test
    @DisplayName("交易账的状态（池子、仓位、成交历史、成交编号）可导出并恢复，恢复后接着编号")
    void stateRoundTrips() {
        deposit("1000");
        book.open(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), Side.LONG, 2000, 10);
        book.open(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 100, 100), Side.SHORT, 5, 5);
        book.close(ALICE, GUNPOWDER, snapshot(GUNPOWDER, 99, 99), 2);

        TradingBook restored = TradingBook.restore(accounts, book.state());

        assertEquals(book.pool().balance(), restored.pool().balance());
        assertEquals(book.position(ALICE, "DIAMONDCRP"), restored.position(ALICE, "DIAMONDCRP"));
        assertEquals(book.position(ALICE, "GUNPOWDERCRP"), restored.position(ALICE, "GUNPOWDERCRP"));
        assertEquals(book.trades(ALICE), restored.trades(ALICE));
        PlayerTrade next = filled(restored.close(ALICE, DIAMOND, snapshot(DIAMOND, 1000, 1000), 2000));
        assertEquals(4, next.id());
    }
}
