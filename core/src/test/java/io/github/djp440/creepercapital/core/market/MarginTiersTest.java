package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.MarginTiers.Definition;

/**
 * 维持保证金分档。默认分档的期望值全部手算：
 * 125x 合约五档 (0,5000] 125x 0.4%、(5000,25000] 100x 0.5%、(25000,100000] 50x 1%、(100000,250000] 20x 2.5%、(250000,500000] 10x 5%，
 * 速算额 0、5、130、1630、7880（= 上一档速算额 + 本档下界 × 费率差）。
 */
class MarginTiersTest {
    private static final MarginTiers DIAMOND = MarginTiers.standard(125);
    private static final MarginTiers GUNPOWDER = MarginTiers.standard(50);

    private static Money crp(String amount) {
        return Money.parse(amount);
    }

    @Test
    @DisplayName("125x 合约的默认分档：上界、杠杆、费率和速算额")
    void defaultTiersForTheDiamondContract() {
        List<MarginTiers.Tier> tiers = DIAMOND.tiers();

        assertEquals(5, tiers.size());
        assertEquals(List.of(crp("5000"), crp("25000"), crp("100000"), crp("250000"), crp("500000")),
                tiers.stream().map(MarginTiers.Tier::upTo).toList());
        assertEquals(List.of(125, 100, 50, 20, 10), tiers.stream().map(MarginTiers.Tier::maxLeverage).toList());
        assertEquals(List.of(0.004, 0.005, 0.01, 0.025, 0.05), tiers.stream().map(MarginTiers.Tier::maintenanceRate).toList());
        assertEquals(List.of("0", "5", "130", "1630", "7880"),
                tiers.stream().map(tier -> tier.amount().stripTrailingZeros().toPlainString()).toList());
        assertEquals(crp("500000"), DIAMOND.maxNotional());
        assertEquals(125, DIAMOND.maxLeverage());
    }

    @Test
    @DisplayName("50x 合约的默认分档：50/40/20/8/4 倍，费率 1%、1.25%、2.5%、6.25%、12.5%")
    void defaultTiersForTheGunpowderContract() {
        assertEquals(List.of(50, 40, 20, 8, 4), GUNPOWDER.tiers().stream().map(MarginTiers.Tier::maxLeverage).toList());
        assertEquals(List.of(0.01, 0.0125, 0.025, 0.0625, 0.125),
                GUNPOWDER.tiers().stream().map(MarginTiers.Tier::maintenanceRate).toList());
        // 12.5、12.5 + 25000 × 0.0125 = 325、325 + 100000 × 0.0375 = 4075、4075 + 250000 × 0.0625 = 19700
        assertEquals(List.of("0", "12.5", "325", "4075", "19700"),
                GUNPOWDER.tiers().stream().map(tier -> tier.amount().stripTrailingZeros().toPlainString()).toList());
    }

    @Test
    @DisplayName("维持保证金 = 名义价值 × 所在档费率 − 速算额，档位区间是 (下界, 上界]")
    void maintenanceMarginUsesTheTierOfTheNotional() {
        assertEquals(crp("16"), DIAMOND.maintenanceMargin(crp("4000")));         // 4000 × 0.4%
        assertEquals(crp("20"), DIAMOND.maintenanceMargin(crp("5000")));         // 5000 在第一档：5000 × 0.4%
        assertEquals(crp("20.00005"), DIAMOND.maintenanceMargin(crp("5000.01"))); // 第二档：5000.01 × 0.5% − 5
        assertEquals(crp("170"), DIAMOND.maintenanceMargin(crp("30000")));       // 第三档：30000 × 1% − 130
        assertEquals(crp("7120"), DIAMOND.maintenanceMargin(crp("300000")));     // 第五档：300000 × 5% − 7880
        // 档位边界两侧连续（第四档 250000 × 2.5% − 1630 = 4620，第五档 250000 × 5% − 7880 = 4620）
        assertEquals(crp("4620"), DIAMOND.maintenanceMargin(crp("250000")));
        assertEquals(crp("0"), DIAMOND.maintenanceMargin(Money.ZERO));
        assertEquals(crp("675"), GUNPOWDER.maintenanceMargin(crp("40000")));     // 火药第三档：40000 × 2.5% − 325
    }

    @Test
    @DisplayName("名义价值所在档位决定可用的最高杠杆；超过合约最高杠杆没有可用的名义价值")
    void leverageLimitsFollowTheTier() {
        assertEquals(125, DIAMOND.maxLeverageAt(crp("5000")));
        assertEquals(100, DIAMOND.maxLeverageAt(crp("5000.01")));
        assertEquals(10, DIAMOND.maxLeverageAt(crp("500000")));
        assertEquals(crp("5000"), DIAMOND.maxNotionalAt(125));
        assertEquals(crp("25000"), DIAMOND.maxNotionalAt(100));
        assertEquals(crp("25000"), DIAMOND.maxNotionalAt(75));
        assertEquals(crp("100000"), DIAMOND.maxNotionalAt(50));
        assertEquals(crp("500000"), DIAMOND.maxNotionalAt(1));
        assertEquals(Money.ZERO, DIAMOND.maxNotionalAt(126));
    }

    @Test
    @DisplayName("不合法的分档在建立时就被拒绝")
    void invalidTiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MarginTiers.of(List.of()));
        // 上界不递增
        assertThrows(IllegalArgumentException.class, () -> MarginTiers.of(
                List.of(new Definition(crp("100"), 10, 0.01), new Definition(crp("100"), 5, 0.02))));
        // 杠杆上升
        assertThrows(IllegalArgumentException.class, () -> MarginTiers.of(
                List.of(new Definition(crp("100"), 5, 0.01), new Definition(crp("200"), 10, 0.02))));
        // 费率下降
        assertThrows(IllegalArgumentException.class, () -> MarginTiers.of(
                List.of(new Definition(crp("100"), 10, 0.02), new Definition(crp("200"), 5, 0.01))));
        // 维持保证金率不低于初始保证金率：10 倍杠杆的初始保证金率是 10%
        assertThrows(IllegalArgumentException.class, () -> MarginTiers.of(List.of(new Definition(crp("100"), 10, 0.1))));
    }

    @Test
    @DisplayName("合约定义里的分档：缺省按最高杠杆推出，写了就用写的，但第一档杠杆必须等于最高杠杆")
    void contractSpecCarriesTiers() {
        ContractSpec diamond = ContractSpec.defaults().get(0);
        assertEquals(DIAMOND, diamond.marginTiers());
        assertEquals(GUNPOWDER, ContractSpec.defaults().get(1).marginTiers());

        MarginTiers custom = MarginTiers.of(List.of(new Definition(crp("1000"), 20, 0.01), new Definition(crp("9000"), 5, 0.05)));
        ContractSpec spec = new ContractSpec("TESTCRP", "minecraft:stone", 10, 1, diamond.volatility(), 0.01, 20, 3, ContractSpec.DEFAULT_DEPTH_SLIPPAGE, custom);
        assertEquals(custom, spec.marginTiers());
        assertThrows(IllegalArgumentException.class,
                () -> new ContractSpec("TESTCRP", "minecraft:stone", 10, 1, diamond.volatility(), 0.01, 25, 3, ContractSpec.DEFAULT_DEPTH_SLIPPAGE, custom));
    }
}
