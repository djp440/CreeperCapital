package io.github.djp440.creepercapital.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.Entry;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.EventType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 世界存档里玩家未送达的离线事件汇总，存为 {@code data/creepercapital/offline_summary.dat}。
 * 单独一个文件而不挂在交易账上：汇总只在玩家上线时读写，和交易账的状态互不依赖，旧存档没有这个文件就是空的。
 * 平铺成 {@code 玩家 × 类型 × 合约} 的条目列表，金额以最小单位的整数存储。
 */
public final class OfflineSummarySavedData extends SavedData {
    private static final Codec<EventType> EVENT_TYPE = Codec.STRING.xmap(EventType::valueOf, EventType::name);

    private record Owned(UUID player, Entry entry) {
    }

    private static final Codec<Owned> OWNED_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("player").forGetter(Owned::player),
            EVENT_TYPE.fieldOf("type").forGetter(o -> o.entry().type()),
            Codec.STRING.fieldOf("symbol").forGetter(o -> o.entry().symbol()),
            Codec.LONG.fieldOf("count").forGetter(o -> o.entry().count()),
            Codec.LONG.fieldOf("amount").forGetter(o -> o.entry().amount().units()),
            Codec.LONG.fieldOf("first_seconds").forGetter(o -> o.entry().firstSeconds()),
            Codec.LONG.fieldOf("last_seconds").forGetter(o -> o.entry().lastSeconds()))
            .apply(i, (player, type, symbol, count, amount, first, last) -> new Owned(player,
                    new Entry(type, symbol, count, Money.ofUnits(amount), first, last))));

    public static final Codec<OfflineSummarySavedData> CODEC = OWNED_CODEC.listOf().fieldOf("entries").codec().xmap(
            owned -> {
                Map<UUID, List<Entry>> state = new HashMap<>();
                for (Owned o : owned) {
                    state.computeIfAbsent(o.player(), p -> new ArrayList<>()).add(o.entry());
                }
                return new OfflineSummarySavedData(OfflineSummaries.restore(state));
            },
            data -> data.summaries.state().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .flatMap(e -> e.getValue().stream().map(entry -> new Owned(e.getKey(), entry)))
                    .toList());

    public static final SavedDataType<OfflineSummarySavedData> TYPE = new SavedDataType<>(
            CreeperCapital.id("offline_summary"), () -> new OfflineSummarySavedData(new OfflineSummaries()), CODEC);

    private final OfflineSummaries summaries;

    private OfflineSummarySavedData(OfflineSummaries summaries) {
        this.summaries = summaries;
    }

    /** 汇总；改动后调用方负责 {@link #setDirty()}。 */
    public OfflineSummaries summaries() {
        return summaries;
    }
}
