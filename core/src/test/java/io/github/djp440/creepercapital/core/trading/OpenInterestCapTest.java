package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 持仓总量上限（#17）：某合约全体玩家未平仓名义价值之和（按成交价）不得超过 流动性池权益 × 比例，平仓不受限。
 * 钻石：点差 0.5、数量精度 3 位（1 手 = 0.001 个），无限深（成交价恰好是最新价 ± 0.25）。
 * 比例取 0.5，池子取 2,000 CRP，所以上限约 1,000 CRP，约一个钻石；杠杆取 2 倍，避开单仓位的杠杆分档上限。
 * 最新价 1000 时开多的成交价是 1000.25，开空是 999.75。
 */
class OpenInterestCapTest {
    private static final ContractSpec DIAMOND = unlimitedDepth(ContractSpec.defaults().get(0));
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);

    private AccountBook accounts;
    private LiquidityPool pool;
    private TradingBook book;

    @BeforeEach
    void setUp() {
        accounts = new AccountBook();
        pool = new LiquidityPool(Money.parse("2000"));
        book = new TradingBook(accounts, pool);
        accounts.deposit(ALICE, Money.parse("100000"));
        accounts.deposit(BOB, Money.parse("100000"));
    }

    private static ContractSpec unlimitedDepth(ContractSpec spec) {
        return new ContractSpec(spec.symbol(), spec.underlying(), spec.initialPrice(), spec.baseVolume(), spec.volatility(),
                spec.spread(), spec.maxLeverage(), spec.quantityDecimals(), 0.0);
    }

    private static MarketSnapshot snapshot(double last) {
        return new MarketSnapshot(100, "DIAMONDCRP", last, last, last, 0.0001, 0);
    }

    private static ToDoubleFunction<String> marks(double diamond) {
        return symbol -> symbol.equals("DIAMONDCRP") ? diamond : Double.NaN;
    }

    private TradeResult open(UUID player, Side side, long lots, double last) {
        return book.open(player, DIAMOND, snapshot(last), side, lots, 2, marks(last));
    }

    private static TradeResult.Rejected rejected(TradeResult result) {
        return assertInstanceOf(TradeResult.Rejected.class, result);
    }

    @Test
    @DisplayName("池子权益 = 池子现金 − 全体玩家的未实现盈亏；没有仓位时就是池子余额")
    void equityIsCashMinusUnrealizedPnl() {
        assertEquals(Money.parse("2000"), book.poolEquity(marks(1000)));

        assertInstanceOf(TradeResult.Filled.class, open(ALICE, Side.LONG, 900, 1000));
        // 开仓费 900.225 × 0.05% = 0.4501125 → 0.450113 进池子；标记价格 1100 时多头浮盈 (1100 − 1000.25) × 0.9 = 89.775
        assertEquals(Money.parse("2000.450113"), pool.balance());
        assertEquals(Money.parse("1910.675113"), book.poolEquity(marks(1100)));
        // 标记价格 900 时浮亏 90.225（未超过保证金 450.1125），池子权益反而更大
        assertEquals(Money.parse("2090.675113"), book.poolEquity(marks(900)));
    }

    @Test
    @DisplayName("逐仓浮亏最多算到保证金：池子最多收到这么多")
    void lossesCountedUpToTheMargin() {
        assertInstanceOf(TradeResult.Filled.class, open(ALICE, Side.LONG, 900, 1000));
        // 保证金 ceil(900.225 / 2) = 450.1125；价格跌到 0 浮亏 900.2250 超过保证金，只算 450.1125
        assertEquals(Money.parse("2450.562613"), book.poolEquity(marks(0.0000001)));
    }

    @Test
    @DisplayName("上限 = 池子权益 × 比例：池子权益随玩家盈亏变化，上限同步变化")
    void capFollowsThePoolEquity() {
        assertEquals(Money.parse("1000"), book.openInterestCap(DIAMOND, marks(1000)));

        assertInstanceOf(TradeResult.Filled.class, open(ALICE, Side.LONG, 900, 1000));
        // 权益 1910.675113 的一半，向下取整到最小金额单位
        assertEquals(Money.parse("955.337556"), book.openInterestCap(DIAMOND, marks(1100)));
        // 玩家亏损时池子权益变大，上限随之变大
        assertEquals(Money.parse("1045.337556"), book.openInterestCap(DIAMOND, marks(900)));
    }

    @Test
    @DisplayName("开仓后全体玩家的名义价值会超过上限：被拒并给出上限；刚好不超过则放行")
    void openingBeyondTheCapIsRejected() {
        assertInstanceOf(TradeResult.Filled.class, open(ALICE, Side.LONG, 900, 1000));

        // 标记价格 1000 时爱丽丝浮亏 0.225，池子权益 2000.450113 + 0.225，上限约 1000.34；名义价值按这一笔的成交价估值
        // 鲍勃开空 100 手（成交价 999.75）：(900 + 100) 手 × 999.75 = 999.75 ≤ 上限，放行
        assertInstanceOf(TradeResult.Filled.class, open(BOB, Side.SHORT, 100, 1000));
        // 再开 1 手：1001 手 × 999.75 = 1000.74975 > 上限，被拒
        TradeResult.Rejected rejected = rejected(open(BOB, Side.SHORT, 1, 1000));
        assertEquals(TradeResult.Reason.OPEN_INTEREST_LIMIT, rejected.reason());
        assertEquals(List.of(book.openInterestCap(DIAMOND, marks(1000)).toDisplayString()), rejected.args());
        assertEquals(100, book.position(BOB, "DIAMONDCRP").orElseThrow().lots(), "被拒的委托不改变仓位");
    }

    @Test
    @DisplayName("加仓也受上限约束")
    void addingToAPositionCountsToo() {
        assertInstanceOf(TradeResult.Filled.class, open(ALICE, Side.LONG, 900, 1000));
        assertEquals(TradeResult.Reason.OPEN_INTEREST_LIMIT, rejected(open(ALICE, Side.LONG, 200, 1000)).reason());
        assertInstanceOf(TradeResult.Filled.class, open(ALICE, Side.LONG, 50, 1000));
    }

    @Test
    @DisplayName("被拒的开仓不动账：玩家、池子、仓位都不变")
    void rejectedOpenLeavesTheBooksUntouched() {
        Money balance = accounts.balance(ALICE);
        Money poolBefore = pool.balance();

        assertEquals(TradeResult.Reason.OPEN_INTEREST_LIMIT, rejected(open(ALICE, Side.LONG, 2000, 1000)).reason());

        assertEquals(balance, accounts.balance(ALICE));
        assertEquals(poolBefore, pool.balance());
        assertTrue(book.positions(ALICE).isEmpty());
        assertTrue(book.trades(ALICE).isEmpty());
    }

    @Test
    @DisplayName("上限因池子权益下降而低于现有持仓时：平仓和减仓不受限，新开仓被拒")
    void closingAndReducingAreNeverBlocked() {
        assertInstanceOf(TradeResult.Filled.class, open(ALICE, Side.LONG, 900, 1000));
        assertInstanceOf(TradeResult.Filled.class, open(BOB, Side.LONG, 99, 1000));
        // 价格涨到 1500：玩家浮盈 (1500 − 1000.25) × 0.999 ≈ 499 把池子权益压到约 1500，上限约 750，现有持仓约 1500
        double high = 1500;
        assertTrue(book.openInterest(DIAMOND, high).compareTo(book.openInterestCap(DIAMOND, marks(high))) > 0,
                "现有持仓已超过现在的上限");

        assertEquals(TradeResult.Reason.OPEN_INTEREST_LIMIT,
                rejected(book.open(BOB, DIAMOND, snapshot(high), Side.LONG, 1, 2, marks(high))).reason());
        assertInstanceOf(TradeResult.Filled.class, book.close(BOB, DIAMOND, snapshot(high), 40), "减仓不受限");
        assertInstanceOf(TradeResult.Filled.class, book.close(ALICE, DIAMOND, snapshot(high), 900), "平仓不受限");
        assertInstanceOf(TradeResult.Filled.class, book.close(BOB, DIAMOND, snapshot(high), 59));
    }

    @Test
    @DisplayName("每个合约各自限额：占满钻石的额度不影响火药")
    void eachContractHasItsOwnCap() {
        ContractSpec gunpowder = unlimitedDepth(ContractSpec.defaults().get(1));
        assertInstanceOf(TradeResult.Filled.class, open(ALICE, Side.LONG, 990, 1000));
        assertEquals(TradeResult.Reason.OPEN_INTEREST_LIMIT, rejected(open(ALICE, Side.LONG, 100, 1000)).reason());

        MarketSnapshot gun = new MarketSnapshot(100, "GUNPOWDERCRP", 100, 100, 100, 0.0001, 0);
        // 火药 5 手 = 5 个，名义价值约 500，在它自己的上限（约 1000）以内
        assertInstanceOf(TradeResult.Filled.class,
                book.open(ALICE, gunpowder, gun, Side.LONG, 5, 2, symbol -> symbol.equals("DIAMONDCRP") ? 1000 : 100));
    }

    @Test
    @DisplayName("比例可按合约配置：比例 0.25 时上限减半")
    void ratioIsPerContract() {
        ContractSpec quarter = new ContractSpec(DIAMOND.symbol(), DIAMOND.underlying(), DIAMOND.initialPrice(),
                DIAMOND.baseVolume(), DIAMOND.volatility(), DIAMOND.spread(), DIAMOND.maxLeverage(), DIAMOND.quantityDecimals(),
                0.0, null, 0.25);
        assertEquals(Money.parse("500"), book.openInterestCap(quarter, marks(1000)));
        assertEquals(Money.parse("1000"), book.openInterestCap(DIAMOND, marks(1000)));
    }

    @Test
    @DisplayName("池子权益为负时上限为 0，所有开仓被拒")
    void negativeEquityMeansNoOpening() {
        assertInstanceOf(TradeResult.Filled.class, open(ALICE, Side.LONG, 900, 1000));
        double huge = 5000;
        assertTrue(book.poolEquity(marks(huge)).units() < 0);
        assertEquals(Money.ZERO, book.openInterestCap(DIAMOND, marks(huge)));
        assertEquals(TradeResult.Reason.OPEN_INTEREST_LIMIT,
                rejected(book.open(BOB, DIAMOND, snapshot(huge), Side.SHORT, 1, 2, marks(huge))).reason());
    }
}
