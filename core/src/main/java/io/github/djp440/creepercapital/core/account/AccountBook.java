package io.github.djp440.creepercapital.core.account;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全服的合约账户账本：每位玩家一个 CRP 余额，没有记录视为 0。余额永不为负。
 */
public final class AccountBook {
    private final Map<UUID, Money> balances = new HashMap<>();

    /** 从存档的余额恢复。 */
    public static AccountBook restore(Map<UUID, Money> balances) {
        AccountBook book = new AccountBook();
        book.balances.putAll(balances);
        return book;
    }

    /** 这名玩家是否已有账户（充值过；扣光余额后账户仍在）。 */
    public boolean hasAccount(UUID player) {
        return balances.containsKey(player);
    }

    public Money balance(UUID player) {
        return balances.getOrDefault(player, Money.ZERO);
    }

    /** 此刻所有余额的副本，供存档。 */
    public Map<UUID, Money> balances() {
        return Map.copyOf(balances);
    }

    /**
     * 充值。
     *
     * @return 充值后的余额
     * @throws IllegalArgumentException 数量不为正
     * @throws ArithmeticException      余额溢出，此时余额不变
     */
    public Money deposit(UUID player, Money amount) {
        requirePositive(amount);
        Money updated = balance(player).plus(amount);
        balances.put(player, updated);
        return updated;
    }

    /**
     * 扣除。
     *
     * @return 余额足够并已扣除为 true；余额不足时拒绝，余额不变，返回 false
     * @throws IllegalArgumentException 数量不为正
     */
    public boolean withdraw(UUID player, Money amount) {
        requirePositive(amount);
        Money current = balance(player);
        if (current.compareTo(amount) < 0) {
            return false;
        }
        balances.put(player, current.minus(amount));
        return true;
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("数量必须为正：" + amount);
        }
    }
}
