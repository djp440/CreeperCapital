package io.github.djp440.creepercapital.core.trading;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 风险保障基金：强平后剩下的保证金和清算费流进来，穿仓时先由它垫付亏损，不够再由流动性池承担（见 {@link TradingBook}）。
 * 和流动性池一样是系统账户，初始为 0，随交易账存档。
 */
public final class InsuranceFund {
    private Money balance;

    public InsuranceFund() {
        this(Money.ZERO);
    }

    /** 从存档的余额恢复。 */
    public InsuranceFund(Money balance) {
        this.balance = balance;
    }

    public Money balance() {
        return balance;
    }

    /** 收款（强平剩余的保证金、清算费）。数量为 0 时什么也不做。 */
    void receive(Money amount) {
        if (amount.units() < 0) {
            throw new IllegalArgumentException("收款不能为负：" + amount);
        }
        balance = balance.plus(amount);
    }

    /**
     * 垫付穿仓亏损，余额不足时最多付出全部余额。
     *
     * @return 实际付出的金额
     */
    Money payUpTo(Money amount) {
        if (amount.units() < 0) {
            throw new IllegalArgumentException("付款不能为负：" + amount);
        }
        Money paid = amount.compareTo(balance) <= 0 ? amount : balance;
        balance = balance.minus(paid);
        return paid;
    }
}
