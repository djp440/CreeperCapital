package io.github.djp440.creepercapital.server;

import com.mojang.brigadier.Command;

import io.github.djp440.creepercapital.network.ModPayloads;
import io.github.djp440.creepercapital.network.OpenTradingScreenPayload;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /creeper}：为执行命令的玩家打开交易界面。
 */
public final class CreeperCommand {
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
                }));
    }
}
