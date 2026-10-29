package io.github.djp440.creepercapital.core.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    @DisplayName("定点数：0.1 + 0.2 恰好等于 0.3，没有浮点误差")
    void noFloatingPointError() {
        assertEquals(Money.parse("0.3"), Money.parse("0.1").plus(Money.parse("0.2")));
        assertEquals("0.300000", Money.parse("0.1").plus(Money.parse("0.2")).toPlainString());
    }

    @Test
    @DisplayName("最小单位是 6 位小数，第 7 位小数被拒绝而不是四舍五入")
    void smallestUnitIsSixDecimals() {
        assertEquals(1, Money.parse("0.000001").units());
        assertEquals(1_000_000, Money.parse("1").units());
        assertThrows(IllegalArgumentException.class, () -> Money.parse("0.0000001"));
    }

    @Test
    @DisplayName("解析拒绝空串、非数字、科学计数法与溢出")
    void parseRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Money.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("1e3"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("9223372036855"));
    }

    @Test
    @DisplayName("展示格式：千分位，整数补 .00，保留有效的 6 位小数")
    void displayFormat() {
        assertEquals("1,234.50", Money.parse("1234.5").toDisplayString());
        assertEquals("0.00", Money.ZERO.toDisplayString());
        assertEquals("0.000001", Money.parse("0.000001").toDisplayString());
        assertEquals("-3.25", Money.parse("-3.25").toDisplayString());
    }

    @Test
    @DisplayName("加法溢出抛异常而不是回绕")
    void additionOverflowThrows() {
        Money max = Money.ofUnits(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> max.plus(Money.ofUnits(1)));
    }
}
