package io.github.djp440.creepercapital.core.trading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.djp440.creepercapital.core.account.Money;

/**
 * 某一刻的 ADL 排名（#17）：全服所有<b>盈利</b>仓位按「盈利率 × 杠杆」从高到低排列，名次靠前的先被自动减仓。
 * 自动减仓的执行顺序和仓位页的五档指示灯都读这同一份排名，所以两者一致。
 * <p>
 * 全服共用一个流动性池，所以排名跨合约、跨方向，不是每个合约多空各一份。
 * 亏损（或持平）的仓位不会被自动减仓，不在排名里，指示灯显示最低的 1 格。
 */
public final class AdlRanking {
    /** 指示灯的格数。 */
    public static final int LIGHTS = 5;

    /**
     * 排名里的一个盈利仓位。
     *
     * @param score  得分 = 盈利率（浮盈 / 保证金）× 杠杆；越高越先被减
     * @param rank   名次，从 1 起
     * @param lights 指示灯亮几格（1 到 5），名次越靠前越多
     */
    public record Entry(UUID player, String symbol, Side side, long lots, Money pnl, double score, int rank, int lights) {
    }

    /** 排名前的候选：还没有名次。 */
    record Candidate(UUID player, String symbol, Side side, long lots, Money pnl, double score) {
    }

    private final List<Entry> entries;
    private final Map<UUID, Map<String, Integer>> lights = new HashMap<>();

    private AdlRanking(List<Entry> entries) {
        this.entries = entries;
        for (Entry entry : entries) {
            lights.computeIfAbsent(entry.player(), p -> new HashMap<>()).put(entry.symbol(), entry.lights());
        }
    }

    /** 按得分从高到低排序（同分按浮盈从大到小，再按玩家与合约，保证结果确定），再分配名次与灯数。 */
    static AdlRanking of(List<Candidate> candidates) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Candidate::pnl, Comparator.reverseOrder())
                .thenComparing(Candidate::player).thenComparing(Candidate::symbol));
        List<Entry> ranked = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Candidate c = sorted.get(i);
            ranked.add(new Entry(c.player(), c.symbol(), c.side(), c.lots(), c.pnl(), c.score(), i + 1,
                    lightsFor(i + 1, sorted.size())));
        }
        return new AdlRanking(List.copyOf(ranked));
    }

    /** 自动减仓的先后顺序：名次从 1 起。 */
    public List<Entry> entries() {
        return entries;
    }

    /** 这个仓位的指示灯格数（1 到 5）；不在排名里（亏损、持平）的显示最低的 1 格。 */
    public int lights(UUID player, String symbol) {
        return lights.getOrDefault(player, Map.of()).getOrDefault(symbol, 1);
    }

    /** 这名玩家各合约仓位的灯数（只含盈利、在排名里的；其余按 {@link #lights(UUID, String)} 的约定是 1）。 */
    public Map<String, Integer> lightsOf(UUID player) {
        return Map.copyOf(lights.getOrDefault(player, Map.of()));
    }

    /**
     * 名次对应的灯数：盈利仓位按名次五等分，最靠前的五分之一亮 5 格，往后依次 4、3、2、1 格。
     * 例如 10 个盈利仓位，名次 1、2 亮 5 格，3、4 亮 4 格，……，9、10 亮 1 格；只有 1 个时它亮 5 格（它就是最先被减的那个）。
     */
    public static int lightsFor(int rank, int count) {
        if (rank < 1 || rank > count) {
            throw new IllegalArgumentException("名次 " + rank + " 不在 1 到 " + count + " 之间");
        }
        return LIGHTS - (rank - 1) * LIGHTS / count;
    }
}
