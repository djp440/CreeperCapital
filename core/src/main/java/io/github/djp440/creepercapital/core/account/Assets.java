package io.github.djp440.creepercapital.core.account;

/**
 * 资产页展示的四项数字。
 *
 * @param walletBalance    钱包余额（含仓位占用的保证金）
 * @param unrealizedPnl    未实现盈亏
 * @param marginBalance    保证金余额 = 钱包余额 + 未实现盈亏
 * @param availableBalance 可用余额：还没被仓位占用的余额，可用来开新仓或被 dev 命令扣除（逐仓的未实现盈亏不计入）
 */
public record Assets(Money walletBalance, Money unrealizedPnl, Money marginBalance, Money availableBalance) {
    /** 没有仓位时的资产：未实现盈亏为 0，其余几项等于钱包余额。 */
    public static Assets ofWallet(Money wallet) {
        return new Assets(wallet, Money.ZERO, wallet, wallet);
    }
}
