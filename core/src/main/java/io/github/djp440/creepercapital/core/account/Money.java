package io.github.djp440.creepercapital.core.account;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 苦力怕币（CRP）金额的定点数：以 10^-6 CRP 为最小单位的整数，所有加减精确，没有浮点误差。
 * 行情价格仍用 double（它们是模型输出，不是账本）；账本里的金额一律用本类型。
 */
public record Money(long units) implements Comparable<Money> {
    /** 小数位数。 */
    public static final int SCALE = 6;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

    public static final Money ZERO = new Money(0);

    public static Money ofUnits(long units) {
        return new Money(units);
    }

    /**
     * 解析十进制文本（如 {@code 100}、{@code 0.5}、{@code -3.25}），不接受科学计数法。
     *
     * @throws IllegalArgumentException 格式不对、小数超过 6 位或超出可表示范围
     */
    public static Money parse(String text) {
        if (text == null || !PLAIN_DECIMAL.matcher(text).matches()) {
            throw new IllegalArgumentException("不是合法的金额：" + text);
        }
        BigDecimal value = new BigDecimal(text);
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("金额最多 " + SCALE + " 位小数：" + text);
        }
        BigInteger units = value.movePointRight(SCALE).toBigIntegerExact();
        if (units.bitLength() > 63) {
            throw new IllegalArgumentException("金额超出范围：" + text);
        }
        return new Money(units.longValue());
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(units, other.units));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(units, other.units));
    }

    public boolean isPositive() {
        return units > 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(units, other.units);
    }

    /** 精确的 6 位小数文本，如 {@code 0.300000}。 */
    public String toPlainString() {
        return decimal().toPlainString();
    }

    /** 界面展示：千分位，至少 2 位小数，去掉多余的尾零。 */
    public String toDisplayString() {
        return new DecimalFormat("#,##0.00####", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(decimal());
    }

    private BigDecimal decimal() {
        return BigDecimal.valueOf(units, SCALE);
    }

    @Override
    public String toString() {
        return toPlainString();
    }
}
