package io.github.djp440.creepercapital.core.market;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 合约的定义：标的资源、起始价格、基础成交量和波动率倾向。由服务端配置文件提供，核心不关心它怎么读进来。
 *
 * @param symbol       交易代码，格式为「标的资源英文名 + CRP」，例如 DIAMONDCRP；它会用作 K 线文件名，所以只允许大写字母、数字和下划线
 * @param underlying   标的资源（原版物品的标识符，例如 {@code minecraft:diamond}）；合约只结算苦力怕币，从不交付物品
 * @param initialPrice 新市场的起始指数价格（CRP）；基本面价值也向它缓慢回归，所以它是价格长期的中枢
 * @param baseVolume   平静时每个行情步的成交量（标的资源数量）
 * @param volatility   波动率倾向
 * @param spread       点差（CRP，价格的绝对间隔）：卖一价与买一价之差，最新价格位于正中；市价委托的成交价是最新价格 ± 半个点差
 * @param maxLeverage  最高杠杆倍数
 * @param quantityDecimals 数量精度（小数位数）：下单数量以 10^-位数 个标的资源为最小单位（一「手」）
 * @param marginTiers  按名义价值分档的维持保证金与杠杆上限；传 null 时按最高杠杆取默认分档（{@link MarginTiers#standard}），
 *                     第一档的最高杠杆必须等于 {@code maxLeverage}
 */
public record ContractSpec(String symbol, String underlying, double initialPrice, double baseVolume,
        VolatilityProfile volatility, double spread, int maxLeverage, int quantityDecimals, MarginTiers marginTiers) {
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*CRP");
    /** 没有配置点差时，点差取起始价格的这个比例。 */
    private static final double DEFAULT_SPREAD_RATIO = 0.0005;
    /** 没有配置时的最高杠杆与数量精度。 */
    public static final int DEFAULT_MAX_LEVERAGE = 20;
    public static final int DEFAULT_QUANTITY_DECIMALS = 3;
    private static final int MAX_QUANTITY_DECIMALS = 6;

    public ContractSpec {
        if (!SYMBOL_PATTERN.matcher(symbol).matches()) {
            throw new IllegalArgumentException("交易代码必须是大写字母、数字、下划线加 CRP 后缀（例如 DIAMONDCRP）：" + symbol);
        }
        if (underlying.isBlank()) {
            throw new IllegalArgumentException("合约 " + symbol + " 的标的资源不能为空");
        }
        if (!(initialPrice > 0) || !Double.isFinite(initialPrice)) {
            throw new IllegalArgumentException("合约 " + symbol + " 的起始价格必须是有限的正数：" + initialPrice);
        }
        if (!(baseVolume > 0) || !Double.isFinite(baseVolume)) {
            throw new IllegalArgumentException("合约 " + symbol + " 的基础成交量必须是有限的正数：" + baseVolume);
        }
        if (!(spread >= 0) || !Double.isFinite(spread) || spread >= initialPrice) {
            throw new IllegalArgumentException("合约 " + symbol + " 的点差必须是非负的有限数，且小于起始价格：" + spread);
        }
        if (maxLeverage < 1) {
            throw new IllegalArgumentException("合约 " + symbol + " 的最高杠杆至少为 1 倍：" + maxLeverage);
        }
        if (quantityDecimals < 0 || quantityDecimals > MAX_QUANTITY_DECIMALS) {
            throw new IllegalArgumentException(
                    "合约 " + symbol + " 的数量精度必须在 0 到 " + MAX_QUANTITY_DECIMALS + " 位之间：" + quantityDecimals);
        }
        if (marginTiers == null) {
            marginTiers = MarginTiers.standard(maxLeverage);
        } else if (marginTiers.maxLeverage() != maxLeverage) {
            throw new IllegalArgumentException("合约 " + symbol + " 的维持保证金分档第一档的最高杠杆（" + marginTiers.maxLeverage()
                    + "）必须等于最高杠杆 " + maxLeverage);
        }
        // 提前校验波动率倾向在模型里可以兑现，配置错了在加载时就报出来
        IndexModel.Calibration.of(symbol, initialPrice, volatility);
    }

    /** 维持保证金分档取默认值（按最高杠杆推出）。 */
    public ContractSpec(String symbol, String underlying, double initialPrice, double baseVolume,
            VolatilityProfile volatility, double spread, int maxLeverage, int quantityDecimals) {
        this(symbol, underlying, initialPrice, baseVolume, volatility, spread, maxLeverage, quantityDecimals, null);
    }

    /** 交易参数取默认值：点差为起始价格的 0.05%，最高 20 倍杠杆，数量精度 3 位。 */
    public ContractSpec(String symbol, String underlying, double initialPrice, double baseVolume,
            VolatilityProfile volatility) {
        this(symbol, underlying, initialPrice, baseVolume, volatility, defaultSpread(initialPrice),
                DEFAULT_MAX_LEVERAGE, DEFAULT_QUANTITY_DECIMALS);
    }

    /** 没有配置点差时某个起始价格对应的默认点差。 */
    public static double defaultSpread(double initialPrice) {
        return initialPrice * DEFAULT_SPREAD_RATIO;
    }

    /** 界面下单需要知道的交易参数。 */
    public TradingParams trading() {
        return new TradingParams(symbol, spread, maxLeverage, quantityDecimals, marginTiers);
    }

    /** {@code lots} 手对应多少个标的资源。 */
    public double quantityOf(long lots) {
        return lots / Math.pow(10, quantityDecimals);
    }

    /** 内置的两个合约：服务端没有配置文件时，据此生成默认配置。 */
    public static List<ContractSpec> defaults() {
        return List.of(
                new ContractSpec("DIAMONDCRP", "minecraft:diamond", 1000.0, 5.0,
                        new VolatilityProfile(0.03, 0.2, 0.03, 0.08, 0.0015), defaultSpread(1000.0), 125, 3),
                new ContractSpec("GUNPOWDERCRP", "minecraft:gunpowder", 100.0, 50.0,
                        new VolatilityProfile(0.10, 2.0, 0.05, 0.20, 0.003), defaultSpread(100.0), 50, 0));
    }
}
