package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/** 成交监听：成交记录的源头对外报告每一笔玩家成交，「最新成交」面板据此全服广播（不挂在某个下单入口上）。 */
class TradeListenerTest {
    private static final ContractSpec GUNPOWDER = ContractSpec.defaults().get(1);
    private static final UUID ALICE = new UUID(0, 1);

    private static MarketSnapshot snapshot(double last) {
        return new MarketSnapshot(100, GUNPOWDER.symbol(), last, last, last, 0.0001, 0);
    }

    @Test
    @DisplayName("每笔成交（开仓、平仓）都通知监听者，收到的是带成交编号的那一笔；被拒的委托不通知")
    void listenersSeeEveryRecordedTradeAndNoRejections() {
        AccountBook accounts = new AccountBook();
        TradingBook book = new TradingBook(accounts, new LiquidityPool());
        accounts.deposit(ALICE, Money.parse("1000"));
        List<PlayerTrade> seen = new ArrayList<>();
        book.addTradeListener(seen::add);

        PlayerTrade opened = ((TradeResult.Filled) book.open(ALICE, GUNPOWDER, snapshot(100), Side.LONG, 10, 5)).trade();
        book.open(ALICE, GUNPOWDER, snapshot(100), Side.SHORT, 10, 5);
        book.open(ALICE, GUNPOWDER, snapshot(100), Side.LONG, 0, 5);
        PlayerTrade closed = ((TradeResult.Filled) book.close(ALICE, GUNPOWDER, snapshot(101), 4)).trade();

        assertEquals(List.of(opened, closed), seen);
        assertEquals(List.of(1L, 2L), seen.stream().map(PlayerTrade::id).toList());
        assertEquals(TradeKind.CLOSE, seen.get(1).kind());
        assertTrue(seen.get(1).realizedPnl().isPositive());
    }

    @Test
    @DisplayName("没有监听者时照常成交；多个监听者都会收到")
    void worksWithoutListenersAndNotifiesAllOfThem() {
        AccountBook accounts = new AccountBook();
        TradingBook book = new TradingBook(accounts, new LiquidityPool());
        accounts.deposit(ALICE, Money.parse("1000"));
        book.open(ALICE, GUNPOWDER, snapshot(100), Side.LONG, 1, 5);

        List<Long> first = new ArrayList<>();
        List<Long> second = new ArrayList<>();
        book.addTradeListener(trade -> first.add(trade.id()));
        book.addTradeListener(trade -> second.add(trade.id()));
        book.close(ALICE, GUNPOWDER, snapshot(100), 1);

        assertEquals(List.of(2L), first);
        assertEquals(List.of(2L), second);
    }
}
