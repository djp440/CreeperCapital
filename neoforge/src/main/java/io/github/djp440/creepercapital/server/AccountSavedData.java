package io.github.djp440.creepercapital.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 世界存档里的合约账户：每位玩家的 CRP 余额（以最小单位的整数存储，无浮点误差），
 * 存为 {@code data/creepercapital/accounts.dat}，玩家在不在线都在这里。
 */
public final class AccountSavedData extends SavedData {
    private record Entry(UUID player, long units) {
    }

    private static final Codec<Entry> ENTRY_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("player").forGetter(Entry::player),
            Codec.LONG.fieldOf("units").forGetter(Entry::units))
            .apply(i, Entry::new));

    public static final Codec<AccountSavedData> CODEC = ENTRY_CODEC.listOf().fieldOf("accounts").codec().xmap(
            entries -> {
                Map<UUID, Money> balances = new HashMap<>();
                for (Entry entry : entries) {
                    balances.put(entry.player(), Money.ofUnits(entry.units()));
                }
                return new AccountSavedData(AccountBook.restore(balances));
            },
            data -> data.book.balances().entrySet().stream()
                    .map(e -> new Entry(e.getKey(), e.getValue().units()))
                    .toList());

    public static final SavedDataType<AccountSavedData> TYPE = new SavedDataType<>(
            CreeperCapital.id("accounts"), () -> new AccountSavedData(new AccountBook()), CODEC);

    private final AccountBook book;

    private AccountSavedData(AccountBook book) {
        this.book = book;
    }

    /** 账本；改动后调用方负责 {@link #setDirty()}。 */
    public AccountBook book() {
        return book;
    }
}
