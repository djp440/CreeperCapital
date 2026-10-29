package io.github.djp440.creepercapital;

import io.github.djp440.creepercapital.dev.DevLogFile;
import io.github.djp440.creepercapital.gametest.MarketGameTests;
import io.github.djp440.creepercapital.network.ModPayloads;
import io.github.djp440.creepercapital.server.CreeperCommand;
import io.github.djp440.creepercapital.server.ServerAccounts;
import io.github.djp440.creepercapital.server.ServerMarket;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * 苦力怕资本的双端入口：所有状态以服务端为准，客户端只渲染界面。
 */
@Mod(CreeperCapital.MOD_ID)
public final class CreeperCapital {
    public static final String MOD_ID = "creepercapital";

    public CreeperCapital(IEventBus modBus) {
        DevLogFile.installIfConfigured();
        ModPayloads.register(modBus);
        ServerMarket.register();
        ServerAccounts.register();
        CreeperCommand.register();
        MarketGameTests.register(modBus);
    }

    /** 本模组命名空间下的标识符。 */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
