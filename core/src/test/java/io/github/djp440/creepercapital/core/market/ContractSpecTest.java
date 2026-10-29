package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContractSpecTest {
    private static final VolatilityProfile PROFILE = new VolatilityProfile(0.03, 0.2, 0.03, 0.08, 0.0015);

    private static ContractSpec spec(String symbol, double initialPrice, double baseVolume, VolatilityProfile profile) {
        return new ContractSpec(symbol, "minecraft:diamond", initialPrice, baseVolume, profile);
    }

    @Test
    @DisplayName("内置两个合约：DIAMONDCRP（钻石）与 GUNPOWDERCRP（火药），火药的波动率倾向比钻石猛得多")
    void defaultsAreDiamondAndGunpowder() {
        List<ContractSpec> defaults = ContractSpec.defaults();

        assertEquals(List.of("DIAMONDCRP", "GUNPOWDERCRP"), defaults.stream().map(ContractSpec::symbol).toList());
        assertEquals(List.of("minecraft:diamond", "minecraft:gunpowder"),
                defaults.stream().map(ContractSpec::underlying).toList());
        assertEquals(0.03, defaults.get(0).volatility().dailyVolatility());
        assertEquals(0.10, defaults.get(1).volatility().dailyVolatility());
        assertTrue(defaults.get(1).volatility().jumpsPerDay() >= 5 * defaults.get(0).volatility().jumpsPerDay(),
                "火药的跳跃频率应远高于钻石");
    }

    @Test
    @DisplayName("交易代码必须是「大写字母/数字/下划线 + CRP」：它会用作 K 线文件名，不能带路径或小写")
    void symbolMustLookLikeATradingCode() {
        assertEquals("BLAZERODCRP", spec("BLAZERODCRP", 10, 1, PROFILE).symbol());
        assertEquals("BLAZE_RODCRP", spec("BLAZE_RODCRP", 10, 1, PROFILE).symbol());
        for (String bad : List.of("", "diamondcrp", "DIAMOND", "CRP", "../EVILCRP", "A/BCRP", "DIA MONDCRP", "DIAMONDCRP.")) {
            assertThrows(IllegalArgumentException.class, () -> spec(bad, 10, 1, PROFILE), "不应接受交易代码：" + bad);
        }
    }

    @Test
    @DisplayName("起始价格与基础成交量必须是有限的正数")
    void pricesAndVolumesMustBePositiveAndFinite() {
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> spec("DIAMONDCRP", bad, 1, PROFILE));
            assertThrows(IllegalArgumentException.class, () -> spec("DIAMONDCRP", 10, bad, PROFILE));
        }
    }

    @Test
    @DisplayName("标的资源不能为空")
    void underlyingMustNotBeBlank() {
        assertThrows(IllegalArgumentException.class, () -> new ContractSpec("DIAMONDCRP", " ", 10, 1, PROFILE));
    }

    @Test
    @DisplayName("波动率倾向：日波动在 (0, 100%]，跳跃幅度满足 0 < 最小 ≤ 最大 < 100%，频率非负，溢价偏移非负")
    void volatilityProfileRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new VolatilityProfile(0, 0.2, 0.03, 0.08, 0.001));
        assertThrows(IllegalArgumentException.class, () -> new VolatilityProfile(1.5, 0.2, 0.03, 0.08, 0.001));
        assertThrows(IllegalArgumentException.class, () -> new VolatilityProfile(0.03, -1, 0.03, 0.08, 0.001));
        assertThrows(IllegalArgumentException.class, () -> new VolatilityProfile(0.03, 0.2, 0, 0.08, 0.001));
        assertThrows(IllegalArgumentException.class, () -> new VolatilityProfile(0.03, 0.2, 0.09, 0.08, 0.001));
        assertThrows(IllegalArgumentException.class, () -> new VolatilityProfile(0.03, 0.2, 0.03, 1.0, 0.001));
        assertThrows(IllegalArgumentException.class, () -> new VolatilityProfile(0.03, 0.2, 0.03, 0.08, -0.001));
        assertThrows(IllegalArgumentException.class, () -> new VolatilityProfile(Double.NaN, 0.2, 0.03, 0.08, 0.001));
    }

    @Test
    @DisplayName("没有跳跃（频率为 0）是合法的")
    void jumpsCanBeDisabled() {
        assertEquals(0.0, new VolatilityProfile(0.03, 0, 0.03, 0.08, 0.001).jumpsPerDay());
    }

    @Test
    @DisplayName("日波动设得比跳跃自己造成的波动还小，模型无法兑现，直接拒绝并说明原因")
    void dailyVolatilityBelowTheJumpContributionIsRejected() {
        // 每天 5 次 5%–20% 的跳跃，光跳跃就远超 1% 的日波动
        VolatilityProfile impossible = new VolatilityProfile(0.01, 5, 0.05, 0.20, 0.001);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> spec("DIAMONDCRP", 10, 1, impossible));
        assertTrue(error.getMessage().contains("DIAMONDCRP"), "报错要指明是哪个合约：" + error.getMessage());
    }
}
