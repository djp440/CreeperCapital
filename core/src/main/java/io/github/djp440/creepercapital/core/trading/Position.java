package io.github.djp440.creepercapital.core.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 一个合约账户在某个合约上的逐仓仓位（单向持仓：每个合约最多一个净仓位）。
 *
 * @param symbol     交易代码
 * @param side       方向
 * @param lots       数量，以合约的最小数量单位（手）计
 * @param entryPrice 开仓均价
 * @param leverage   杠杆倍数
 * @param margin     分配给这个仓位的逐仓保证金，亏损最多亏到这么多
 * @param quantityDecimals 合约的数量精度：1 手是 10^-位数 个标的资源
 */
public record Position(String symbol, Side side, long lots, double entryPrice, int leverage, Money margin,
        int quantityDecimals) {
    public Position {
        if (lots <= 0) {
            throw new IllegalArgumentException("仓位数量必须为正：" + lots);
        }
    }

    /** 按某个价格估算的盈亏（做多 (价格 − 开仓均价) × 数量，做空反之），取整到最小金额单位。 */
    public Money pnlAt(double price) {
        return pnl(side, entryPrice, price, lots, quantityDecimals);
    }

    static Money pnl(Side side, double entryPrice, double exitPrice, long lots, int quantityDecimals) {
        BigDecimal move = BigDecimal.valueOf(exitPrice).subtract(BigDecimal.valueOf(entryPrice));
        BigDecimal value = move.multiply(BigDecimal.valueOf(lots)).movePointLeft(quantityDecimals)
                .multiply(BigDecimal.valueOf(side.sign()));
        return Money.ofUnits(value.movePointRight(Money.SCALE).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    /** 收益率（回报率）：盈亏占保证金的比例；保证金为 0 时为 0。 */
    public double returnOnEquity(double price) {
        if (margin.units() == 0) {
            return 0;
        }
        return (double) pnlAt(price).units() / margin.units();
    }
}
