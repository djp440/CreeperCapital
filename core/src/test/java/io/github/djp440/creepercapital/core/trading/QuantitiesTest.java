package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuantitiesTest {
    @Test
    @DisplayName("按合约的数量精度把输入文本换算成整数手：钻石 3 位小数，火药 0 位")
    void parsesDecimalsIntoLots() {
        assertEquals(OptionalLong.of(2000), Quantities.parseLots("2", 3));
        assertEquals(OptionalLong.of(1500), Quantities.parseLots("1.5", 3));
        assertEquals(OptionalLong.of(1), Quantities.parseLots("0.001", 3));
        assertEquals(OptionalLong.of(1230), Quantities.parseLots("1.230", 3));
        assertEquals(OptionalLong.of(40), Quantities.parseLots("40", 0));
        assertEquals(OptionalLong.of(40), Quantities.parseLots("40.0", 0));
        assertEquals(OptionalLong.of(5), Quantities.parseLots(" 5 ", 0));
    }

    @Test
    @DisplayName("非正数、超过精度的小数、科学计数法、非数字、空文本都不是合法数量")
    void rejectsInvalidText() {
        for (String bad : new String[] {"", " ", "0", "0.000", "-1", "+", "abc", "1e3", "1,5", "1.2.3", "0.0001", "1.5"}) {
            assertEquals(OptionalLong.empty(), Quantities.parseLots(bad, bad.equals("1.5") ? 0 : 3), "不应接受：" + bad);
        }
        assertEquals(OptionalLong.empty(), Quantities.parseLots(null, 3));
    }

    @Test
    @DisplayName("大到装不进 long 的数量不合法")
    void rejectsOverflow() {
        assertEquals(OptionalLong.empty(), Quantities.parseLots("99999999999999999999", 3));
        assertEquals(OptionalLong.empty(), Quantities.parseLots("9223372036854775.808", 3));
        assertEquals(OptionalLong.of(Long.MAX_VALUE), Quantities.parseLots("9223372036854775.807", 3));
    }

    @Test
    @DisplayName("手数换回文本：补足精度位数，例如 2000 手（3 位）是 2.000，40 手（0 位）是 40")
    void formatsLots() {
        assertEquals("2.000", Quantities.format(2000, 3));
        assertEquals("0.001", Quantities.format(1, 3));
        assertEquals("40", Quantities.format(40, 0));
        assertEquals("1234.500", Quantities.format(1234500, 3));
    }
}
