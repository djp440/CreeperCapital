package io.github.djp440.creepercapital.core.trading;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 流动性池：持有有限苦力怕币、作为所有玩家交易对手方的系统账户（ADR-0001）。玩家盈利由它支付，亏损和手续费流入它。
 */
public final class LiquidityPool {
    /** 新世界里池子的初始资金。 */
    public static final Money INITIAL_BALANCE = Money.parse("1000000");

    private Money balance;

    public LiquidityPool() {
        this(INITIAL_BALANCE);
    }

    /** 从存档的余额恢复。 */
    public LiquidityPool(Money balance) {
        this.balance = balance;
    }

    public Money balance() {
        return balance;
    }

    /** 注资（开发命令用，凭空增加池子的资金）。 */
    public Money fund(Money amount) {
        requirePositive(amount);
        balance = balance.plus(amount);
        return balance;
    }

    /** 收款（玩家的亏损、手续费）。数量为 0 时什么也不做。 */
    void receive(Money amount) {
        if (amount.units() < 0) {
            throw new IllegalArgumentException("收款不能为负：" + amount);
        }
        balance = balance.plus(amount);
    }

    /**
     * 付款（玩家的盈利），池子余额不足时最多付出全部余额。
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

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("数量必须为正：" + amount);
        }
    }
}
