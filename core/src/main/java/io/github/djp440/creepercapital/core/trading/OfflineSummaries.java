package io.github.djp440.creepercapital.core.trading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 玩家离线期间发生的事件汇总：强平、资金费、委托成交、止盈止损触发，上线时合成一条通知，之后清空。
 * 只存聚合（按事件类型与合约累加次数和金额），明细在各自的历史页里（成交历史、资金费记录、历史委托），所以汇总金额
 * 必须等于对应明细的合计。「玩家当时是否离线」由调用方（服务端）判断，这里只管记录与取走。
 * <p>
 * 加新的离线事件类型（#17 的自动减仓是这样接的）：在 {@link EventType} 末尾加一个常量，在事件产生处、玩家离线时调
 * {@link #record}；通知文案按常量名取语言键，不用改汇总本身。
 */
public final class OfflineSummaries {
    /**
     * 离线汇总里的事件类型。常量按序号排序展示，新类型追加在末尾；每种类型的金额含义见常量说明。
     */
    public enum EventType {
        /** 强平：每个被平仓位记一次，金额是该仓位强平的已实现盈亏（= 成交历史里强平那一行）。 */
        LIQUIDATION,
        /** 资金费：每次结算记一次，金额是玩家实际收付的带符号金额（正为收到，= 资金费记录里的金额）。 */
        FUNDING,
        /** 限价委托成交：每张成交的委托记一次，金额是成交额（成交价 × 数量，= 历史委托里的委托价与数量之积）。 */
        ORDER_FILL,
        /** 止盈止损触发并成交：每张记一次，金额是已实现盈亏（= 成交历史里平仓那一行）。 */
        TRIGGER,
        /**
         * 自动减仓（#17）：每次缩减记一次，金额是成交额（开仓价 × 缩减数量，= 成交历史里那行自动减仓的价格与数量之积）。
         * 不记已实现盈亏：自动减仓按开仓价成交，已实现盈亏恒为 0，被放弃的浮盈在通知之外看不出来，所以用成交额对账。
         */
        AUTO_DELEVERAGE
    }

    /**
     * 某类事件在某个合约上的累计。
     *
     * @param count        次数
     * @param amount       金额合计，含义见 {@link EventType}
     * @param firstSeconds 最早一次发生的市场时间（市场秒）
     * @param lastSeconds  最近一次发生的市场时间（市场秒）
     */
    public record Entry(EventType type, String symbol, long count, Money amount, long firstSeconds, long lastSeconds) {
        Entry add(Money more, long marketSeconds) {
            return new Entry(type, symbol, Math.addExact(count, 1), amount.plus(more), Math.min(firstSeconds, marketSeconds),
                    Math.max(lastSeconds, marketSeconds));
        }
    }

    /** 一名玩家未送达的汇总，条目按类型序号、合约代码排序。 */
    public record Summary(List<Entry> entries) {
        public static final Summary EMPTY = new Summary(List.of());

        public Summary {
            entries = List.copyOf(entries);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /** 这一类事件的次数（所有合约合计）。 */
        public long count(EventType type) {
            return entries.stream().filter(e -> e.type() == type).mapToLong(Entry::count).sum();
        }

        /** 这一类事件的金额合计（所有合约合计）。 */
        public Money amount(EventType type) {
            Money total = Money.ZERO;
            for (Entry entry : entries) {
                if (entry.type() == type) {
                    total = total.plus(entry.amount());
                }
            }
            return total;
        }
    }

    private static final Comparator<Entry> ORDER =
            Comparator.comparing(Entry::type).thenComparing(Entry::symbol);

    private final Map<UUID, List<Entry>> pending = new HashMap<>();

    /** 从存档状态恢复。 */
    public static OfflineSummaries restore(Map<UUID, List<Entry>> state) {
        OfflineSummaries summaries = new OfflineSummaries();
        state.forEach((player, entries) -> {
            if (!entries.isEmpty()) {
                List<Entry> sorted = new ArrayList<>(entries);
                sorted.sort(ORDER);
                summaries.pending.put(player, sorted);
            }
        });
        return summaries;
    }

    /** 存档状态：只含有未送达条目的玩家。 */
    public Map<UUID, List<Entry>> state() {
        Map<UUID, List<Entry>> copy = new HashMap<>();
        pending.forEach((player, entries) -> copy.put(player, List.copyOf(entries)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 记一次离线事件，累加进该玩家这一类事件在这个合约上的条目。
     *
     * @throws ArithmeticException 次数或金额溢出，汇总不变
     */
    public void record(UUID player, EventType type, String symbol, Money amount, long marketSeconds) {
        List<Entry> entries = pending.get(player);
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                if (entry.type() == type && entry.symbol().equals(symbol)) {
                    entries.set(i, entry.add(amount, marketSeconds)); // 溢出会抛出，此时还没改动任何东西
                    return;
                }
            }
        } else {
            entries = new ArrayList<>();
            pending.put(player, entries);
        }
        entries.add(new Entry(type, symbol, 1, amount, marketSeconds, marketSeconds));
        entries.sort(ORDER);
    }

    /** 记一次强平：被平的每个仓位各一次，金额是该仓位的已实现盈亏。 */
    public void record(LiquidationEvent event) {
        for (LiquidationEvent.Liquidated closed : event.positions()) {
            record(event.player(), EventType.LIQUIDATION, closed.symbol(), closed.realizedPnl(), event.marketSeconds());
        }
    }

    /** 记一次自动减仓，金额是成交额（开仓价 × 缩减数量）。 */
    public void record(AutoDeleverageEvent event, int quantityDecimals) {
        record(event.player(), EventType.AUTO_DELEVERAGE, event.symbol(),
                TradingBook.notional(event.price(), event.lots(), quantityDecimals), event.marketSeconds());
    }

    /** 记一次资金费结算，金额是玩家实际收付的带符号金额。 */
    public void record(FundingRecord funding) {
        record(funding.player(), EventType.FUNDING, funding.symbol(), funding.amount(), funding.marketSeconds());
    }

    /**
     * 记一次委托事件：限价委托成交记为 {@link EventType#ORDER_FILL}，止盈止损成交记为 {@link EventType#TRIGGER}；
     * 触发（紧接着就是成交）与撤销不记。
     *
     * @param quantityDecimals 这个合约的数量精度（算成交额用）
     * @return 是否记了
     */
    public boolean record(OrderEvent event, int quantityDecimals) {
        if (event.kind() != OrderEvent.Kind.FILLED) {
            return false;
        }
        switch (event.type()) {
            case LIMIT -> record(event.player(), EventType.ORDER_FILL, event.symbol(),
                    TradingBook.notional(event.price(), event.lots(), quantityDecimals), event.marketSeconds());
            case TAKE_PROFIT, STOP_LOSS -> record(event.player(), EventType.TRIGGER, event.symbol(),
                    event.realizedPnl(), event.marketSeconds());
            // 新增委托类型时必须在这里决定它怎么汇总，不能被悄悄吞掉
            default -> throw new IllegalStateException("离线汇总未处理的委托类型：" + event.type());
        }
        return true;
    }

    /** 这名玩家未送达的汇总（不清空）。 */
    public Summary pending(UUID player) {
        return new Summary(pending.getOrDefault(player, List.of()));
    }

    public boolean hasPending(UUID player) {
        return pending.containsKey(player);
    }

    /** 取走这名玩家的汇总并清空，送达后不会再次通知。 */
    public Summary take(UUID player) {
        List<Entry> entries = pending.remove(player);
        return entries == null ? Summary.EMPTY : new Summary(entries);
    }
}
