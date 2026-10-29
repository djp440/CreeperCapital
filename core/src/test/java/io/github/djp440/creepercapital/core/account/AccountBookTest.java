package io.github.djp440.creepercapital.core.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountBookTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    @DisplayName("新玩家余额为 0")
    void newAccountIsEmpty() {
        assertEquals(Money.ZERO, new AccountBook().balance(ALICE));
    }

    @Test
    @DisplayName("充值后才有账户，扣光余额后账户仍在")
    void accountExistsAfterFirstDeposit() {
        AccountBook book = new AccountBook();
        assertFalse(book.hasAccount(ALICE));
        book.deposit(ALICE, Money.parse("1"));
        assertTrue(book.hasAccount(ALICE));
        book.withdraw(ALICE, Money.parse("1"));
        assertTrue(book.hasAccount(ALICE));
        assertFalse(book.hasAccount(BOB));
    }

    @Test
    @DisplayName("充值累加，各玩家互不影响")
    void depositAccumulates() {
        AccountBook book = new AccountBook();
        book.deposit(ALICE, Money.parse("100.5"));
        book.deposit(ALICE, Money.parse("0.000001"));
        assertEquals(Money.parse("100.500001"), book.balance(ALICE));
        assertEquals(Money.ZERO, book.balance(BOB));
    }

    @Test
    @DisplayName("扣除成功后减少余额；恰好扣光余额也允许")
    void withdrawReducesBalance() {
        AccountBook book = new AccountBook();
        book.deposit(ALICE, Money.parse("10"));
        assertTrue(book.withdraw(ALICE, Money.parse("4.25")));
        assertEquals(Money.parse("5.75"), book.balance(ALICE));
        assertTrue(book.withdraw(ALICE, Money.parse("5.75")));
        assertEquals(Money.ZERO, book.balance(ALICE));
    }

    @Test
    @DisplayName("余额不足时扣除被拒绝且余额不变，哪怕只差最小单位")
    void withdrawBeyondBalanceIsRejected() {
        AccountBook book = new AccountBook();
        book.deposit(ALICE, Money.parse("10"));
        assertFalse(book.withdraw(ALICE, Money.parse("10.000001")));
        assertEquals(Money.parse("10"), book.balance(ALICE));
        assertFalse(book.withdraw(BOB, Money.parse("0.000001")));
    }

    @Test
    @DisplayName("充值与扣除的数量必须为正")
    void amountsMustBePositive() {
        AccountBook book = new AccountBook();
        assertThrows(IllegalArgumentException.class, () -> book.deposit(ALICE, Money.ZERO));
        assertThrows(IllegalArgumentException.class, () -> book.deposit(ALICE, Money.parse("-1")));
        assertThrows(IllegalArgumentException.class, () -> book.withdraw(ALICE, Money.ZERO));
    }

    @Test
    @DisplayName("充值溢出被拒绝且余额不变")
    void depositOverflowKeepsBalance() {
        AccountBook book = new AccountBook();
        book.deposit(ALICE, Money.ofUnits(Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> book.deposit(ALICE, Money.ofUnits(1)));
        assertEquals(Money.ofUnits(Long.MAX_VALUE), book.balance(ALICE));
    }

    @Test
    @DisplayName("导出状态再恢复，余额不变")
    void stateRoundTrips() {
        AccountBook book = new AccountBook();
        book.deposit(ALICE, Money.parse("1.5"));
        book.deposit(BOB, Money.parse("2"));
        AccountBook restored = AccountBook.restore(book.balances());
        assertEquals(Map.of(ALICE, Money.parse("1.5"), BOB, Money.parse("2")), restored.balances());
    }

    @Test
    @DisplayName("没有仓位时：未实现盈亏为 0，保证金余额与可用余额等于钱包余额")
    void assetsWithoutPositions() {
        Assets assets = Assets.ofWallet(Money.parse("123.45"));
        assertEquals(Money.parse("123.45"), assets.walletBalance());
        assertEquals(Money.ZERO, assets.unrealizedPnl());
        assertEquals(Money.parse("123.45"), assets.marginBalance());
        assertEquals(Money.parse("123.45"), assets.availableBalance());
    }
}
