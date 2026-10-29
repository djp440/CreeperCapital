package io.github.djp440.creepercapital.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.network.MarketSnapshotPayload;
import io.github.djp440.creepercapital.server.MarketSavedData;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 行情同步、市场持久化与 dev 命令的 GameTest。测试函数随模组注册；测试实例只在 NeoForge 启用 GameTest 时注册
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
        function("market_survives_save_and_reload_without_catch_up", helper -> {
            try {
                marketSurvivesSaveAndReloadWithoutCatchUp(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        function("dev_market_command_is_op_only", MarketGameTests::devMarketCommandIsOpOnly);
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

    /** 市场写进存档文件、重新读出后，市场时间和价格与存档时一致，之后紧接着继续推进，不补算。 */
    private static void marketSurvivesSaveAndReloadWithoutCatchUp(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        Market market = new Market(2026L);
        for (int tick = 0; tick < Market.TICKS_PER_STEP * 500 + 7; tick++) {
            market.tick();
        }
        MarketSnapshot saved = market.snapshot();
        Path dataFolder = Files.createTempDirectory("creepercapital-market");
        try {
            SavedDataStorage writer = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            writer.set(MarketSavedData.TYPE, new MarketSavedData(market));
            writer.saveAndJoin();
            writer.close();

            // 全新的存储实例，模拟停服后重新开服
            SavedDataStorage reader = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            MarketSavedData reloaded = reader.get(MarketSavedData.TYPE);
            helper.assertTrue(reloaded != null, "存档里应当读得出市场");
            Market restored = reloaded.market();
            helper.assertValueEqual(restored.snapshot(), saved, "重新加载后的市场快照");
            helper.assertValueEqual(restored.snapshot().time(), saved.time(), "重新加载后的市场时间");

            // 存档时已经攒了 7 个 tick：再过 13 个 tick 恰好是下一个行情步，且与没停过的市场一模一样
            MarketSnapshot expectedNext = null;
            MarketSnapshot actualNext = null;
            for (int tick = 0; tick < Market.TICKS_PER_STEP - 7; tick++) {
                expectedNext = market.tick().orElse(expectedNext);
                actualNext = restored.tick().orElse(actualNext);
            }
            helper.assertValueEqual(actualNext, expectedNext, "重启后的第一个行情步");
            helper.assertValueEqual(actualNext.step(), saved.step() + 1, "重启后的第一个行情步序号紧接存档时的最后一步");
            reader.close();
        } finally {
            deleteRecursively(dataFolder);
        }
        helper.succeed();
    }

    /** 只有 OP 能用 /creeper dev market；普通玩家用不了，市场状态不变。 */
    private static void devMarketCommandIsOpOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack op = server.createCommandSourceStack();
        CommandSourceStack regularPlayer = op.withPermission(PermissionSet.NO_PERMISSIONS);

        server.getCommands().performPrefixedCommand(regularPlayer, "creeper dev market pause");
        helper.assertFalse(ServerMarket.isPaused(), "普通玩家不应能暂停市场");

        try {
            server.getCommands().performPrefixedCommand(op, "creeper dev market pause");
            helper.assertTrue(ServerMarket.isPaused(), "OP 应能暂停市场");

            server.getCommands().performPrefixedCommand(regularPlayer, "creeper dev market resume");
            helper.assertTrue(ServerMarket.isPaused(), "普通玩家不应能恢复市场");

            server.getCommands().performPrefixedCommand(op, "creeper dev market resume");
            helper.assertFalse(ServerMarket.isPaused(), "OP 应能恢复市场");
        } finally {
            // 即使断言失败也不能让暂停泄漏给同时运行的其他测试
            ServerMarket.resume();
        }
        helper.succeed();
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }

    private static Map<Long, Double> indexPricesByStep(ServerPlayer player) {
        Map<Long, Double> prices = new TreeMap<>();
        for (MarketSnapshotPayload payload : MockClient.received(player, MarketSnapshotPayload.class)) {
            prices.put(payload.snapshot().step(), payload.snapshot().indexPrice());
        }
        return prices;
    }
}
