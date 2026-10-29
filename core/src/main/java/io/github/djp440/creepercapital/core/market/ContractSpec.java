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
 */
public record ContractSpec(String symbol, String underlying, double initialPrice, double baseVolume,
        VolatilityProfile volatility) {
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*CRP");

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
        // 提前校验波动率倾向在模型里可以兑现，配置错了在加载时就报出来
        IndexModel.Calibration.of(symbol, initialPrice, volatility);
    }

    /** 内置的两个合约：服务端没有配置文件时，据此生成默认配置。 */
    public static List<ContractSpec> defaults() {
        return List.of(
                new ContractSpec("DIAMONDCRP", "minecraft:diamond", 1000.0, 5.0,
                        new VolatilityProfile(0.03, 0.2, 0.03, 0.08, 0.0015)),
                new ContractSpec("GUNPOWDERCRP", "minecraft:gunpowder", 100.0, 50.0,
                        new VolatilityProfile(0.10, 2.0, 0.05, 0.20, 0.003)));
    }
}
