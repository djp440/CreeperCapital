package io.github.djp440.creepercapital.core.market;

/**
 * 逼真的指数价格模型：几种随机成分叠加，价格始终为正（在对数空间里演化）。
 * <ul>
 * <li>基本面价值缓慢漂移：漂移方向由市场状态决定，并向合约的起始价格缓慢回归，所以长期不会发散；</li>
 * <li>价格围绕基本面价值均值回归：日常扩散造成的偏离回归得很慢（半衰期数个市场日），跳跃留下的偏离几小时内回吐大半；</li>
 * <li>GARCH(1,1) 波动聚集，叠加市场状态各自的波动水平；</li>
 * <li>扩散项服从 Student-t 分布，肥尾；</li>
 * <li>泊松跳跃，幅度在上下限之间按对数均匀分布，约 30% 永久计入基本面价值；</li>
 * <li>牛市、熊市、震荡三种市场状态随机切换。</li>
 * </ul>
 * 各成分的强度按合约的 {@link VolatilityProfile} 反推（见 {@link Calibration}），使按市场日计的收益率标准差等于配置的日波动率。
 * 模型自己不持有随机数发生器，每步由调用方传入，所以随机数流的归属（每合约一个）由调用方决定。
 */
final class IndexModel {
    static final int STEPS_PER_DAY = 24 * 60 * 60 / Market.MARKET_SECONDS_PER_STEP;

    /** 基本面价值向起始价格回归的半衰期（市场日）：慢到不影响趋势，又足以让价格长期不发散。 */
    private static final double ANCHOR_HALF_LIFE_DAYS = 40;
    /** 基本面价值自身的扩散强度，以日波动率的倍数计：它是「缓慢」漂移的。 */
    private static final double FUNDAMENTAL_NOISE_SHARE = 0.25;
    /** 日常扩散造成的偏离向基本面价值回归的半衰期（市场日）。 */
    private static final double SLOW_HALF_LIFE_DAYS = 5;
    /** 跳跃中永久计入基本面价值的比例，其余是价格的瞬时过冲，随后回吐。 */
    private static final double JUMP_PERMANENT_SHARE = 0.3;
    /** 跳跃过冲回吐的半衰期（市场小时）。 */
    private static final double JUMP_RETRACE_HALF_LIFE_HOURS = 1.5;
    private static final double GARCH_ALPHA = 0.012;
    private static final double GARCH_BETA = 0.986;
    /** 扩散项的 Student-t 自由度：越小尾部越肥，必须大于 4 才有有限的峰度。 */
    private static final int STUDENT_DEGREES_OF_FREEDOM = 5;
    private static final double MIN_VOLATILITY_RATIO = 0.05;
    private static final double MAX_VOLATILITY_RATIO = 25;
    /** 日常扩散至少要占日方差的这个比例，否则说明跳跃等成分已经超过了配置的日波动率。 */
    private static final double MIN_DIFFUSION_SHARE = 0.15;
    private static final int JUMP_INTEGRATION_POINTS = 400;

    private static final double ANCHOR_REVERSION = reversionPerStep(ANCHOR_HALF_LIFE_DAYS * STEPS_PER_DAY);
    private static final double SLOW_REVERSION = reversionPerStep(SLOW_HALF_LIFE_DAYS * STEPS_PER_DAY);
    private static final double JUMP_RETRACE =
            reversionPerStep(JUMP_RETRACE_HALF_LIFE_HOURS * 60 * 60 / Market.MARKET_SECONDS_PER_STEP);

    private final Calibration calibration;
    private double logFundamental;
    private double slowDeviation;
    private double jumpDeviation;
    private double volatilityRatio;
    private Regime regime;
    private double pendingJump;

    IndexModel(Calibration calibration, IndexState state) {
        this.calibration = calibration;
        this.logFundamental = state.logFundamental();
        this.slowDeviation = state.slowDeviation();
        this.jumpDeviation = state.jumpDeviation();
        this.volatilityRatio = state.volatilityRatio();
        this.regime = state.regime();
        this.pendingJump = state.pendingJump();
    }

    /** 一个行情步的结果：对数收益率，以及这一步里发生的跳跃（对数收益率，没有则为 0）。 */
    record Step(double logReturn, double randomJump, double manualJump) {
    }

    IndexState state() {
        return new IndexState(logFundamental, slowDeviation, jumpDeviation, volatilityRatio, regime, pendingJump);
    }

    double price() {
        return Math.exp(logFundamental + slowDeviation + jumpDeviation);
    }

    Regime regime() {
        return regime;
    }

    /** 排队一次手动跳跃，在下一个行情步里与日常波动一起生效。多次排队会叠加。 */
    void queueJump(double logReturn) {
        pendingJump += logReturn;
    }

    /** 走一个行情步。 */
    Step next(MarketRandom random) {
        Regime current = regime;
        double scale = Math.sqrt(volatilityRatio) * current.volatility();
        double shock = random.nextStandardizedStudentT(STUDENT_DEGREES_OF_FREEDOM);
        double fundamentalNoise = random.nextGaussian();
        double regimeDraw = random.nextDouble();
        double jumpDraw = random.nextDouble();

        double manualJump = pendingJump;
        pendingJump = 0;
        double randomJump = jumpDraw < calibration.jumpProbability ? drawJump(random) : 0.0;
        double jump = manualJump + randomJump;

        double fundamentalMove = calibration.driftPerStep(current)
                + ANCHOR_REVERSION * (calibration.anchorLog - logFundamental)
                + calibration.fundamentalSigma * scale * fundamentalNoise
                + JUMP_PERMANENT_SHARE * jump;
        double newSlow = (1 - SLOW_REVERSION) * slowDeviation + calibration.slowSigma * scale * shock;
        double newJumpDeviation = (1 - JUMP_RETRACE) * jumpDeviation + (1 - JUMP_PERMANENT_SHARE) * jump;
        double logReturn = fundamentalMove + (newSlow - slowDeviation) + (newJumpDeviation - jumpDeviation);

        logFundamental += fundamentalMove;
        slowDeviation = newSlow;
        jumpDeviation = newJumpDeviation;
        // GARCH(1,1)：条件方差 = 长期方差 × 比值，冲击大了下一步的比值就升高，之后慢慢回落
        volatilityRatio = Math.max(MIN_VOLATILITY_RATIO, Math.min(MAX_VOLATILITY_RATIO,
                (1 - GARCH_ALPHA - GARCH_BETA) + (GARCH_ALPHA * shock * shock + GARCH_BETA) * volatilityRatio));
        double leaveProbability = current.leaveProbability();
        if (regimeDraw < leaveProbability) {
            regime = current.successor(regimeDraw / leaveProbability);
        }
        return new Step(logReturn, randomJump, manualJump);
    }

    /** 抽一次随机跳跃的对数收益率：幅度在上下限之间对数均匀分布，涨跌各半。 */
    private double drawJump(MarketRandom random) {
        double ratio = calibration.jumpMin * Math.pow(calibration.jumpMax / calibration.jumpMin, random.nextDouble());
        return random.nextDouble() < 0.5 ? Math.log1p(ratio) : Math.log1p(-ratio);
    }

    /** 一个行情步成交量的放大倍数：1 + |收益率| / 平时的步波动率，平静时约为 1，大幅波动时随之放大。 */
    double volumeMultiplier(double logReturn) {
        return 1 + Math.abs(logReturn) / calibration.stepVolatility;
    }

    private static double reversionPerStep(double halfLifeSteps) {
        return 1 - Math.pow(0.5, 1.0 / halfLifeSteps);
    }

    /**
     * 按波动率倾向反推各成分的强度。
     * <p>
     * 按市场日计的收益率方差由四部分相加：市场状态漂移造成的、基本面价值自身扩散的、跳跃造成的（含过冲回吐），
     * 余下的由慢偏离的日常扩散补足，所以日波动率配置多少，长样本里就实现多少。跳跃自己就用完了方差预算时无法兑现，直接拒绝。
     */
    static final class Calibration {
        private final double fundamentalSigma;
        private final double slowSigma;
        private final double jumpProbability;
        private final double jumpMin;
        private final double jumpMax;
        private final double anchorLog;
        private final double stepVolatility;
        private final double dailyVolatility;

        private Calibration(double fundamentalSigma, double slowSigma, VolatilityProfile profile, double anchorLog) {
            this.fundamentalSigma = fundamentalSigma;
            this.slowSigma = slowSigma;
            this.jumpProbability = profile.jumpsPerDay() / STEPS_PER_DAY;
            this.jumpMin = profile.jumpMin();
            this.jumpMax = profile.jumpMax();
            this.anchorLog = anchorLog;
            this.dailyVolatility = profile.dailyVolatility();
            this.stepVolatility = profile.dailyVolatility() / Math.sqrt(STEPS_PER_DAY);
        }

        /** @throws IllegalArgumentException 日波动率小于跳跃等成分自己造成的波动 */
        static Calibration of(String symbol, double initialPrice, VolatilityProfile profile) {
            double daily = profile.dailyVolatility();
            double dailyVariance = daily * daily;
            double driftVariance = 0;
            for (Regime regime : Regime.values()) {
                double drift = regime.driftPerDay() * daily;
                driftVariance += Regime.Shares.of(regime) * drift * drift;
            }
            double fundamentalVariance = FUNDAMENTAL_NOISE_SHARE * FUNDAMENTAL_NOISE_SHARE * dailyVariance;
            double jumpVariance = profile.jumpsPerDay() * jumpSecondMoment(profile) * jumpResponse();
            double diffusionVariance = dailyVariance - driftVariance - fundamentalVariance - jumpVariance;
            if (diffusionVariance < MIN_DIFFUSION_SHARE * dailyVariance) {
                throw new IllegalArgumentException(String.format(
                        "合约 %s 的日波动率 %.2f%% 低于跳跃（每日 %.2f 次，%.1f%%~%.1f%%）自身造成的波动，"
                                + "请调高日波动率，或降低跳跃频率与幅度",
                        symbol, daily * 100, profile.jumpsPerDay(), profile.jumpMin() * 100, profile.jumpMax() * 100));
            }
            // 慢偏离的日收益方差 = 2·Var(y)·(1 − ρ^T)，Var(y) = s²/(1 − ρ²)
            double rho = 1 - SLOW_REVERSION;
            double dailyFactor = 2 * (1 - Math.pow(rho, STEPS_PER_DAY)) / (1 - rho * rho);
            return new Calibration(FUNDAMENTAL_NOISE_SHARE * daily / Math.sqrt(STEPS_PER_DAY),
                    Math.sqrt(diffusionVariance / dailyFactor), profile, Math.log(initialPrice));
        }

        double driftPerStep(Regime regime) {
            return regime.driftPerDay() * dailyVolatility / STEPS_PER_DAY;
        }

        /** 单次跳跃对数收益率的二阶矩，对幅度的对数均匀分布数值积分，涨跌各半。 */
        private static double jumpSecondMoment(VolatilityProfile profile) {
            double sum = 0;
            for (int i = 0; i < JUMP_INTEGRATION_POINTS; i++) {
                double ratio = profile.jumpMin()
                        * Math.pow(profile.jumpMax() / profile.jumpMin(), (i + 0.5) / JUMP_INTEGRATION_POINTS);
                double up = Math.log1p(ratio);
                double down = Math.log1p(-ratio);
                sum += 0.5 * (up * up + down * down);
            }
            return sum / JUMP_INTEGRATION_POINTS;
        }

        /**
         * 一次单位大小的跳跃对「一个市场日内收益率方差」的贡献系数：永久部分整日计入，过冲部分只在回吐完之前计入，
         * 当日之前的跳跃也会因回吐而影响当日的收益。按连续时间的泊松冲击积分得出闭式解。
         */
        private static double jumpResponse() {
            double k = -Math.log(1 - JUMP_RETRACE) * STEPS_PER_DAY;
            double permanent = JUMP_PERMANENT_SHARE;
            double transientShare = 1 - permanent;
            double decayedOnce = 1 - Math.exp(-k);
            return permanent * permanent
                    + 2 * permanent * transientShare * decayedOnce / k
                    + transientShare * transientShare * (1 - Math.exp(-2 * k)) / (2 * k)
                    + transientShare * transientShare * decayedOnce * decayedOnce / (2 * k);
        }
    }
}
