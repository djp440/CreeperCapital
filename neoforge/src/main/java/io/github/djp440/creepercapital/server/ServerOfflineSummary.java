package io.github.djp440.creepercapital.server;

import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.trading.AutoDeleverageEvent;
import io.github.djp440.creepercapital.core.trading.FundingRecord;
import io.github.djp440.creepercapital.core.trading.LiquidationEvent;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.EventType;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.Summary;
import io.github.djp440.creepercapital.core.trading.OrderEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 离线事件汇总：玩家不在线时发生的强平、资金费、委托成交和止盈止损触发记进汇总（{@link OfflineSummarySavedData}），
 * 玩家上线时在聊天栏发一条汇总通知并清空。在线玩家的事件已经实时通知过，不进汇总。
 * <p>
 * 事件的产生处（{@link ServerTrading} 的行情步各阶段）调用这里的 {@code onXxx}，由这里判断玩家此刻是否在线。
 * 加新的离线事件类型：在 {@link EventType} 加常量、在这里加一个 {@code onXxx} 并在产生处调用它（参照 {@link #onAutoDeleverage}）、
 * 两份语言文件加 {@code message.creepercapital.offline_summary.<类型名小写>}。
 */
public final class ServerOfflineSummary {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEY_PREFIX = "message.creepercapital.offline_summary.";
    /** 汇总消息标题的翻译键；客户端靠它认出这条聊天消息，在收到时响一声提示（不改协议）。 */
    public static final String HEADER_KEY = KEY_PREFIX + "header";

    private static @Nullable OfflineSummarySavedData data;

    private ServerOfflineSummary() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerOfflineSummary::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ServerOfflineSummary::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerOfflineSummary::onPlayerLoggedIn);
    }

    /** 汇总，只能在服务器运行时调用。 */
    public static OfflineSummaries summaries() {
        if (data == null) {
            throw new IllegalStateException("服务器未运行，没有离线汇总");
        }
        return data.summaries();
    }

    /** 委托成交 / 止盈止损触发（其它种类的委托事件不记）。 */
    public static void onOrderEvent(MinecraftServer server, OrderEvent event) {
        if (data == null || server.getPlayerList().getPlayer(event.player()) != null) {
            return;
        }
        int decimals = ServerMarket.symbols().contains(event.symbol())
                ? ServerMarket.spec(event.symbol()).quantityDecimals() : 0;
        if (data.summaries().record(event, decimals)) {
            data.setDirty();
        }
    }

    public static void onFunding(MinecraftServer server, FundingRecord record) {
        if (data != null && server.getPlayerList().getPlayer(record.player()) == null) {
            data.summaries().record(record);
            data.setDirty();
        }
    }

    public static void onLiquidation(MinecraftServer server, LiquidationEvent event) {
        if (data != null && server.getPlayerList().getPlayer(event.player()) == null) {
            data.summaries().record(event);
            data.setDirty();
        }
    }

    /** 自动减仓（#17）：金额记成交额，和成交历史里那行自动减仓对得上。 */
    public static void onAutoDeleverage(MinecraftServer server, AutoDeleverageEvent event) {
        if (data == null || server.getPlayerList().getPlayer(event.player()) != null) {
            return;
        }
        int decimals = ServerMarket.symbols().contains(event.symbol())
                ? ServerMarket.spec(event.symbol()).quantityDecimals() : 0;
        data.summaries().record(event, decimals);
        data.setDirty();
    }

    /** 汇总通知：标题、每类有事件的一行（次数与金额）、提示去哪看明细。一条聊天消息，行之间用换行连接。 */
    public static Component message(Summary summary) {
        MutableComponent message = Component.translatable(HEADER_KEY).withStyle(ChatFormatting.GOLD);
        for (EventType type : EventType.values()) {
            long count = summary.count(type);
            if (count > 0) {
                message.append("\n").append(Component.translatable(KEY_PREFIX + type.name().toLowerCase(Locale.ROOT),
                        String.valueOf(count), amountText(type, summary.amount(type))));
            }
        }
        return message.append("\n").append(Component.translatable(KEY_PREFIX + "footer").withStyle(ChatFormatting.GRAY));
    }

    /** 盈亏、资金费这类带符号的金额显示正负号，成交额（委托成交、自动减仓）不带。 */
    private static String amountText(EventType type, Money amount) {
        boolean signed = type != EventType.ORDER_FILL && type != EventType.AUTO_DELEVERAGE;
        return (signed && amount.isPositive() ? "+" : "") + amount.toDisplayString();
    }

    /** 玩家上线：有未送达的汇总就发一条并清空，没有就什么都不发。 */
    static void deliver(ServerPlayer player) {
        if (data == null || !data.summaries().hasPending(player.getUUID())) {
            return;
        }
        Summary summary = data.summaries().take(player.getUUID());
        data.setDirty();
        player.sendSystemMessage(message(summary));
        LOGGER.info("向 {} 送达离线事件汇总：{}", player.getGameProfile().name(), summary.entries());
    }

    private static void onServerStarting(ServerStartingEvent event) {
        data = event.getServer().getDataStorage().computeIfAbsent(OfflineSummarySavedData.TYPE);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        data = null;
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            deliver(player);
        }
    }
}
