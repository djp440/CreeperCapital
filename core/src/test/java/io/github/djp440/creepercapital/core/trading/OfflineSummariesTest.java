package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.EventType;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.Summary;

/**
 * 离线事件汇总（#19）：累加、取走即清空、存档往返，以及四类真实事件记进汇总后，金额与各历史页的明细（成交历史、资金费记录）合计一致。
 * 钻石：点差 0.5、数量精度 3 位（2000 手 = 2.000 个），无限深，成交价恰好是最新价 ± 半个点差。期望值全部手算。
 */
class OfflineSummariesTest {
    private static final ContractSpec DIAMOND = unlimitedDepth(ContractSpec.defaults().get(0));
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);
    private static final Function<String, MarginTiers> TIERS = symbol -> DIAMOND.marginTiers();

    private AccountBook accounts;
    private LiquidityPool pool;
    private TradingBook book;
    private OfflineSummaries summaries;

    @BeforeEach
    void setUp() {
        accounts = new AccountBook();
        pool = new LiquidityPool();
        book = new TradingBook(accounts, pool);
        summaries = new OfflineSummaries();
    }

    private static ContractSpec unlimitedDepth(ContractSpec spec) {
        return new ContractSpec(spec.symbol(), spec.underlying(), spec.initialPrice(), spec.baseVolume(), spec.volatility(),
                spec.spread(), spec.maxLeverage(), spec.quantityDecimals(), 0.0);
    }

    private static MarketSnapshot snapshot(double last, double mark, long step, double rate) {
        return new MarketSnapshot(step, DIAMOND.symbol(), last, last, mark, rate, 0);
    }

    private static MarketSnapshot snapshot(double last) {
        return snapshot(last, last, 100, 0.0001);
    }

    private void openLong() {
        accounts.deposit(ALICE, Money.parse("100000"));
        assertInstanceOf(TradeResult.Filled.class, book.open(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10));
    }

    /** 同一名玩家成交历史里某类成交的已实现盈亏合计（明细一侧）。 */
    private Money realized(UUID player, TradeKind kind) {
        Money total = Money.ZERO;
        for (PlayerTrade trade : book.trades(player)) {
            if (trade.kind() == kind) {
                total = total.plus(trade.realizedPnl());
            }
        }
        return total;
    }

    // ---- 累加、取走、存档 ----

    @Test
    @DisplayName("同类事件按合约累加次数、金额与最早 / 最近发生时间，条目按类型序号与合约代码排序")
    void recordsAccumulatePerTypeAndSymbol() {
        summaries.record(ALICE, EventType.FUNDING, "GUNPOWDERCRP", Money.parse("0.5"), 50);
        summaries.record(ALICE, EventType.LIQUIDATION, "DIAMONDCRP", Money.parse("-192.9"), 600);
        summaries.record(ALICE, EventType.FUNDING, "DIAMONDCRP", Money.parse("-0.22"), 100);
        summaries.record(ALICE, EventType.LIQUIDATION, "DIAMONDCRP", Money.parse("-7.1"), 400);
        summaries.record(ALICE, EventType.FUNDING, "DIAMONDCRP", Money.parse("0.1"), 300);

        Summary summary = summaries.pending(ALICE);

        assertEquals(List.of(EventType.LIQUIDATION, EventType.FUNDING, EventType.FUNDING),
                summary.entries().stream().map(OfflineSummaries.Entry::type).toList());
        assertEquals(List.of("DIAMONDCRP", "DIAMONDCRP", "GUNPOWDERCRP"),
                summary.entries().stream().map(OfflineSummaries.Entry::symbol).toList());
        OfflineSummaries.Entry liquidation = summary.entries().get(0);
        assertEquals(2, liquidation.count());
        assertEquals(Money.parse("-200"), liquidation.amount());
        assertEquals(400, liquidation.firstSeconds());
        assertEquals(600, liquidation.lastSeconds());
        // 资金费：钻石 −0.22 + 0.1 = −0.12，火药 +0.5；所有合约合计 0.38
        assertEquals(2, summary.entries().get(1).count());
        assertEquals(Money.parse("-0.12"), summary.entries().get(1).amount());
        assertEquals(3, summary.count(EventType.FUNDING));
        assertEquals(Money.parse("0.38"), summary.amount(EventType.FUNDING));
        assertEquals(0, summary.count(EventType.TRIGGER));
        assertEquals(Money.ZERO, summary.amount(EventType.TRIGGER));
    }

    @Test
    @DisplayName("取走即清空：只清这名玩家，再取是空的；没有事件的玩家汇总为空")
    void takeClearsOnlyThatPlayer() {
        summaries.record(ALICE, EventType.ORDER_FILL, "DIAMONDCRP", Money.parse("1980"), 10);
        summaries.record(BOB, EventType.ORDER_FILL, "DIAMONDCRP", Money.parse("980"), 20);
        assertTrue(summaries.hasPending(ALICE));
        assertTrue(summaries.pending(new UUID(0, 3)).isEmpty());

        Summary taken = summaries.take(ALICE);

        assertEquals(1, taken.count(EventType.ORDER_FILL));
        assertEquals(Money.parse("1980"), taken.amount(EventType.ORDER_FILL));
        assertFalse(summaries.hasPending(ALICE));
        assertTrue(summaries.take(ALICE).isEmpty());
        assertEquals(Money.parse("980"), summaries.pending(BOB).amount(EventType.ORDER_FILL));
    }

    @Test
    @DisplayName("存档状态往返不丢条目，之后接着累加")
    void stateRoundTrips() {
        summaries.record(ALICE, EventType.LIQUIDATION, "DIAMONDCRP", Money.parse("-192.9"), 600);
        summaries.record(ALICE, EventType.TRIGGER, "DIAMONDCRP", Money.parse("199"), 720);
        summaries.record(BOB, EventType.FUNDING, "DIAMONDCRP", Money.parse("0.22"), 100);
        summaries.take(BOB);

        OfflineSummaries restored = OfflineSummaries.restore(summaries.state());

        assertEquals(summaries.state(), restored.state());
        assertEquals(Map.of(ALICE, summaries.pending(ALICE).entries()), restored.state(), "已取走的玩家不在状态里");
        restored.record(ALICE, EventType.TRIGGER, "DIAMONDCRP", Money.parse("1"), 800);
        assertEquals(2, restored.pending(ALICE).count(EventType.TRIGGER));
        assertEquals(Money.parse("200"), restored.pending(ALICE).amount(EventType.TRIGGER));
    }

    @Test
    @DisplayName("金额溢出时抛出且汇总不变")
    void overflowLeavesTheSummaryUntouched() {
        Money max = Money.ofUnits(Long.MAX_VALUE);
        summaries.record(ALICE, EventType.FUNDING, "DIAMONDCRP", max, 10);

        org.junit.jupiter.api.Assertions.assertThrows(ArithmeticException.class,
                () -> summaries.record(ALICE, EventType.FUNDING, "DIAMONDCRP", Money.parse("1"), 20));

        assertEquals(1, summaries.pending(ALICE).count(EventType.FUNDING));
        assertEquals(max, summaries.pending(ALICE).amount(EventType.FUNDING));
    }

    // ---- 四类真实事件：汇总金额 = 明细合计 ----

    @Test
    @DisplayName("强平：每个被平仓位记一次，金额 = 成交历史里强平行的已实现盈亏合计")
    void liquidationMatchesTheTradeHistory() {
        accounts.deposit(ALICE, Money.parse("1000"));
        assertInstanceOf(TradeResult.Filled.class, book.open(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10));
        ToDoubleFunction<String> mark = symbol -> 903.8;

        List<LiquidationEvent> events = book.checkLiquidations(mark, TIERS, 600);
        events.forEach(summaries::record);

        // 903.8：盈亏 (903.8 − 1000.25) × 2.000 = −192.9
        assertEquals(1, events.size());
        Summary summary = summaries.pending(ALICE);
        assertEquals(1, summary.count(EventType.LIQUIDATION));
        assertEquals(Money.parse("-192.9"), summary.amount(EventType.LIQUIDATION));
        assertEquals(realized(ALICE, TradeKind.LIQUIDATION), summary.amount(EventType.LIQUIDATION));
        assertEquals(600, summary.entries().get(0).lastSeconds());
    }

    @Test
    @DisplayName("资金费：金额 = 资金费记录的带符号金额合计（多头付为负）")
    void fundingMatchesTheFundingRecords() {
        openLong();

        // 2.000 × 1100 × 0.01% = 0.22，每次结算多头付 0.22
        book.settleFunding(snapshot(1100, 1100, 4800, 0.0001)).forEach(summaries::record);
        book.settleFunding(snapshot(1100, 1100, 9600, 0.0001)).forEach(summaries::record);

        Summary summary = summaries.pending(ALICE);
        assertEquals(2, summary.count(EventType.FUNDING));
        assertEquals(Money.parse("-0.44"), summary.amount(EventType.FUNDING));
        Money details = Money.ZERO;
        for (FundingRecord record : book.fundingRecords(ALICE)) {
            details = details.plus(record.amount());
        }
        assertEquals(details, summary.amount(EventType.FUNDING));
    }

    @Test
    @DisplayName("限价成交：每张成交的委托记一次，金额 = 成交额；撤单不记")
    void limitFillsAreCountedByTurnover() {
        accounts.deposit(ALICE, Money.parse("100000"));
        orders().placeLimit(ALICE, DIAMOND, snapshot(1000), Side.LONG, 2000, 10, 990);
        orders().placeLimit(ALICE, DIAMOND, snapshot(1000), Side.LONG, 1000, 10, 980);
        Order doomed = ((TradeResult.Placed) orders().placeLimit(ALICE, DIAMOND, snapshot(1000), Side.LONG, 1000, 10, 900))
                .order();

        List<OrderEvent> events = new ArrayList<>(orders().onSnapshot(DIAMOND, snapshot(979.5)));
        events.add(orders().cancel(ALICE, doomed.id(), 900).orElseThrow());
        int recorded = 0;
        for (OrderEvent event : events) {
            recorded += summaries.record(event, 3) ? 1 : 0;
        }

        // 990 × 2.000 + 980 × 1.000 = 1980 + 980 = 2960
        assertEquals(2, recorded, "两张成交，撤单那条不记");
        Summary summary = summaries.pending(ALICE);
        assertEquals(2, summary.count(EventType.ORDER_FILL));
        assertEquals(Money.parse("2960"), summary.amount(EventType.ORDER_FILL));
        BigDecimal details = BigDecimal.ZERO;
        for (PlayerTrade trade : book.trades(ALICE)) {
            details = details.add(BigDecimal.valueOf(trade.price()).multiply(BigDecimal.valueOf(trade.lots())).movePointLeft(3));
        }
        assertEquals(0, details.compareTo(new BigDecimal("2960")), "成交历史里每笔 价格 × 数量 的合计");
        assertEquals(0, summary.amount(EventType.TRIGGER).units());
    }

    @Test
    @DisplayName("止盈止损：触发并成交的记一次，金额 = 成交历史里平仓行的已实现盈亏；触发和失效的兄弟单不记")
    void takeProfitAndStopLossCountByRealizedPnl() {
        openLong();
        orders().placeProtection(ALICE, DIAMOND, snapshot(1000), 2000, new Protection(1100, 900, TriggerSource.LAST));

        List<OrderEvent> events = orders().onSnapshot(DIAMOND, snapshot(1100, 1000, 120, 0.0001));
        int recorded = 0;
        for (OrderEvent event : events) {
            recorded += summaries.record(event, 3) ? 1 : 0;
        }

        // 止盈 1100：成交价 1099.75，盈亏 (1099.75 − 1000.25) × 2.000 = 199；事件有 触发 / 成交 / 兄弟止损失效 三条
        assertEquals(3, events.size());
        assertEquals(1, recorded);
        Summary summary = summaries.pending(ALICE);
        assertEquals(1, summary.count(EventType.TRIGGER));
        assertEquals(Money.parse("199"), summary.amount(EventType.TRIGGER));
        assertEquals(realized(ALICE, TradeKind.CLOSE), summary.amount(EventType.TRIGGER));
        assertEquals(0, summary.count(EventType.ORDER_FILL));
    }

    @Test
    @DisplayName("止损触发的亏损为负数")
    void stopLossRecordsTheLoss() {
        openLong();
        orders().placeProtection(ALICE, DIAMOND, snapshot(1000), 2000, new Protection(0, 950, TriggerSource.LAST));

        orders().onSnapshot(DIAMOND, snapshot(949.9)).forEach(event -> summaries.record(event, 3));

        // 止损成交价 949.9 − 0.25 = 949.65，盈亏 (949.65 − 1000.25) × 2.000 = −101.2（没超过保证金 200.05，不会被封顶）
        assertEquals(Money.parse("-101.2"), summaries.pending(ALICE).amount(EventType.TRIGGER));
        assertEquals(realized(ALICE, TradeKind.CLOSE), summaries.pending(ALICE).amount(EventType.TRIGGER));
    }

    private OrderBook orders() {
        return book.orders();
    }
}
