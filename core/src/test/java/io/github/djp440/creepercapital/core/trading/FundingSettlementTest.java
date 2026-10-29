package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 资金费结算：每 8 市场小时（每 4800 个行情步）在持仓玩家与流动性池之间按「名义价值 × 费率」收付。
 * 钻石：点差 0.5、数量精度 3 位、125x；2.000 个钻石在最新价 1000 开多：成交价 1000.25，名义价值 2000.5，
 * 10 倍保证金 200.05，手续费 1.00025；开空成交价 999.75，名义价值 1999.5，保证金 199.95，手续费 0.99975。
 * 结算时按标记价格 1100 计，名义价值 2.000 × 1100 = 2200，费率 0.01% 时资金费 0.22。期望值全部手算。
 */
class FundingSettlementTest {
    private static final ContractSpec DIAMOND = unlimitedDepth(ContractSpec.defaults().get(0));
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    /** 第一个结算点：8 市场小时 = 28800 市场秒 = 4800 个行情步。 */
    private static final long SETTLEMENT_STEP = 4800;

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

    private static MarketSnapshot at(long step, double last, double mark, double rate) {
        return new MarketSnapshot(step, DIAMOND.symbol(), last, last, mark, rate, 0);
    }

    private void open(UUID player, Side side, MarginMode mode) {
        accounts.deposit(player, Money.parse("1000"));
        if (mode == MarginMode.CROSS) {
            assertInstanceOf(MarginResult.Applied.class, book.setMarginMode(player, DIAMOND.symbol(), MarginMode.CROSS));
        }
        assertInstanceOf(TradeResult.Filled.class,
                book.open(player, DIAMOND, at(100, 1000, 1000, 0.0001), side, 2000, 10));
    }

    private Money margin(UUID player) {
        return book.position(player, DIAMOND.symbol()).orElseThrow().margin();
    }

    @Test
    void longPaysPoolWhenRateIsPositive() {
        open(ALICE, Side.LONG, MarginMode.ISOLATED);
        Money poolBefore = pool.balance();

        List<FundingRecord> records = book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, 0.0001));

        assertEquals(1, records.size());
        FundingRecord record = records.get(0);
        assertEquals(ALICE, record.player());
        assertEquals("DIAMONDCRP", record.symbol());
        assertEquals(Side.LONG, record.side());
        assertEquals(MarginMode.ISOLATED, record.mode());
        assertEquals(0.0001, record.rate());
        assertEquals(1100.0, record.markPrice());
        assertEquals(Money.parse("2200"), record.notional());
        assertEquals(Money.parse("-0.22"), record.amount());
        assertEquals(Money.ZERO, record.shortfall());
        assertEquals(SETTLEMENT_STEP * 6, record.marketSeconds());
        // 逐仓：从仓位保证金里扣，池子收到；可用余额不动
        assertEquals(Money.parse("199.83"), margin(ALICE));
        assertEquals(poolBefore.plus(Money.parse("0.22")), pool.balance());
        assertEquals(Money.parse("798.94975"), accounts.balance(ALICE));
    }

    @Test
    void shortReceivesFromPoolWhenRateIsPositive() {
        open(ALICE, Side.SHORT, MarginMode.ISOLATED);
        Money poolBefore = pool.balance();

        FundingRecord record = book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, 0.0001)).get(0);

        assertEquals(Side.SHORT, record.side());
        assertEquals(Money.parse("0.22"), record.amount());
        assertEquals(Money.parse("200.17"), margin(ALICE));
        assertEquals(poolBefore.minus(Money.parse("0.22")), pool.balance());
    }

    @Test
    void negativeRateMakesShortPayAndLongReceive() {
        open(ALICE, Side.LONG, MarginMode.ISOLATED);
        open(BOB, Side.SHORT, MarginMode.ISOLATED);
        Money poolBefore = pool.balance();

        List<FundingRecord> records = book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, -0.0001));

        assertEquals(2, records.size());
        assertEquals(Money.parse("0.22"), book.fundingRecords(ALICE).get(0).amount());
        assertEquals(Money.parse("-0.22"), book.fundingRecords(BOB).get(0).amount());
        // 多空各 2.000，一收一付，池子净额为零
        assertEquals(poolBefore, pool.balance());
    }

    @Test
    void amountUsesMarkPriceAtSettlementNotLastPriceOrEntry() {
        open(ALICE, Side.LONG, MarginMode.ISOLATED);

        FundingRecord record = book.settleFunding(at(SETTLEMENT_STEP, 1500, 1200, 0.0003)).get(0);

        // 2.000 × 标记价格 1200 = 2400；× 0.03% = 0.72
        assertEquals(Money.parse("2400"), record.notional());
        assertEquals(Money.parse("-0.72"), record.amount());
    }

    @Test
    void crossTakesFromBalanceAndLeavesMarginAlone() {
        open(ALICE, Side.LONG, MarginMode.CROSS);
        Money marginBefore = margin(ALICE);

        FundingRecord record = book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, 0.0001)).get(0);

        assertEquals(MarginMode.CROSS, record.mode());
        assertEquals(Money.parse("-0.22"), record.amount());
        assertEquals(marginBefore, margin(ALICE));
        assertEquals(Money.parse("798.72975"), accounts.balance(ALICE));
    }

    @Test
    void crossReceivesIntoBalance() {
        open(ALICE, Side.LONG, MarginMode.CROSS);

        book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, -0.0001));

        assertEquals(Money.parse("799.16975"), accounts.balance(ALICE));
    }

    @Test
    void settlesOnlyAtSettlementSteps() {
        open(ALICE, Side.LONG, MarginMode.ISOLATED);
        Money poolBefore = pool.balance();

        for (long step : new long[] {1, 100, SETTLEMENT_STEP - 1, SETTLEMENT_STEP + 1, 2 * SETTLEMENT_STEP - 1}) {
            assertTrue(book.settleFunding(at(step, 1100, 1100, 0.0001)).isEmpty(), "第 " + step + " 步不是结算点");
        }
        assertEquals(poolBefore, pool.balance());
        assertTrue(book.fundingRecords(ALICE).isEmpty());
        // 第二、第三个结算点各结算一次
        assertEquals(1, book.settleFunding(at(2 * SETTLEMENT_STEP, 1100, 1100, 0.0001)).size());
        assertEquals(1, book.settleFunding(at(3 * SETTLEMENT_STEP, 1100, 1100, 0.0001)).size());
        assertEquals(2, book.fundingRecords(ALICE).size());
    }

    @Test
    void positionsInOtherContractsAndPlayersWithoutPositionsAreUntouched() {
        open(ALICE, Side.LONG, MarginMode.ISOLATED);
        accounts.deposit(BOB, Money.parse("500"));
        MarketSnapshot other = new MarketSnapshot(SETTLEMENT_STEP, "GUNPOWDERCRP", 100, 100, 100, 0.0001, 0);

        assertTrue(book.settleFunding(other).isEmpty());
        book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, 0.0001));

        assertTrue(book.fundingRecords(BOB).isEmpty());
        assertEquals(Money.parse("500"), accounts.balance(BOB));
    }

    @Test
    void isolatedMarginIsDrainedToZeroNeverNegative() {
        open(ALICE, Side.LONG, MarginMode.ISOLATED);

        // 2200 × 20% = 440 > 保证金 200.05：扣到 0 为止，差额 239.95 记为未收足
        FundingRecord record = book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, 0.2)).get(0);

        assertEquals(Money.parse("-200.05"), record.amount());
        assertEquals(Money.parse("239.95"), record.shortfall());
        assertEquals(Money.ZERO, margin(ALICE));
        assertEquals(Money.parse("798.94975"), accounts.balance(ALICE), "逐仓不能动可用余额");
        assertTrue(book.position(ALICE, DIAMOND.symbol()).isPresent(), "仓位留给强平检查处理");
    }

    @Test
    void crossBalanceIsDrainedToZeroNeverNegative() {
        open(ALICE, Side.LONG, MarginMode.CROSS);

        // 2200 × 50% = 1100 > 可用余额 798.94975
        FundingRecord record = book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, 0.5)).get(0);

        assertEquals(Money.parse("-798.94975"), record.amount());
        assertEquals(Money.parse("301.05025"), record.shortfall());
        assertEquals(Money.ZERO, accounts.balance(ALICE));
    }

    @Test
    void poolPaysAtMostItsBalance() {
        open(ALICE, Side.SHORT, MarginMode.ISOLATED);
        pool.payUpTo(pool.balance().minus(Money.parse("0.1")));

        FundingRecord record = book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, 0.0001)).get(0);

        assertEquals(Money.parse("0.1"), record.amount());
        assertEquals(Money.parse("0.12"), record.shortfall());
        assertEquals(Money.ZERO, pool.balance());
        assertEquals(Money.parse("199.95").plus(Money.parse("0.1")), margin(ALICE));
    }

    @Test
    void recordsAreNumberedAndKeepOnlyTheLatestPerPlayer() {
        open(ALICE, Side.LONG, MarginMode.CROSS);
        int settlements = TradingBook.MAX_FUNDING_PER_PLAYER + 50;
        for (int i = 1; i <= settlements; i++) {
            book.settleFunding(at(i * SETTLEMENT_STEP, 1100, 1100, 0.0001));
        }

        List<FundingRecord> kept = book.fundingRecords(ALICE);
        assertEquals(TradingBook.MAX_FUNDING_PER_PLAYER, kept.size());
        assertEquals(51, kept.get(0).id(), "最旧的 50 条被淘汰");
        assertEquals(settlements, kept.get(kept.size() - 1).id());
        assertEquals(settlements, book.state().lastFundingId());
    }

    @Test
    void recordsSurviveSaveAndRestore() {
        open(ALICE, Side.LONG, MarginMode.ISOLATED);
        book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, 0.0001));

        TradingBook restored = TradingBook.restore(accounts, book.state());

        assertEquals(book.fundingRecords(ALICE), restored.fundingRecords(ALICE));
        assertEquals(Money.parse("199.83"), restored.position(ALICE, DIAMOND.symbol()).orElseThrow().margin());
        restored.settleFunding(at(2 * SETTLEMENT_STEP, 1100, 1100, 0.0001));
        assertEquals(List.of(1L, 2L), restored.fundingRecords(ALICE).stream().map(FundingRecord::id).toList(),
                "恢复后编号接着往下");
    }

    @Test
    void legacyStateWithoutFundingLoads() {
        TradingState legacy = new TradingState(LiquidityPool.INITIAL_BALANCE, 0, java.util.Map.of(), java.util.Map.of());

        TradingBook restored = TradingBook.restore(accounts, legacy);

        assertTrue(restored.fundingRecords(ALICE).isEmpty());
        assertFalse(restored.state().lastFundingId() > 0);
    }

    @Test
    void positionClosedBeforeSettlementPaysNothing() {
        open(ALICE, Side.LONG, MarginMode.ISOLATED);
        assertInstanceOf(TradeResult.Filled.class, book.close(ALICE, DIAMOND, at(200, 1000, 1000, 0.0001), 2000));

        assertTrue(book.settleFunding(at(SETTLEMENT_STEP, 1100, 1100, 0.0001)).isEmpty());
    }
}
