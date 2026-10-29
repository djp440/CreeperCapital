package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 用真实的行情推进验证结算节奏：每 8 市场小时（4800 个行情步）恰好结算一次，且就是行情栏倒计时走到 00:00:00 的那一步；
 * 停服重启（市场状态存档恢复）后结算周期不错位，停服期间不补结算。
 */
class FundingScheduleTest {
    private static final List<ContractSpec> SPECS = ContractSpec.defaults();
    private static final ContractSpec DIAMOND = SPECS.get(0);
    private static final UUID ALICE = new UUID(0, 1);
    private static final long SEED = 20260101L;
    private static final long STEPS_PER_PERIOD = 4800;

    private static TradingBook bookWithLongPosition(Market market, AccountBook accounts) {
        accounts.deposit(ALICE, Money.parse("10000"));
        TradingBook book = new TradingBook(accounts, new LiquidityPool());
        assertInstanceOf(TradeResult.Filled.class,
                book.open(ALICE, DIAMOND, market.snapshot(DIAMOND.symbol()), Side.LONG, 1000, 5));
        return book;
    }

    /** 推进到第 {@code toStep} 步（含），每一步的快照都交给结算；返回这段时间的结算，并记下结算时倒计时是否为 0。 */
    private static List<FundingRecord> run(Market market, TradingBook book, long toStep, List<Long> settlementSteps) {
        List<FundingRecord> all = new ArrayList<>();
        while (market.snapshot(DIAMOND.symbol()).step() < toStep) {
            for (MarketSnapshot snapshot : market.advanceSteps(1)) {
                List<FundingRecord> settled = book.settleFunding(snapshot);
                if (!settled.isEmpty() && snapshot.symbol().equals(DIAMOND.symbol())) {
                    settlementSteps.add(snapshot.step());
                    assertEquals(0, snapshot.secondsUntilFunding(), "结算那一步行情栏倒计时应为 00:00:00");
                    assertEquals(snapshot.fundingRate(), settled.get(0).rate(), "结算用的就是那一步行情栏上的费率");
                    assertEquals(snapshot.markPrice(), settled.get(0).markPrice());
                }
                all.addAll(settled);
            }
        }
        return all;
    }

    @Test
    void settlesExactlyOncePerEightMarketHours() {
        Market market = new Market(SEED, SPECS);
        TradingBook book = bookWithLongPosition(market, new AccountBook());
        List<Long> steps = new ArrayList<>();

        List<FundingRecord> records = run(market, book, 3 * 14400, steps);

        assertEquals(9, records.size(), "3 个市场日 = 9 个结算周期");
        assertEquals(List.of(4800L, 9600L, 14400L, 19200L, 24000L, 28800L, 33600L, 38400L, 43200L), steps);
        for (int i = 0; i < records.size(); i++) {
            assertEquals((i + 1) * STEPS_PER_PERIOD * 6, records.get(i).marketSeconds());
        }
        assertEquals(book.fundingRecords(ALICE), records);
    }

    @Test
    void restartKeepsTheScheduleAlignedAndDoesNotCatchUp() {
        // 不中断的参照：跑到第二个结算点之后
        Market straight = new Market(SEED, SPECS);
        TradingBook straightBook = bookWithLongPosition(straight, new AccountBook());
        List<Long> straightSteps = new ArrayList<>();
        List<FundingRecord> expected = run(straight, straightBook, 2 * STEPS_PER_PERIOD + 100, straightSteps);
        assertEquals(List.of(4800L, 9600L), straightSteps);

        // 中断的：在第一个结算点之后、第二个之前存档并“重启”（市场与交易账都从存档恢复），
        // 停服期间市场时间不走，重启后继续倒数
        Market market = new Market(SEED, SPECS);
        AccountBook accounts = new AccountBook();
        TradingBook book = bookWithLongPosition(market, accounts);
        List<Long> steps = new ArrayList<>();
        List<FundingRecord> records = new ArrayList<>(run(market, book, 7000, steps));
        assertEquals(1, records.size());
        Market restoredMarket = Market.restore(market.state(), SPECS);
        TradingBook restoredBook = TradingBook.restore(accounts, book.state());
        assertEquals(7000, restoredMarket.snapshot(DIAMOND.symbol()).step());
        assertTrue(restoredBook.settleFunding(restoredMarket.snapshot(DIAMOND.symbol())).isEmpty(), "恢复本身不触发结算");
        records.addAll(run(restoredMarket, restoredBook, 2 * STEPS_PER_PERIOD + 100, steps));

        assertEquals(List.of(4800L, 9600L), steps, "重启前后结算点一致，没有补结算也没有漏结算");
        assertEquals(expected, records, "与不中断的运行完全一致");
        assertFalse(restoredBook.fundingRecords(ALICE).isEmpty());
    }
}
