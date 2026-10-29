package io.github.djp440.creepercapital.core.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 一个合约按名义价值分档的维持保证金与杠杆上限（照搬币安 USDⓈ-M 合约的分档结构）。
 * <p>
 * 每一档管名义价值区间 (上一档上界, 本档上界]：区间内的仓位适用本档的最高杠杆与维持保证金率；
 * 维持保证金 = 名义价值 × 维持保证金率 − 速算额，速算额由各档的费率差累计而来（与币安表里的 “Maintenance Amount” 同义），
 * 这样跨档时维持保证金连续、不会在档位边界跳变。最后一档的上界就是单个仓位的名义价值上限。
 * <p>
 * 这是维持保证金、保证金率和强平价（见 {@code MarginMath}）的唯一数据来源，下单、调杠杆、界面展示和强平都读它。
 */
public final class MarginTiers {
    /**
     * 默认分档的最高名义价值：流动性池初始 1,000,000 CRP 的 50%（#16 的持仓总量上限也是池子权益的一半，两者协调）。
     */
    public static final Money DEFAULT_MAX_NOTIONAL = Money.parse("500000");

    /** 默认分档的各档上界占最高名义价值的比例：1%、5%、20%、50%、100%。 */
    private static final double[] DEFAULT_BOUND_RATIOS = {0.01, 0.05, 0.20, 0.50, 1.0};
    /** 默认分档的各档杠杆上限占合约最高杠杆的比例；125x 时恰好是币安 BTC 的 125/100/50/20/10。 */
    private static final double[] DEFAULT_LEVERAGE_RATIOS = {1.0, 0.8, 0.4, 0.16, 0.08};

    /**
     * 分档的定义（配置文件与网络传输用）：速算额由 {@link MarginTiers} 自己推出。
     *
     * @param upTo            本档名义价值上界（含），CRP
     * @param maxLeverage     本档最高杠杆
     * @param maintenanceRate 本档维持保证金率（0 到 1 之间）
     */
    public record Definition(Money upTo, int maxLeverage, double maintenanceRate) {
    }

    /**
     * 推出速算额之后的一档。
     *
     * @param lower  本档下界（不含），CRP
     * @param upTo   本档上界（含），CRP
     * @param amount 速算额（CRP，精确值，不取整）
     */
    public record Tier(Money lower, Money upTo, int maxLeverage, double maintenanceRate, BigDecimal amount) {
    }

    private final List<Definition> definitions;
    private final List<Tier> tiers;

    private MarginTiers(List<Definition> definitions, List<Tier> tiers) {
        this.definitions = definitions;
        this.tiers = tiers;
    }

    /**
     * 由各档定义建立分档。
     *
     * @throws IllegalArgumentException 空、上界不递增、杠杆上升、费率下降，或维持保证金率不低于初始保证金率（1 / 杠杆）
     */
    public static MarginTiers of(List<Definition> definitions) {
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("维持保证金分档至少要有一档");
        }
        List<Tier> tiers = new ArrayList<>();
        Money lower = Money.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        Definition previous = null;
        for (int i = 0; i < definitions.size(); i++) {
            Definition definition = definitions.get(i);
            String where = "第 " + (i + 1) + " 档";
            if (definition.upTo().compareTo(lower) <= 0) {
                throw new IllegalArgumentException(where + "的名义价值上界必须大于上一档：" + definition.upTo().toDisplayString());
            }
            if (definition.maxLeverage() < 1) {
                throw new IllegalArgumentException(where + "的最高杠杆至少为 1 倍：" + definition.maxLeverage());
            }
            double rate = definition.maintenanceRate();
            if (!(rate > 0) || !(rate < 1)) {
                throw new IllegalArgumentException(where + "的维持保证金率必须在 0 到 1 之间（不含）：" + rate);
            }
            if (rate * definition.maxLeverage() >= 1) {
                throw new IllegalArgumentException(
                        where + "的维持保证金率 " + rate + " 不低于初始保证金率 1/" + definition.maxLeverage() + "，开仓即会被强平");
            }
            if (previous != null) {
                if (definition.maxLeverage() > previous.maxLeverage()) {
                    throw new IllegalArgumentException(where + "的最高杠杆不能高于上一档：" + definition.maxLeverage());
                }
                if (rate < previous.maintenanceRate()) {
                    throw new IllegalArgumentException(where + "的维持保证金率不能低于上一档：" + rate);
                }
                // 速算额累计：下界 × (本档费率 − 上一档费率)，使维持保证金在档位边界连续
                amount = amount.add(new BigDecimal(lower.units()).movePointLeft(Money.SCALE)
                        .multiply(BigDecimal.valueOf(rate).subtract(BigDecimal.valueOf(previous.maintenanceRate()))));
            }
            tiers.add(new Tier(lower, definition.upTo(), definition.maxLeverage(), rate, amount));
            lower = definition.upTo();
            previous = definition;
        }
        return new MarginTiers(List.copyOf(definitions), List.copyOf(tiers));
    }

    /**
     * 合约没有配置分档时的默认分档：五档，名义价值上界为 {@link #DEFAULT_MAX_NOTIONAL} 的 1%、5%、20%、50%、100%，
     * 各档最高杠杆是合约最高杠杆的 100%、80%、40%、16%、8%，维持保证金率取 1 / (2 × 本档杠杆)（币安各档杠杆与费率的对应关系）。
     */
    public static MarginTiers standard(int maxLeverage) {
        List<Definition> definitions = new ArrayList<>();
        for (int i = 0; i < DEFAULT_BOUND_RATIOS.length; i++) {
            Money upTo = Money.ofUnits(BigDecimal.valueOf(DEFAULT_MAX_NOTIONAL.units())
                    .multiply(BigDecimal.valueOf(DEFAULT_BOUND_RATIOS[i])).setScale(0, RoundingMode.HALF_UP).longValueExact());
            int leverage = (int) Math.max(1, Math.round(maxLeverage * DEFAULT_LEVERAGE_RATIOS[i]));
            double unit = Money.UNITS_PER_CRP;
            double rate = Math.round(0.5 / leverage * unit) / unit;
            definitions.add(new Definition(upTo, leverage, rate));
        }
        return of(definitions);
    }

    public List<Definition> definitions() {
        return definitions;
    }

    public List<Tier> tiers() {
        return tiers;
    }

    /** 最高一档的杠杆，也就是合约的最高杠杆。 */
    public int maxLeverage() {
        return tiers.get(0).maxLeverage();
    }

    /** 单个仓位的名义价值上限（最后一档的上界）。 */
    public Money maxNotional() {
        return tiers.get(tiers.size() - 1).upTo();
    }

    /** 名义价值所在的档位：区间是 (下界, 上界]，超过上限的归入最后一档。 */
    public Tier tierFor(Money notional) {
        for (Tier tier : tiers) {
            if (notional.compareTo(tier.upTo()) <= 0) {
                return tier;
            }
        }
        return tiers.get(tiers.size() - 1);
    }

    /** 这个名义价值的仓位允许使用的最高杠杆（所在档位的上限）。 */
    public int maxLeverageAt(Money notional) {
        return tierFor(notional).maxLeverage();
    }

    /** 使用这个杠杆时，仓位名义价值的上限：最高杠杆不低于它的最后一档的上界；杠杆超过合约最高杠杆时为 0。 */
    public Money maxNotionalAt(int leverage) {
        Money limit = Money.ZERO;
        for (Tier tier : tiers) {
            if (tier.maxLeverage() >= leverage) {
                limit = tier.upTo();
            }
        }
        return limit;
    }

    /** 名义价值对应的维持保证金 = 名义价值 × 所在档费率 − 速算额，取整到最小金额单位，不小于 0。 */
    public Money maintenanceMargin(Money notional) {
        if (!notional.isPositive()) {
            return Money.ZERO;
        }
        Tier tier = tierFor(notional);
        BigDecimal units = new BigDecimal(notional.units()).multiply(BigDecimal.valueOf(tier.maintenanceRate()))
                .subtract(tier.amount().movePointRight(Money.SCALE));
        Money margin = Money.ofUnits(units.setScale(0, RoundingMode.HALF_UP).longValueExact());
        return margin.isPositive() ? margin : Money.ZERO;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MarginTiers that && definitions.equals(that.definitions);
    }

    @Override
    public int hashCode() {
        return definitions.hashCode();
    }

    @Override
    public String toString() {
        return "MarginTiers" + definitions;
    }
}
