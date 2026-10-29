package io.github.djp440.creepercapital.client;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

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
