package io.github.djp440.creepercapital.core.trading;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarginTiers;

/**
 * 维持保证金、保证金率和预估强平价的唯一实现（USDT 本位永续合约单向持仓的口径）：仓位页展示、调整杠杆的安全检查和强平触发（#12）
 * 都调用这里，不得各写一份公式。服务端和客户端用同一份代码，所以界面显示的数字就是服务端判断强平用的数字。
 * <p>
 * 保证金率 = 维持保证金 / 保证金余额，达到 100%（保证金余额不足以覆盖维持保证金）即触发强平。
 * <ul>
 * <li>逐仓：保证金余额 = 该仓位的保证金 + 该仓位的未实现盈亏，只看自己。</li>
 * <li>全仓：保证金余额 = 全仓钱包余额 + 全部全仓仓位的未实现盈亏，维持保证金是全部全仓仓位之和，所有全仓仓位共用一个保证金率。
 * 全仓钱包余额 = 钱包余额 − 逐仓仓位占用的保证金。</li>
 * </ul>
 * 强平价（价格变动只发生在本仓位的合约上，其他全仓仓位按当前标记价格不变）：
 * <pre>
 * 强平价 = (WB − TMM1 + UPNL1 + cum − s × q × EP) / (q × MMR − s × q)
 * </pre>
 * WB 是钱包余额（逐仓时是该仓位的保证金），TMM1、UPNL1 是其他全仓仓位的维持保证金之和与未实现盈亏之和（逐仓时为 0），
 * cum 是强平价所在档位的速算额，MMR 是该档费率，s 是方向（多 +1、空 −1），q 是数量（标的个数），EP 是开仓均价。
 */
public final class MarginMath {
    /** 一个仓位在某一时刻（按标记价格）的风险指标。 */
    public record PositionRisk(
            /** 按标记价格算的名义价值。 */
            Money notional,
            /** 维持保证金。 */
            Money maintenanceMargin,
            /** 保证金余额：逐仓是该仓位的，全仓是整个全仓账户的。 */
            Money marginBalance,
            /** 保证金率；保证金余额不为正时是正无穷。达到 1.0（100%）即强平。 */
            double marginRatio,
            /** 预估强平价；仓位没有强平价（保证金足以覆盖价格归零）时为空。 */
            OptionalDouble liquidationPrice) {
    }

    private MarginMath() {
    }

    /** 标记价格可用：有限的正数。合约已不在配置里时标记价格是 NaN，那样的仓位按开仓均价算、不参与强平与 ADL。 */
    public static boolean hasMark(double mark) {
        return Double.isFinite(mark) && mark > 0;
    }

    /** 保证金率 = 维持保证金 / 保证金余额；保证金余额不为正时视为正无穷（已达强平线）。 */
    public static double marginRatio(Money maintenanceMargin, Money marginBalance) {
        if (!marginBalance.isPositive()) {
            return Double.POSITIVE_INFINITY;
        }
        return (double) maintenanceMargin.units() / marginBalance.units();
    }

    /** 保证金率的显示文本：百分比，两位小数；无穷大显示为 {@code ∞}。 */
    public static String formatRatio(double ratio) {
        return Double.isInfinite(ratio) ? "∞" : String.format(java.util.Locale.ROOT, "%.2f%%", ratio * 100);
    }

    /**
     * 评估一名玩家全部仓位的风险。
     *
     * @param walletBalance 玩家的钱包余额（可用余额 + 全部仓位的保证金，不含未实现盈亏），即 {@code Assets.walletBalance}
     * @param markPrice     各合约当前的标记价格；返回 NaN（合约已不在配置里）时该仓位按开仓均价算，盈亏为 0
     * @param tiers         各合约的维持保证金分档
     * @return 以交易代码为键（单向持仓，每个合约最多一个仓位）
     */
    public static Map<String, PositionRisk> assess(List<Position> positions, Money walletBalance,
            ToDoubleFunction<String> markPrice, Function<String, MarginTiers> tiers) {
        Money isolatedMargins = Money.ZERO;
        Money crossMaintenance = Money.ZERO;
        Money crossUnrealized = Money.ZERO;
        Map<String, Money> notionals = new HashMap<>();
        Map<String, Money> maintenances = new HashMap<>();
        Map<String, Money> pnls = new HashMap<>();
        for (Position position : positions) {
            double mark = markPrice.applyAsDouble(position.symbol());
            double price = hasMark(mark) ? mark : position.entryPrice();
            Money notional = TradingBook.notional(price, position.lots(), position.quantityDecimals());
            Money maintenance = tiers.apply(position.symbol()).maintenanceMargin(notional);
            Money pnl = position.pnlAt(price);
            notionals.put(position.symbol(), notional);
            maintenances.put(position.symbol(), maintenance);
            pnls.put(position.symbol(), pnl);
            if (position.mode() == MarginMode.CROSS) {
                crossMaintenance = crossMaintenance.plus(maintenance);
                crossUnrealized = crossUnrealized.plus(pnl);
            } else {
                isolatedMargins = isolatedMargins.plus(position.margin());
            }
        }
        Money crossWallet = walletBalance.minus(isolatedMargins);
        Money crossBalance = crossWallet.plus(crossUnrealized);

        Map<String, PositionRisk> risks = new HashMap<>();
        for (Position position : positions) {
            String symbol = position.symbol();
            Money maintenance = maintenances.get(symbol);
            boolean cross = position.mode() == MarginMode.CROSS;
            Money balance = cross ? crossBalance : position.margin().plus(pnls.get(symbol));
            Money wallet = cross ? crossWallet : position.margin();
            Money otherMaintenance = cross ? crossMaintenance.minus(maintenance) : Money.ZERO;
            Money otherUnrealized = cross ? crossUnrealized.minus(pnls.get(symbol)) : Money.ZERO;
            double quantity = ContractSpec.quantityOf(position.lots(), position.quantityDecimals());
            OptionalDouble liquidation = liquidationPrice(position.side(), quantity, position.entryPrice(),
                    crp(wallet), crp(otherMaintenance), crp(otherUnrealized), tiers.apply(symbol));
            risks.put(symbol, new PositionRisk(notionals.get(symbol), maintenance, balance,
                    marginRatio(cross ? crossMaintenance : maintenance, balance), liquidation));
        }
        return risks;
    }

    /**
     * 预估强平价：在价格所在的档位上求解，直到解出的价格对应的名义价值确实落在该档内。
     * 保证金余额随价格线性变化、维持保证金随价格分段线性且连续，所以解唯一。
     *
     * @param quantity         仓位数量（标的个数）
     * @param walletBalance    钱包余额（CRP）；逐仓时传该仓位的保证金
     * @param otherMaintenance 其他全仓仓位的维持保证金之和（CRP）；逐仓传 0
     * @param otherUnrealized  其他全仓仓位的未实现盈亏之和（CRP）；逐仓传 0
     * @return 强平价；仓位在价格跌到 0（多头）之前都不会被强平时为空
     */
    public static OptionalDouble liquidationPrice(Side side, double quantity, double entryPrice, double walletBalance,
            double otherMaintenance, double otherUnrealized, MarginTiers tiers) {
        double sign = side.sign();
        double base = walletBalance - otherMaintenance + otherUnrealized - sign * quantity * entryPrice;
        List<MarginTiers.Tier> list = tiers.tiers();
        double fallback = Double.NaN;
        for (int i = 0; i < list.size(); i++) {
            MarginTiers.Tier tier = list.get(i);
            double price = (base + tier.amount().doubleValue()) / (quantity * tier.maintenanceRate() - sign * quantity);
            if (!(price > 0) || !Double.isFinite(price)) {
                // 多头价格解为负：这一档（及其下面更高的档，费率更高）都不会被打到
                continue;
            }
            double notional = quantity * price;
            boolean last = i == list.size() - 1;
            if (notional > crp(tier.lower()) && (notional <= crp(tier.upTo()) || last)) {
                return OptionalDouble.of(price);
            }
            fallback = price;
        }
        // 解出的价格在所有档位的区间之外（价格低到名义价值落在第一档下方不会发生，因为下界是 0），保险起见返回最后一个解
        return Double.isNaN(fallback) ? OptionalDouble.empty() : OptionalDouble.of(fallback);
    }

    private static double crp(Money money) {
        return money.units() / (double) Money.UNITS_PER_CRP;
    }
}
