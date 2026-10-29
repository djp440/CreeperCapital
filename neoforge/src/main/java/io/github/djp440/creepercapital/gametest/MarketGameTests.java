package io.github.djp440.creepercapital.gametest;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.network.MarketSnapshotPayload;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 行情同步的 GameTest。测试函数随模组注册；测试实例只在 NeoForge 启用 GameTest 时注册
 * （开发环境与 GameTestServer，见 {@code GameTestHooks.isGametestEnabled}），生产环境不会出现。
 */
public final class MarketGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, CreeperCapital.MOD_ID);
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");
    /** 单个测试最多运行的 tick 数，超时判失败；最长的测试等待 45 tick。 */
    private static final int MAX_TICKS = 100;
    private static final int SETUP_TICKS = 0;
    private static final boolean REQUIRED = true;

    private MarketGameTests() {
    }

    public static void register(IEventBus modBus) {
        function("two_players_receive_same_index_price_in_same_step", MarketGameTests::twoPlayersReceiveSameIndexPriceInSameStep);
        function("market_step_advances_every_twenty_ticks", MarketGameTests::marketStepAdvancesEveryTwentyTicks);
        function("market_snapshot_payload_round_trips", MarketGameTests::marketSnapshotPayloadRoundTrips);
        FUNCTIONS.register(modBus);
        modBus.addListener(MarketGameTests::registerTests);
    }

    private static void function(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> test);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(CreeperCapital.id("default"));
        for (var entry : FUNCTIONS.getEntries()) {
            ResourceKey<Consumer<GameTestHelper>> key = entry.getKey();
            TestData<Holder<TestEnvironmentDefinition<?>>> data =
                    new TestData<>(environment, EMPTY_STRUCTURE, MAX_TICKS, SETUP_TICKS, REQUIRED);
            event.registerTest(key.identifier(), new FunctionGameTestInstance(key, data));
        }
    }

    /** 同一个行情步里，两名玩家收到的指数价格相同：全服共享同一个市场。 */
    private static void twoPlayersReceiveSameIndexPriceInSameStep(GameTestHelper helper) {
        ServerPlayer alice = MockClient.join(helper, "alice");
        ServerPlayer bob = MockClient.join(helper, "bob");
        helper.runAfterDelay(45, () -> {
            try {
                Map<Long, Double> aliceReceived = indexPricesByStep(alice);
                Map<Long, Double> bobReceived = indexPricesByStep(bob);
                Set<Long> commonSteps = new TreeSet<>(aliceReceived.keySet());
                commonSteps.retainAll(bobReceived.keySet());

                helper.assertTrue(commonSteps.size() >= 2,
                        "两名玩家应至少共同收到 2 个行情步，实际 alice=" + aliceReceived.keySet() + " bob=" + bobReceived.keySet());
                for (long step : commonSteps) {
                    helper.assertValueEqual(bobReceived.get(step), aliceReceived.get(step), "第 " + step + " 个行情步的指数价格");
                }
                helper.succeed();
            } finally {
                MockClient.leave(alice);
                MockClient.leave(bob);
            }
        });
    }

    /** 行情只由服务器 tick 推进：40 个 tick 恰好推进 2 个行情步。 */
    private static void marketStepAdvancesEveryTwentyTicks(GameTestHelper helper) {
        long startStep = ServerMarket.snapshot().step();
        helper.runAfterDelay(40, () -> {
            helper.assertValueEqual(ServerMarket.snapshot().step(), startStep + 2, "40 个 tick 后的行情步");
            helper.succeed();
        });
    }

    /** 行情快照载荷经编码、解码后保持不变。 */
    private static void marketSnapshotPayloadRoundTrips(GameTestHelper helper) {
        MarketSnapshotPayload payload = new MarketSnapshotPayload(new MarketSnapshot(7L, "DIAMONDCRP", 1012.345678));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());

        MarketSnapshotPayload.STREAM_CODEC.encode(buffer, payload);

        helper.assertValueEqual(MarketSnapshotPayload.STREAM_CODEC.decode(buffer), payload, "解码后的行情快照载荷");
        helper.succeed();
    }

    private static Map<Long, Double> indexPricesByStep(ServerPlayer player) {
        Map<Long, Double> prices = new TreeMap<>();
        for (MarketSnapshotPayload payload : MockClient.received(player, MarketSnapshotPayload.class)) {
            prices.put(payload.snapshot().step(), payload.snapshot().indexPrice());
        }
        return prices;
    }
}
