package io.github.djp440.creepercapital.core.trading;

import java.math.BigDecimal;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * 下单数量在「标的资源个数」（文本）与「手」（整数，1 手 = 10^-精度 个）之间的换算。
 */
public final class Quantities {
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");

    private Quantities() {
    }

    /**
     * 把输入文本换算成手数。
     *
     * @param text     十进制文本，例如 {@code 1.5}；不接受科学计数法、正负号和千分位
     * @param decimals 合约的数量精度
     * @return 手数；不是数字、不为正、小数位超过精度或装不进 long 时为空
     */
    public static OptionalLong parseLots(String text, int decimals) {
        if (text == null) {
            return OptionalLong.empty();
        }
        String trimmed = text.trim();
        if (!PLAIN_DECIMAL.matcher(trimmed).matches()) {
            return OptionalLong.empty();
        }
        BigDecimal value = new BigDecimal(trimmed).stripTrailingZeros();
        if (value.signum() <= 0 || value.scale() > decimals) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(value.movePointRight(decimals).longValueExact());
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    /** 手数换回文本，补足精度位数，例如 2000 手（3 位）是 {@code 2.000}。 */
    public static String format(long lots, int decimals) {
        return BigDecimal.valueOf(lots).movePointLeft(decimals).setScale(decimals).toPlainString();
    }
}
