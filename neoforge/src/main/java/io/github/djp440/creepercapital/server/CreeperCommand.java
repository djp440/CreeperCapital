package io.github.djp440.creepercapital.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import io.github.djp440.creepercapital.network.ModPayloads;
import io.github.djp440.creepercapital.network.OpenTradingScreenPayload;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /creeper}：为执行命令的玩家打开交易界面；{@code /creeper dev ...} 是仅限 OP 的开发命令
 * （暂停/恢复市场，以及把行情快进若干步，方便查看 K 线和做自动截图）。
 */
public final class CreeperCommand {
    /** 一次快进最多的行情步数，约 10 个市场日。 */
    private static final int MAX_ADVANCE_STEPS = 150_000;

    private CreeperCommand() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CreeperCommand::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("creeper")
                .executes(context -> {
                    ModPayloads.sendTo(context.getSource().getPlayerOrException(), OpenTradingScreenPayload.INSTANCE);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("dev")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("market")
                                .then(Commands.literal("pause").executes(context -> {
                                    ServerMarket.pause();
                                    context.getSource().sendSuccess(
                                            () -> Component.translatable("commands.creepercapital.market.paused"), true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(Commands.literal("resume").executes(context -> {
                                    ServerMarket.resume();
                                    context.getSource().sendSuccess(
                                            () -> Component.translatable("commands.creepercapital.market.resumed"), true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                                .then(Commands.literal("advance")
                                        .then(Commands.argument("steps", IntegerArgumentType.integer(1, MAX_ADVANCE_STEPS))
                                                .executes(context -> {
                                                    int steps = ServerMarket.advance(context.getSource().getServer(),
                                                            IntegerArgumentType.getInteger(context, "steps"));
                                                    context.getSource().sendSuccess(() -> Component.translatable(
                                                            "commands.creepercapital.market.advanced", steps), true);
                                                    return Command.SINGLE_SUCCESS;
                                                }))))));
    }
}
