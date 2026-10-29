package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 虚拟流动性曲线：离散阶梯，每档数量相同（约 500 CRP 名义价值），价格逐档按固定比例变差。
 * 钻石：点差 0.5、滑点 0.05%、精度 3 位，最新价 1000 时每档 500 手（0.5 个）；
 * 火药：点差 0.05、滑点 0.3%、精度 0 位，最新价 100 时每档 5 手。期望值都是手算的。
 */
class LiquidityCurveTest {
    private static final ContractSpec DIAMOND = ContractSpec.defaults().get(0);
    private static final ContractSpec GUNPOWDER = ContractSpec.defaults().get(1);

    private static LiquidityCurve diamond() {
        return DIAMOND.liquidityCurve(1000);
    }

    private static LiquidityCurve gunpowder() {
        return GUNPOWDER.liquidityCurve(100);
    }

    @Test
    @DisplayName("第一档就是买一价/卖一价（最新价 ± 半个点差），每档数量由最新价换算")
    void firstLevelIsTheTouchPrice() {
        assertEquals(1000.25, diamond().level(true, 0).price(), 1e-9);
        assertEquals(999.75, diamond().level(false, 0).price(), 1e-9);
        assertEquals(500, diamond().levelLots());
        assertEquals(100.025, gunpowder().level(true, 0).price(), 1e-9);
        assertEquals(99.975, gunpowder().level(false, 0).price(), 1e-9);
        assertEquals(5, gunpowder().levelLots());
    }

    @Test
    @DisplayName("买盘逐档变贵、卖盘逐档变便宜，每档数量相同")
    void levelsGetWorseStepByStep() {
        // 火药滑点 0.3%，参考名义价值 10,000 对应 20 档，每档价格比上一档差 2 × 0.003 / 19
        double step = 0.006 / 19;
        List<LiquidityCurve.Level> asks = gunpowder().levels(true, 3);
        assertEquals(100.025, asks.get(0).price(), 1e-9);
        assertEquals(100.025 * (1 + step), asks.get(1).price(), 1e-9);
        assertEquals(100.025 * (1 + step) * (1 + step), asks.get(2).price(), 1e-9);
        List<LiquidityCurve.Level> bids = gunpowder().levels(false, 3);
        assertEquals(99.975, bids.get(0).price(), 1e-9);
        assertEquals(99.975 * (1 - step), bids.get(1).price(), 1e-9);
        assertEquals(5, asks.get(2).lots());
        assertEquals(5, bids.get(2).lots());
    }

    @Test
    @DisplayName("手算：火药买 7 手 = 第一档 5 手 + 第二档 2 手；买 5 手以内就是买一价")
    void averagePriceOfAPartialSecondLevel() {
        // (5 × 100.025 + 2 × 100.025 × (1 + 0.006/19)) / 7 = 100.0340248…
        assertEquals(100.03402481203, gunpowder().averageFillPrice(true, 7), 1e-9);
        assertEquals(100.025, gunpowder().averageFillPrice(true, 5), 1e-12);
        assertEquals(100.025, gunpowder().averageFillPrice(true, 1), 1e-12);
    }

    @Test
    @DisplayName("手算：钻石买 1.5 个 = 三档各 500 手；火药卖 12 手 = 5 + 5 + 2")
    void averagePriceAcrossSeveralLevels() {
        // 钻石：1000.25 × (500 + 500 × (1 + g) + 500 × (1 + g)²) / 1500，g = 0.001 / 19
        assertEquals(1000.30264566, diamond().averageFillPrice(true, 1500), 1e-6);
        // 火药卖出：99.975 × (5 + 5 × (1 − g) + 2 × (1 − g)²) / 12，g = 0.006 / 19
        assertEquals(99.95132337, gunpowder().averageFillPrice(false, 12), 1e-6);
    }

    @Test
    @DisplayName("成交均价随数量单调变差：买入不降、卖出不升，超过第一档后严格变差")
    void averagePriceGetsWorseWithSize() {
        for (LiquidityCurve curve : List.of(diamond(), gunpowder())) {
            double previousBuy = curve.averageFillPrice(true, 1);
            double previousSell = curve.averageFillPrice(false, 1);
            for (long lots = 2; lots <= 5000; lots++) {
                double buy = curve.averageFillPrice(true, lots);
                double sell = curve.averageFillPrice(false, lots);
                assertTrue(buy >= previousBuy, "买入均价不应下降：" + lots);
                assertTrue(sell <= previousSell, "卖出均价不应上升：" + lots);
                if (lots > curve.levelLots()) {
                    assertTrue(buy > curve.averageFillPrice(true, 1), "超过第一档后买入应更贵：" + lots);
                    assertTrue(sell < curve.averageFillPrice(false, 1), "超过第一档后卖出应更便宜：" + lots);
                }
                previousBuy = buy;
                previousSell = sell;
            }
        }
    }

    @Test
    @DisplayName("默认深度：10,000 CRP 名义价值的市价单，钻石滑点约 0.05%，火药约 0.3%（相对买一价/卖一价，容差 ±10%）")
    void defaultDepthMatchesTheCalibration() {
        // 钻石最新价 1000：买一价 1000.25，10,000 CRP 约 9997 手（9.997 个）
        double diamondBuy = diamond().averageFillPrice(true, 9997);
        double diamondSell = diamond().averageFillPrice(false, 10003);
        assertEquals(0.0005, diamondBuy / 1000.25 - 1, 0.0005 * 0.1);
        assertEquals(0.0005, 1 - diamondSell / 999.75, 0.0005 * 0.1);
        // 火药最新价 100：10,000 CRP 约 100 手
        double gunpowderBuy = gunpowder().averageFillPrice(true, 100);
        double gunpowderSell = gunpowder().averageFillPrice(false, 100);
        assertEquals(0.003, gunpowderBuy / 100.025 - 1, 0.003 * 0.1);
        assertEquals(0.003, 1 - gunpowderSell / 99.975, 0.003 * 0.1);
        // 含半个点差、相对最新价的口径：钻石 0.025% + 0.05% ≈ 0.075%，火药 0.025% + 0.3% ≈ 0.325%
        assertEquals(0.00075, diamondBuy / 1000 - 1, 0.00075 * 0.1);
        assertEquals(0.00325, gunpowderBuy / 100 - 1, 0.00325 * 0.1);
    }

    @Test
    @DisplayName("盘口逐档吃单算出的均价 = 实际成交均价（买卖两侧，含最后一档部分成交）")
    void eatingTheDisplayedLevelsGivesTheFillPrice() {
        for (LiquidityCurve curve : List.of(diamond(), gunpowder())) {
            for (boolean buy : new boolean[] {true, false}) {
                List<LiquidityCurve.Level> levels = curve.levels(buy, 12);
                long levelLots = curve.levelLots();
                for (long lots : new long[] {1, levelLots, levelLots + 1, levelLots * 3, levelLots * 5 + 7, levelLots * 11 + 1}) {
                    double cost = 0;
                    long left = lots;
                    for (LiquidityCurve.Level level : levels) {
                        long eaten = Math.min(left, level.lots());
                        cost += eaten * level.price();
                        left -= eaten;
                    }
                    assertEquals(0, left, "盘口应该吃得下这笔单");
                    assertEquals(cost / lots, curve.averageFillPrice(buy, lots), 1e-9, (buy ? "买入 " : "卖出 ") + lots + " 手");
                }
            }
        }
    }

    @Test
    @DisplayName("成交价只取决于合约深度参数和传入的最新价：同一份曲线反复成交，价格不变")
    void curveDoesNotRememberFills() {
        LiquidityCurve curve = gunpowder();
        double first = curve.averageFillPrice(true, 400);
        curve.averageFillPrice(true, 4000);
        assertEquals(first, curve.averageFillPrice(true, 400), 0.0);
        assertEquals(100.025, curve.level(true, 0).price(), 1e-12);
    }

    @Test
    @DisplayName("滑点设为 0：整条曲线是平的，大单也按买一价/卖一价成交")
    void zeroSlippageIsFlat() {
        LiquidityCurve flat = LiquidityCurve.of(0.05, 0.0, 0, 100);
        assertEquals(100.025, flat.averageFillPrice(true, 100000), 1e-9);
        assertEquals(99.975, flat.averageFillPrice(false, 100000), 1e-9);
    }

    @Test
    @DisplayName("数量非正、参数不合法：直接拒绝")
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> diamond().averageFillPrice(true, 0));
        assertThrows(IllegalArgumentException.class, () -> diamond().level(true, -1));
        assertThrows(IllegalArgumentException.class, () -> LiquidityCurve.of(0.5, -0.1, 3, 1000));
        assertThrows(IllegalArgumentException.class, () -> LiquidityCurve.of(0.5, 0.6, 3, 1000));
        assertThrows(IllegalArgumentException.class, () -> LiquidityCurve.of(0.5, Double.NaN, 3, 1000));
        assertThrows(IllegalArgumentException.class, () -> LiquidityCurve.of(0.5, 0.001, 3, 0));
    }

    @Test
    @DisplayName("价格很高、精度为 0 时每档至少 1 手")
    void levelHoldsAtLeastOneLot() {
        assertEquals(1, LiquidityCurve.of(1.0, 0.001, 0, 100000).levelLots());
    }
}
