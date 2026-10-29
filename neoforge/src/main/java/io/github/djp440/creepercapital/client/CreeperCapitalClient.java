package io.github.djp440.creepercapital.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.network.AccountPayload;
import io.github.djp440.creepercapital.network.AdlLightsPayload;
import io.github.djp440.creepercapital.network.KlineHistoryPayload;
import io.github.djp440.creepercapital.network.LatestTradesPayload;
import io.github.djp440.creepercapital.network.MarketSnapshotPayload;
import io.github.djp440.creepercapital.network.OpenTradingScreenPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.network.TradingStatePayload;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端入口：快捷键、载荷处理器与交易界面。专用服务端不会加载这个类。
 */
@Mod(value = CreeperCapital.MOD_ID, dist = Dist.CLIENT)
public final class CreeperCapitalClient {
    private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(CreeperCapital.id("main"));

    /** 打开交易界面，默认 K，可在按键设置里改绑。 */
    static final KeyMapping OPEN_TRADING_KEY = new KeyMapping(
            "key.creepercapital.open_trading", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KEY_CATEGORY);

    public CreeperCapitalClient(IEventBus modBus) {
        modBus.addListener(CreeperCapitalClient::onRegisterKeyMappings);
        modBus.addListener(CreeperCapitalClient::onRegisterPayloadHandlers);
        NeoForge.EVENT_BUS.addListener(CreeperCapitalClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(CreeperCapitalClient::onLoggingOut);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(OPEN_TRADING_KEY);
    }

    private static void onRegisterPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(MarketSnapshotPayload.TYPE, (payload, context) -> ClientMarket.accept(payload));
        event.register(KlineHistoryPayload.TYPE, (payload, context) -> ClientKlines.accept(payload));
        event.register(AccountPayload.TYPE, (payload, context) -> ClientAccount.accept(payload));
        event.register(TradingStatePayload.TYPE, (payload, context) -> ClientTrading.accept(payload));
        event.register(OrderResultPayload.TYPE, (payload, context) -> ClientTrading.accept(payload));
        event.register(LatestTradesPayload.TYPE, (payload, context) -> ClientLatestTrades.accept(payload));
        event.register(AdlLightsPayload.TYPE, (payload, context) -> ClientTrading.accept(payload));
        event.register(OpenTradingScreenPayload.TYPE, (payload, context) -> TradingScreen.open());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_TRADING_KEY.consumeClick()) {
            TradingScreen.open();
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientMarket.clear();
        ClientAccount.clear();
        ClientTrading.clear();
        ClientLatestTrades.clear();
    }
}
