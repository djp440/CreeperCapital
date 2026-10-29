package io.github.djp440.creepercapital.core.account;

/**
 * 资产页展示的四项数字。
 *
 * @param walletBalance    钱包余额
 * @param unrealizedPnl    未实现盈亏
 * @param marginBalance    保证金余额 = 钱包余额 + 未实现盈亏
 * @param availableBalance 可用余额 = 保证金余额 − 已占用保证金
 */
public record Assets(Money walletBalance, Money unrealizedPnl, Money marginBalance, Money availableBalance) {
    /** 没有仓位时的资产：未实现盈亏为 0，其余几项等于钱包余额。 */
    public static Assets ofWallet(Money wallet) {
        return new Assets(wallet, Money.ZERO, wallet, wallet);
    }
}
