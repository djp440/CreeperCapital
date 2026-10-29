package io.github.djp440.creepercapital.server;

import java.nio.file.Files;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.network.AccountPayload;
import io.github.djp440.creepercapital.network.ModPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 服务端的合约账户：可用余额随世界存档保存（{@link AccountSavedData}），玩家离线也能充值、扣除。
 * 余额变动后立即把最新资产同步给在线的该玩家，登录时也同步一次。
 */
public final class ServerAccounts {
    private static @Nullable AccountSavedData data;

    private ServerAccounts() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerAccounts::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ServerAccounts::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerAccounts::onPlayerLoggedIn);
    }

    /** 玩家的可用余额（不含仓位占用的保证金）。 */
    public static Money balance(UUID player) {
        return data().book().balance(player);
    }

    /** 账本，交易账用它划转保证金与盈亏。 */
    static AccountBook book() {
        return data().book();
    }

    /** 此刻这名玩家的资产，含仓位的保证金与未实现盈亏。 */
    public static Assets assets(UUID player) {
        return ServerTrading.assets(player);
    }

    /**
     * 充值并同步给在线的该玩家。
     *
     * @return 充值后的余额
     * @throws IllegalArgumentException 数量不为正
     * @throws ArithmeticException      余额溢出，余额不变
     */
    public static Money deposit(MinecraftServer server, UUID player, Money amount) {
        Money updated = data().book().deposit(player, amount);
        changed(server, player);
        return updated;
    }

    /**
     * 扣除并同步给在线的该玩家。
     *
     * @return 已扣除为 true；余额不足时拒绝，余额不变，返回 false
     * @throws IllegalArgumentException 数量不为正
     */
    public static boolean withdraw(MinecraftServer server, UUID player, Money amount) {
        boolean done = data().book().withdraw(player, amount);
        if (done) {
            changed(server, player);
        }
        return done;
    }

    /**
     * 这名玩家是否是本世界已知的：在线、已有账户，或存档里有他的玩家数据（进过服）。
     * 开发服（离线模式）会把任何名字解析成一个离线 UUID，命令靠这个判断拦下拼错的名字。
     */
    public static boolean isKnownPlayer(MinecraftServer server, UUID player) {
        return server.getPlayerList().getPlayer(player) != null
                || data().book().hasAccount(player)
                || Files.isRegularFile(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat"));
    }

    private static void changed(MinecraftServer server, UUID player) {
        data().setDirty();
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            sendAssets(online);
        }
    }

    private static void sendAssets(ServerPlayer player) {
        ModPayloads.sendTo(player, new AccountPayload(assets(player.getUUID())));
    }

    private static AccountSavedData data() {
        if (data == null) {
            throw new IllegalStateException("服务器未运行，没有账户");
        }
        return data;
    }

    private static void onServerStarting(ServerStartingEvent event) {
        data = event.getServer().getDataStorage().computeIfAbsent(AccountSavedData.TYPE);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        data = null;
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (data != null && event.getEntity() instanceof ServerPlayer player) {
            sendAssets(player);
        }
    }
}
