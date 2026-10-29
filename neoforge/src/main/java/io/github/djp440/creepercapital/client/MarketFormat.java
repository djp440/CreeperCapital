package io.github.djp440.creepercapital.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.MarketTime;
import net.minecraft.network.chat.Component;

/**
 * 界面上价格、成交量、涨跌幅和市场时间的统一写法。只在渲染线程调用，共用格式化对象。
 */
final class MarketFormat {
    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.ROOT);
    private static final DecimalFormat PRICE = new DecimalFormat("#,##0.00", SYMBOLS);
    private static final DecimalFormat SIGNED_PRICE = new DecimalFormat("+#,##0.00;-#,##0.00", SYMBOLS);
    private static final DecimalFormat SIGNED_PERCENT = new DecimalFormat("+0.00%;-0.00%", SYMBOLS);
    /** 资金费率按币安的习惯显示为百分数、保留 4 位小数，例如 0.0100%。 */
    private static final DecimalFormat FUNDING_RATE = new DecimalFormat("0.0000%", SYMBOLS);
    /** 金额（CRP）：表格和下单面板统一保留 2 位小数；手续费常常不到 0.01，最多保留 4 位。 */
    private static final DecimalFormat MONEY = rounded("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = rounded("+#,##0.00;-#,##0.00");
    private static final DecimalFormat FEE = rounded("#,##0.00##");
    /** 账户余额（资产页、下单面板的可用余额与账户概况）：按账本精确显示，至少 2 位、最多 6 位小数。 */
    private static final DecimalFormat BALANCE = rounded("#,##0.00####");
    private static final DecimalFormat SIGNED_BALANCE = rounded("+#,##0.00####;-#,##0.00####");

    private MarketFormat() {
    }

    static String price(double value) {
        return PRICE.format(value);
    }

    /** 带正负号的价格差，例如 {@code +3.20}。 */
    static String signedPrice(double value) {
        return SIGNED_PRICE.format(value);
    }

    /** 带正负号的涨跌幅，例如 {@code +0.32%}。 */
    static String signedPercent(double ratio) {
        return SIGNED_PERCENT.format(ratio);
    }

    /** 金额，例如 {@code 1,200.58}。 */
    static String money(Money money) {
        return MONEY.format(decimal(money));
    }

    /** 带正负号的金额，零不带符号，例如 {@code +73.64}、{@code -5.20}、{@code 0.00}。 */
    static String signedMoney(Money money) {
        return money.units() == 0 ? MONEY.format(BigDecimal.ZERO) : SIGNED_MONEY.format(decimal(money));
    }

    /** 账户余额，按账本精确显示，例如 {@code 12,345.678901}、{@code 0.00}。 */
    static String balance(Money money) {
        return BALANCE.format(decimal(money));
    }

    /** 带正负号的账户余额变动（未实现盈亏），零不带符号，例如 {@code +73.6412}。 */
    static String signedBalance(Money money) {
        return money.units() == 0 ? BALANCE.format(BigDecimal.ZERO) : SIGNED_BALANCE.format(decimal(money));
    }

    /** 手续费，例如 {@code 0.6003}、{@code 12.50}。 */
    static String fee(Money money) {
        return FEE.format(decimal(money));
    }

    private static BigDecimal decimal(Money money) {
        return BigDecimal.valueOf(money.units(), Money.SCALE);
    }

    private static DecimalFormat rounded(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, SYMBOLS);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }

    static String fundingRate(double rate) {
        return FUNDING_RATE.format(rate);
    }

    /** 成交量：千以上用 K、百万以上用 M，例如 {@code 86.40K}。 */
    static String volume(double value) {
        double magnitude = Math.abs(value);
        if (magnitude >= 1_000_000) {
            return PRICE.format(value / 1_000_000) + "M";
        }
        if (magnitude >= 1_000) {
            return PRICE.format(value / 1_000) + "K";
        }
        return PRICE.format(value);
    }

    /** 市场日历的日期和时分秒，例如「第 3 日 08:12:06」。 */
    static String marketTime(MarketTime time) {
        return Component.translatable("screen.creepercapital.trading.market_time.value",
                time.day(), String.format(Locale.ROOT, "%02d:%02d:%02d", time.hour(), time.minute(), time.second()))
                .getString();
    }

    /** 距结算的倒计时，例如「07:59:54」。 */
    static String countdown(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }
}
