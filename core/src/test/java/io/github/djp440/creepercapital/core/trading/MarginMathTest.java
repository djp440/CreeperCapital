package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarginTiers;

/**
 * 保证金率与预估强平价（#12 强平复用的单一事实来源）。钻石 125x 的分档：(0,5000] 0.4%、(5000,25000] 0.5%、(25000,100000] 1%，
 * 速算额 0、5、130；火药 50x 的第一档 (0,5000] 费率 1%（上界与钻石相同，见 MarginTiersTest）。期望值全部手算。
 */
class MarginMathTest {
    private static final MarginTiers DIAMOND_TIERS = ContractSpec.defaults().get(0).marginTiers();
    private static final MarginTiers GUNPOWDER_TIERS = ContractSpec.defaults().get(1).marginTiers();

    private static Money crp(String amount) {
        return Money.parse(amount);
    }

    private static MarginTiers tiersOf(String symbol) {
        return symbol.equals("DIAMONDCRP") ? DIAMOND_TIERS : GUNPOWDER_TIERS;
    }

    private static Position diamond(Side side, long lots, double entry, int leverage, String margin, MarginMode mode) {
        return new Position("DIAMONDCRP", side, lots, entry, leverage, crp(margin), 3, mode);
    }

    private static Position gunpowder(Side side, long lots, double entry, int leverage, String margin, MarginMode mode) {
        return new Position("GUNPOWDERCRP", side, lots, entry, leverage, crp(margin), 0, mode);
    }

    @Test
    @DisplayName("逐仓多头：保证金率 = 维持保证金 / (保证金 + 未实现盈亏)，强平价按币安公式")
    void isolatedLong() {
        // 2 个钻石 @1000，10 倍，保证金 200；标记价格 950：名义价值 1900，维持保证金 1900 × 0.4% = 7.6，浮亏 100，保证金余额 100
        Position position = diamond(Side.LONG, 2000, 1000, 10, "200", MarginMode.ISOLATED);

        MarginMath.PositionRisk risk = MarginMath
                .assess(List.of(position), crp("200"), symbol -> 950.0, MarginMathTest::tiersOf).get("DIAMONDCRP");

        assertEquals(crp("1900"), risk.notional());
        assertEquals(crp("7.6"), risk.maintenanceMargin());
        assertEquals(crp("100"), risk.marginBalance());
        assertEquals(0.076, risk.marginRatio(), 1e-12);
        // (200 − 2 × 1000) / (2 × 0.004 − 2) = 1800 / 1.992
        assertEquals(1800.0 / 1.992, risk.liquidationPrice().orElseThrow(), 1e-9);
        assertEquals(903.614457831, risk.liquidationPrice().orElseThrow(), 1e-6);
    }

    @Test
    @DisplayName("逐仓空头：强平价在开仓价之上")
    void isolatedShort() {
        // 2 个钻石 @1000 空，保证金 200；标记价格 1050：名义价值 2100，维持保证金 8.4，浮亏 100
        Position position = diamond(Side.SHORT, 2000, 1000, 10, "200", MarginMode.ISOLATED);

        MarginMath.PositionRisk risk = MarginMath
                .assess(List.of(position), crp("200"), symbol -> 1050.0, MarginMathTest::tiersOf).get("DIAMONDCRP");

        assertEquals(crp("8.4"), risk.maintenanceMargin());
        assertEquals(0.084, risk.marginRatio(), 1e-12);
        // (200 + 2 × 1000) / (2 × 0.004 + 2) = 2200 / 2.008
        assertEquals(1095.617529881, risk.liquidationPrice().orElseThrow(), 1e-6);
    }

    @Test
    @DisplayName("全仓：所有全仓仓位共用余额，保证金率是整个全仓账户的，强平价把其他全仓仓位的维持保证金与浮亏算进去")
    void crossSharesTheWallet() {
        // 全仓钱包 3000（可用 1800 + 两个仓位的保证金 200 + 1000）
        Position diamond = diamond(Side.LONG, 2000, 1000, 10, "200", MarginMode.CROSS);
        // 100 个火药 @100，10 倍，保证金 1000
        Position gunpowder = gunpowder(Side.LONG, 100, 100, 10, "1000", MarginMode.CROSS);
        Map<String, Double> marks = Map.of("DIAMONDCRP", 950.0, "GUNPOWDERCRP", 90.0);

        Map<String, MarginMath.PositionRisk> risks = MarginMath.assess(List.of(diamond, gunpowder), crp("3000"),
                symbol -> marks.get(symbol), MarginMathTest::tiersOf);

        // 钻石：名义价值 1900，维持保证金 7.6，浮亏 100；
        // 火药：名义价值 9000 在第二档（费率 1.25%，速算额 12.5），维持保证金 9000 × 1.25% − 12.5 = 100，浮亏 1000
        // 全仓保证金余额 = 3000 − 1100 = 1900，保证金率 = (7.6 + 100) / 1900
        MarginMath.PositionRisk d = risks.get("DIAMONDCRP");
        MarginMath.PositionRisk g = risks.get("GUNPOWDERCRP");
        assertEquals(crp("1900"), d.marginBalance());
        assertEquals(107.6 / 1900, d.marginRatio(), 1e-12);
        assertEquals(d.marginRatio(), g.marginRatio(), 0.0);
        assertEquals(crp("100"), g.maintenanceMargin());
        // 钻石：(3000 − 100 − 1000 − 2 × 1000) / (2 × 0.004 − 2) = (−100) / (−1.992)
        assertEquals(100.0 / 1.992, d.liquidationPrice().orElseThrow(), 1e-9);
        // 火药（强平价 71.85 对应的名义价值 7185 在第二档）：
        // (3000 − 7.6 − 100 + 12.5 − 100 × 100) / (100 × 0.0125 − 100) = (−7095.1) / (−98.75)
        assertEquals(7095.1 / 98.75, g.liquidationPrice().orElseThrow(), 1e-9);
    }

    @Test
    @DisplayName("全仓与逐仓混合：逐仓保证金不计入全仓钱包，逐仓仓位只看自己的保证金")
    void crossAndIsolatedAreSeparate() {
        Position isolated = diamond(Side.LONG, 2000, 1000, 10, "200", MarginMode.ISOLATED);
        Position cross = gunpowder(Side.LONG, 100, 100, 10, "1000", MarginMode.CROSS);
        Map<String, Double> marks = Map.of("DIAMONDCRP", 950.0, "GUNPOWDERCRP", 90.0);

        // 钱包 3000 里有 200 是逐仓保证金，全仓钱包是 2800，减去火药浮亏 1000 = 1800
        Map<String, MarginMath.PositionRisk> risks = MarginMath.assess(List.of(isolated, cross), crp("3000"),
                symbol -> marks.get(symbol), MarginMathTest::tiersOf);

        assertEquals(0.076, risks.get("DIAMONDCRP").marginRatio(), 1e-12);
        assertEquals(100.0 / 1800, risks.get("GUNPOWDERCRP").marginRatio(), 1e-12);
        // 火药强平价（第二档）：(2800 + 12.5 − 100 × 100) / (100 × 0.0125 − 100) = (−7187.5) / (−98.75)
        assertEquals(7187.5 / 98.75, risks.get("GUNPOWDERCRP").liquidationPrice().orElseThrow(), 1e-9);
    }

    @Test
    @DisplayName("强平价落在别的档位时，按那一档的费率和速算额重算")
    void liquidationPriceUsesTheTierOfThePrice() {
        // 100 个钻石 @1000 多，20 倍，保证金 5000；名义价值 100000 在第三档（费率 1%，速算额 130）
        OptionalDouble price = MarginMath.liquidationPrice(Side.LONG, 100, 1000, 5000, 0, 0, DIAMOND_TIERS);

        // 第三档：(5000 + 130 − 100 × 1000) / (100 × 0.01 − 100) = 94870 / 99，名义价值 95828 仍在第三档
        assertEquals(94870.0 / 99, price.orElseThrow(), 1e-9);

        // 钱包 75000：第三档 (75000 + 130 − 100000) / (−99) = 251.21，名义价值 25121 在第三档（> 25000）
        assertEquals(24870.0 / 99, MarginMath.liquidationPrice(Side.LONG, 100, 1000, 75000, 0, 0, DIAMOND_TIERS).orElseThrow(), 1e-9);
        // 钱包 76000：第三档给出 (76000 + 130 − 100000) / (−99) = 241.11，名义价值 24111 不在第三档，
        // 第二档（费率 0.5%，速算额 5）：(76000 + 5 − 100000) / (100 × 0.005 − 100) = 23995 / 99.5 = 241.1558，名义价值 24115.58 在第二档内
        assertEquals(23995.0 / 99.5, MarginMath.liquidationPrice(Side.LONG, 100, 1000, 76000, 0, 0, DIAMOND_TIERS).orElseThrow(), 1e-9);
    }

    @Test
    @DisplayName("保证金足以覆盖到价格归零的多头没有强平价")
    void noLiquidationPriceWhenFullyCovered() {
        // 2 个钻石 @1000 多，钱包 3000 ≥ 开仓价值 2000
        assertTrue(MarginMath.liquidationPrice(Side.LONG, 2, 1000, 3000, 0, 0, DIAMOND_TIERS).isEmpty());
    }

    @Test
    @DisplayName("保证金余额不为正时保证金率视为无穷大（已达强平线）")
    void marginRatioIsInfiniteWhenEquityIsGone() {
        assertEquals(Double.POSITIVE_INFINITY, MarginMath.marginRatio(crp("7.6"), Money.ZERO));
        assertEquals(Double.POSITIVE_INFINITY, MarginMath.marginRatio(crp("7.6"), crp("-1")));
        assertEquals(0.05, MarginMath.marginRatio(crp("5"), crp("100")), 1e-12);
    }

    @Test
    @DisplayName("标记价格未知（合约已下线）时按开仓均价算，浮盈亏为 0")
    void unknownMarkPriceFallsBackToTheEntryPrice() {
        Position position = diamond(Side.LONG, 2000, 1000, 10, "200", MarginMode.ISOLATED);

        MarginMath.PositionRisk risk = MarginMath
                .assess(List.of(position), crp("200"), symbol -> Double.NaN, MarginMathTest::tiersOf).get("DIAMONDCRP");

        assertEquals(crp("200"), risk.marginBalance());
        assertEquals(crp("2000"), risk.notional());
        assertEquals(crp("8"), risk.maintenanceMargin());
    }
}
