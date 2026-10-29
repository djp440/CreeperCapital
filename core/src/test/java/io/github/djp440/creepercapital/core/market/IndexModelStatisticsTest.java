package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 逼真行情模型的长样本统计检验。种子固定，所以结果完全可复现；容差按样本量写在各断言旁边：
 * 300 个市场日的日收益率标准差，抽样误差约 5%~8%，所以容差取 ±20%。
 */
class IndexModelStatisticsTest {
    private static final String DIAMOND = "DIAMONDCRP";
    private static final String GUNPOWDER = "GUNPOWDERCRP";
    private static final long SEED = 2026L;
    private static final int STEPS_PER_DAY = IndexModel.STEPS_PER_DAY;
    private static final int STEPS_PER_MINUTE = 10;
    private static final int DAYS = 300;

    private static final Map<String, Sample> SAMPLES = new HashMap<>();

    private static ContractSpec spec(String symbol) {
        return ContractSpec.defaults().stream().filter(s -> s.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    /** 同一份合约定义，关掉随机跳跃：用来单独观察日常扩散的波动聚集。 */
    private static ContractSpec withoutJumps(ContractSpec spec) {
        VolatilityProfile v = spec.volatility();
        return new ContractSpec(spec.symbol(), spec.underlying(), spec.initialPrice(), spec.baseVolume(),
                new VolatilityProfile(v.dailyVolatility(), 0, v.jumpMin(), v.jumpMax(), v.premiumBias()));
    }

    private static Sample sample(String key, ContractSpec spec, int days) {
        return SAMPLES.computeIfAbsent(key + days, k -> Sample.simulate(spec, SEED, days));
    }

    private static Sample defaultSample(String symbol) {
        return sample("default:" + symbol, spec(symbol), DAYS);
    }

    @Test
    @DisplayName("日波动率与配置一致：300 日样本里，按市场日计的收益率标准差在配置值的 ±20% 内（钻石约 3%，火药约 10%）")
    void dailyVolatilityMatchesTheConfiguredProfile() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            double configured = spec(symbol).volatility().dailyVolatility();
            double realized = standardDeviation(defaultSample(symbol).dailyReturns);

            assertEquals(configured, realized, 0.2 * configured, symbol + " 日波动率，实现 " + realized);
        }
    }

    @Test
    @DisplayName("配置里的日波动率改了，行情的波动随之改变：同一种子下 2% 与 6% 的实现波动各在 ±25% 内，且后者约为前者的 3 倍")
    void editingTheVolatilityProfileChangesTheRealizedVolatility() {
        ContractSpec base = withoutJumps(spec(GUNPOWDER));
        ContractSpec calm = withVolatility(base, 0.02);
        ContractSpec wild = withVolatility(base, 0.06);

        double calmVolatility = standardDeviation(sample("calm", calm, 200).dailyReturns);
        double wildVolatility = standardDeviation(sample("wild", wild, 200).dailyReturns);

        assertEquals(0.02, calmVolatility, 0.25 * 0.02);
        assertEquals(0.06, wildVolatility, 0.25 * 0.06);
        assertEquals(3.0, wildVolatility / calmVolatility, 0.6);
    }

    @Test
    @DisplayName("肥尾：1 分钟收益率的超额峰度为正，峰度大于 4（正态为 3），两个合约都是")
    void minuteReturnsAreFatTailed() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            double kurtosis = kurtosis(defaultSample(symbol).minuteReturns);

            assertTrue(kurtosis > 4, symbol + " 1 分钟收益率峰度应大于 4，实际 " + kurtosis);
        }
    }

    @Test
    @DisplayName("收益率本身几乎没有自相关（|ρ| < 0.03，滞后 1 步、10 步、720 步），价格没有可预测的方向")
    void returnsAreNotAutocorrelated() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            Sample sample = defaultSample(symbol);
            for (int lag : Sample.LAGS) {
                double rho = sample.returnAutocorrelation.get(lag);
                assertTrue(Math.abs(rho) < 0.03, symbol + " 收益率滞后 " + lag + " 步的自相关应接近 0，实际 " + rho);
            }
        }
    }

    @Test
    @DisplayName("波动聚集：|收益率| 的自相关为正且缓慢衰减——滞后 1 步最强，滞后 720 步（72 市场分钟）仍为正，滞后一整个市场日仍为正（关掉随机跳跃，单看日常扩散）")
    void absoluteReturnsClusterAndDecaySlowly() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            Sample sample = sample("nojump:" + symbol, withoutJumps(spec(symbol)), 200);
            double lag1 = sample.absoluteAutocorrelation.get(1);
            double lag10 = sample.absoluteAutocorrelation.get(10);
            double lag720 = sample.absoluteAutocorrelation.get(720);
            double lagDay = sample.absoluteAutocorrelation.get(STEPS_PER_DAY);

            assertTrue(lag1 > 0.05, symbol + " 滞后 1 步应有明显的正自相关，实际 " + lag1);
            assertTrue(lag1 > lag10 && lag10 > lag720, symbol + " 应随滞后增大而衰减：" + lag1 + " > " + lag10 + " > " + lag720);
            assertTrue(lag10 > 0.5 * lag1, symbol + " 衰减应是缓慢的：滞后 10 步仍保留滞后 1 步的一半以上，" + lag10 + " vs " + lag1);
            assertTrue(lag720 > 0.01, symbol + " 滞后 720 步应仍为正（缓慢衰减），实际 " + lag720);
            assertTrue(lagDay > 0, symbol + " 滞后一个市场日应仍为正，实际 " + lagDay);
        }
    }

    @Test
    @DisplayName("含随机跳跃时 |收益率| 的自相关同样为正：滞后 1 步和 720 步都大于 0")
    void absoluteReturnsClusterWithJumpsToo() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            Sample sample = defaultSample(symbol);

            assertTrue(sample.absoluteAutocorrelation.get(1) > 0.0, symbol + " 滞后 1 步");
            assertTrue(sample.absoluteAutocorrelation.get(720) > 0.0, symbol + " 滞后 720 步");
        }
    }

    @Test
    @DisplayName("火药每个市场日 1~3 次随机跳跃，幅度都在 5%~20% 内，涨跌都有")
    void gunpowderJumpsOneToThreeTimesADay() {
        List<JumpEvent> jumps = defaultSample(GUNPOWDER).jumps;

        double perDay = jumps.size() / (double) DAYS;
        assertTrue(perDay >= 1 && perDay <= 3, "火药每日跳跃次数应在 1~3 之间，实际 " + perDay);
        for (JumpEvent jump : jumps) {
            assertEquals(JumpEvent.Cause.RANDOM, jump.cause());
            assertTrue(Math.abs(jump.changeRatio()) >= 0.05 - 1e-9 && Math.abs(jump.changeRatio()) <= 0.20 + 1e-9,
                    "跳跃幅度应在 5%~20% 内，实际 " + jump.changeRatio());
        }
        assertTrue(jumps.stream().anyMatch(JumpEvent::isUp), "应有上涨的跳跃");
        assertTrue(jumps.stream().anyMatch(j -> !j.isUp()), "应有下跌的跳跃");
    }

    @Test
    @DisplayName("随机跳跃立刻落在价格上：发生跳跃的那一步，指数价格的对数收益等于跳跃的对数幅度（误差 1%，日常扩散在单步里只有约 0.1%）")
    void randomJumpsLandOnThePriceImmediately() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            Sample sample = defaultSample(symbol);

            assertTrue(sample.jumpLandings.size() > 0, symbol + " 应有随机跳跃");
            for (double[] landing : sample.jumpLandings) {
                double expected = Math.log1p(landing[0]);
                assertEquals(expected, landing[1], 0.01, symbol + " 跳跃 " + landing[0] + " 当步的对数收益");
            }
        }
    }

    @Test
    @DisplayName("成交量与波动正相关：逐步看，|收益率| 越大成交量越大（ρ > 0.3）；按市场小时看，已实现波动越高成交量越大（秩相关 ρ > 0.3；跳跃所在的小时方差极大，用秩相关免得被少数极端值主导）")
    void volumeIsPositivelyCorrelatedWithVolatility() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            Sample sample = defaultSample(symbol);

            assertTrue(sample.volumeReturnCorrelation > 0.3, symbol + " 逐步成交量与 |收益率| 的相关系数应为正，实际 " + sample.volumeReturnCorrelation);
            assertTrue(sample.hourlyVolumeVolatilityCorrelation > 0.3,
                    symbol + " 逐小时成交量与已实现波动的秩相关系数应为正，实际 " + sample.hourlyVolumeVolatilityCorrelation);
        }
    }

    @Test
    @DisplayName("钻石的跳跃明显更少：每日次数不到火药的四分之一，幅度在它自己配置的 3%~8% 内")
    void diamondJumpsFarLessOftenThanGunpowder() {
        List<JumpEvent> diamond = defaultSample(DIAMOND).jumps;
        List<JumpEvent> gunpowder = defaultSample(GUNPOWDER).jumps;

        assertTrue(diamond.size() * 4 < gunpowder.size(),
                "钻石 " + diamond.size() + " 次，火药 " + gunpowder.size() + " 次");
        assertTrue(diamond.size() > 0, "300 个市场日里钻石也该偶尔跳一下");
        for (JumpEvent jump : diamond) {
            assertTrue(Math.abs(jump.changeRatio()) >= 0.03 - 1e-9 && Math.abs(jump.changeRatio()) <= 0.08 + 1e-9,
                    "钻石跳跃幅度应在 3%~8% 内，实际 " + jump.changeRatio());
        }
    }

    @Test
    @DisplayName("价格不会为负，也不会发散：300 个市场日内始终在起始价格的 1/10 到 10 倍之间")
    void priceStaysPositiveAndBounded() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            Sample sample = defaultSample(symbol);
            double initial = spec(symbol).initialPrice();

            assertTrue(sample.minPrice > initial / 10, symbol + " 最低 " + sample.minPrice);
            assertTrue(sample.maxPrice < initial * 10, symbol + " 最高 " + sample.maxPrice);
        }
    }

    @Test
    @DisplayName("极端配置（日波动率 60%、每日 5 次跳跃）下价格仍然为正且有限")
    void extremeProfileStillGivesFinitePositivePrices() {
        ContractSpec extreme = new ContractSpec("WILDCRP", "minecraft:tnt", 50.0, 10.0,
                new VolatilityProfile(0.6, 5, 0.05, 0.20, 0.01));
        Sample sample = sample("extreme", extreme, 100);

        assertTrue(sample.minPrice > 0 && Double.isFinite(sample.minPrice), "最低价 " + sample.minPrice);
        assertTrue(Double.isFinite(sample.maxPrice), "最高价 " + sample.maxPrice);
    }

    @Test
    @DisplayName("三种市场状态都会出现并不断切换（300 日内切换至少 20 次）")
    void marketRegimesKeepSwitching() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            Sample sample = defaultSample(symbol);

            for (Regime regime : Regime.values()) {
                assertTrue(sample.regimeSteps[regime.ordinal()] > STEPS_PER_DAY, symbol + " 应出现过" + regime);
            }
            assertTrue(sample.regimeChanges >= 20, symbol + " 市场状态切换次数 " + sample.regimeChanges);
        }
    }

    @Test
    @DisplayName("溢价随市场状态而动：牛市平均溢价为正、熊市为负、震荡接近 0，且牛 > 震荡 > 熊；均值落在配置的溢价倾向附近")
    void premiumFollowsTheMarketRegime() {
        for (String symbol : List.of(DIAMOND, GUNPOWDER)) {
            Sample sample = defaultSample(symbol);
            double bias = spec(symbol).volatility().premiumBias();
            double bull = sample.premiumMean(Regime.BULL);
            double bear = sample.premiumMean(Regime.BEAR);
            double range = sample.premiumMean(Regime.RANGE);

            assertTrue(bull > 0.5 * bias, symbol + " 牛市平均溢价应为正，实际 " + bull + "（倾向 " + bias + "）");
            assertTrue(bear < -0.5 * bias, symbol + " 熊市平均溢价应为负，实际 " + bear);
            assertTrue(Math.abs(range) < 0.5 * bias, symbol + " 震荡平均溢价应接近 0，实际 " + range);
            assertTrue(bull > range && range > bear, symbol + " 应满足牛市 > 震荡 > 熊市");
            assertEquals(bias, bull, 0.5 * bias, symbol + " 牛市平均溢价应在配置的溢价倾向附近");
            assertEquals(-bias, bear, 0.5 * bias, symbol + " 熊市平均溢价应在配置的溢价倾向附近");
        }
    }

    @Test
    @DisplayName("跳跃只部分回吐：手动跳 +20%，价格立刻跳 +20%，1.5 市场小时后回吐过半，十几小时后只剩约 30% 永久计入基本面")
    void jumpRetracesPartially() {
        ContractSpec gunpowder = spec(GUNPOWDER);
        Market jumping = new Market(SEED, List.of(gunpowder));
        Market control = new Market(SEED, List.of(gunpowder));
        jumping.queueJump(GUNPOWDER, 0.20);

        // 同一种子的对照市场里随机数完全相同，所以两边价格之比只剩跳跃自身的影响，没有日常波动的噪声
        double afterOneStep = 0;
        double afterOneAndAHalfHours = 0;
        double afterFifteenHours = 0;
        for (int step = 1; step <= 9_000; step++) {
            double ratio = stepSingle(jumping).indexPrice() / stepSingle(control).indexPrice();
            if (step == 1) {
                afterOneStep = ratio;
            } else if (step == 900) { // 90 市场分钟 = 1.5 小时
                afterOneAndAHalfHours = ratio;
            } else if (step == 9_000) { // 15 小时
                afterFifteenHours = ratio;
            }
        }

        double logJump = Math.log(1.2);
        assertEquals(1.2, afterOneStep, 1e-4, "跳跃当步立刻落地");
        assertEquals(Math.exp((0.3 + 0.7 * 0.5) * logJump), afterOneAndAHalfHours, 3e-3, "1.5 小时后过冲回吐一半");
        assertEquals(Math.exp(0.3 * logJump), afterFifteenHours, 3e-3, "15 小时后只剩永久部分");
    }

    private static MarketSnapshot stepSingle(Market market) {
        while (true) {
            List<MarketSnapshot> stepped = market.tick();
            if (!stepped.isEmpty()) {
                return stepped.get(0);
            }
        }
    }

    private static ContractSpec withVolatility(ContractSpec spec, double dailyVolatility) {
        VolatilityProfile v = spec.volatility();
        return new ContractSpec(spec.symbol(), spec.underlying(), spec.initialPrice(), spec.baseVolume(),
                new VolatilityProfile(dailyVolatility, v.jumpsPerDay(), v.jumpMin(), v.jumpMax(), v.premiumBias()));
    }

    private static double standardDeviation(double[] values) {
        double mean = 0;
        for (double value : values) {
            mean += value / values.length;
        }
        double sum = 0;
        for (double value : values) {
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / (values.length - 1));
    }

    private static double kurtosis(double[] values) {
        double mean = 0;
        for (double value : values) {
            mean += value / values.length;
        }
        double m2 = 0;
        double m4 = 0;
        for (double value : values) {
            double d = value - mean;
            m2 += d * d;
            m4 += d * d * d * d;
        }
        m2 /= values.length;
        m4 /= values.length;
        return m4 / (m2 * m2);
    }

    /** 每个值的名次（从 0 起，值相同的名次随排序而定，对本检验无影响）。 */
    private static double[] ranks(double[] values) {
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));
        double[] ranks = new double[values.length];
        for (int rank = 0; rank < order.length; rank++) {
            ranks[order[rank]] = rank;
        }
        return ranks;
    }

    private static double correlation(double[] x, double[] y) {
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < x.length; i++) {
            meanX += x[i] / x.length;
            meanY += y[i] / x.length;
        }
        double covariance = 0;
        double varianceX = 0;
        double varianceY = 0;
        for (int i = 0; i < x.length; i++) {
            covariance += (x[i] - meanX) * (y[i] - meanY);
            varianceX += (x[i] - meanX) * (x[i] - meanX);
            varianceY += (y[i] - meanY) * (y[i] - meanY);
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }

    private static double autocorrelation(double[] values, int lag) {
        double mean = 0;
        for (double value : values) {
            mean += value / values.length;
        }
        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < values.length; i++) {
            double d = values[i] - mean;
            denominator += d * d;
            if (i + lag < values.length) {
                numerator += d * (values[i + lag] - mean);
            }
        }
        return numerator / denominator;
    }

    /** 一次长样本模拟的汇总。大数组只在模拟时存在，汇总完即可回收。 */
    private static final class Sample {
        static final List<Integer> LAGS = List.of(1, 10, 720);
        private static final List<Integer> ABSOLUTE_LAGS = List.of(1, 10, 720, STEPS_PER_DAY);

        final double[] dailyReturns;
        final double[] minuteReturns;
        final Map<Integer, Double> returnAutocorrelation = new HashMap<>();
        final Map<Integer, Double> absoluteAutocorrelation = new HashMap<>();
        final List<JumpEvent> jumps = new ArrayList<>();
        /** 每个发生过随机跳跃的行情步：{这一步里跳跃的总变化比例, 这一步指数价格的对数收益}。 */
        final List<double[]> jumpLandings = new ArrayList<>();
        double volumeReturnCorrelation;
        double hourlyVolumeVolatilityCorrelation;
        final double[] premiumSum = new double[Regime.values().length];
        final long[] regimeSteps = new long[Regime.values().length];
        int regimeChanges;
        double minPrice;
        double maxPrice;

        private Sample(int days) {
            dailyReturns = new double[days];
            minuteReturns = new double[days * STEPS_PER_DAY / STEPS_PER_MINUTE];
        }

        double premiumMean(Regime regime) {
            return premiumSum[regime.ordinal()] / regimeSteps[regime.ordinal()];
        }

        static Sample simulate(ContractSpec spec, long seed, int days) {
            Sample sample = new Sample(days);
            Market market = new Market(seed, List.of(spec));
            market.addJumpListener(sample.jumps::add);
            int steps = days * STEPS_PER_DAY;
            double[] stepReturns = new double[steps];
            double[] absoluteReturns = new double[steps];
            double[] volumes = new double[steps];
            int stepsPerHour = 3600 / Market.MARKET_SECONDS_PER_STEP;
            double[] hourlyVolume = new double[steps / stepsPerHour];
            double[] hourlyVariance = new double[steps / stepsPerHour];
            double previous = spec.initialPrice();
            double dayStart = previous;
            double minuteStart = previous;
            sample.minPrice = previous;
            sample.maxPrice = previous;
            Regime inForce = market.regime(spec.symbol());
            for (int i = 0; i < steps; i++) {
                int jumpsBefore = sample.jumps.size();
                MarketSnapshot snapshot = stepSingle(market);
                double price = snapshot.indexPrice();
                stepReturns[i] = Math.log(price / previous);
                absoluteReturns[i] = Math.abs(stepReturns[i]);
                volumes[i] = snapshot.volume();
                hourlyVolume[i / stepsPerHour] += snapshot.volume();
                hourlyVariance[i / stepsPerHour] += stepReturns[i] * stepReturns[i];
                if (sample.jumps.size() > jumpsBefore) {
                    double logJump = 0;
                    for (JumpEvent event : sample.jumps.subList(jumpsBefore, sample.jumps.size())) {
                        logJump += Math.log1p(event.changeRatio());
                    }
                    sample.jumpLandings.add(new double[] {Math.expm1(logJump), stepReturns[i]});
                }
                previous = price;
                sample.minPrice = Math.min(sample.minPrice, price);
                sample.maxPrice = Math.max(sample.maxPrice, price);
                sample.premiumSum[inForce.ordinal()] += snapshot.lastPrice() / price - 1;
                sample.regimeSteps[inForce.ordinal()]++;
                Regime next = market.regime(spec.symbol());
                if (next != inForce) {
                    sample.regimeChanges++;
                    inForce = next;
                }
                if ((i + 1) % STEPS_PER_MINUTE == 0) {
                    sample.minuteReturns[i / STEPS_PER_MINUTE] = Math.log(price / minuteStart);
                    minuteStart = price;
                }
                if ((i + 1) % STEPS_PER_DAY == 0) {
                    sample.dailyReturns[i / STEPS_PER_DAY] = Math.log(price / dayStart);
                    dayStart = price;
                }
            }
            sample.volumeReturnCorrelation = correlation(volumes, absoluteReturns);
            sample.hourlyVolumeVolatilityCorrelation = correlation(ranks(hourlyVolume), ranks(hourlyVariance));
            for (int lag : LAGS) {
                sample.returnAutocorrelation.put(lag, autocorrelation(stepReturns, lag));
            }
            for (int lag : ABSOLUTE_LAGS) {
                sample.absoluteAutocorrelation.put(lag, autocorrelation(absoluteReturns, lag));
            }
            return sample;
        }
    }
}
