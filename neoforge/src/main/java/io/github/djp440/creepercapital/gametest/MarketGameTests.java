package io.github.djp440.creepercapital.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.ContractState;
import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.PricingState;
import io.github.djp440.creepercapital.network.KlineHistoryPayload;
import io.github.djp440.creepercapital.network.KlineRequestPayload;
import io.github.djp440.creepercapital.network.MarketSnapshotPayload;
import io.github.djp440.creepercapital.server.ContractConfig;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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
 * 行情同步、K 线、市场持久化、合约配置与 dev 命令的 GameTest。测试函数随模组注册；测试实例只在 NeoForge 启用 GameTest 时注册
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
    private static final String DIAMOND = "DIAMONDCRP";
    private static final String GUNPOWDER = "GUNPOWDERCRP";

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
        function("kline_payloads_round_trip", MarketGameTests::klinePayloadsRoundTrip);
        function("kline_history_follows_the_market", MarketGameTests::klineHistoryFollowsTheMarket);
        function("dev_market_command_is_op_only", MarketGameTests::devMarketCommandIsOpOnly);
        function("dev_market_jump_command_is_op_only", MarketGameTests::devMarketJumpCommandIsOpOnly);
        function("pre_pricing_save_loads_with_zero_premium", MarketGameTests::prePricingSaveLoadsWithZeroPremium);
        function("single_contract_save_loads_as_diamond", MarketGameTests::singleContractSaveLoadsAsDiamond);
        function("contracts_removed_from_config_stay_in_the_save", MarketGameTests::contractsRemovedFromConfigStayInTheSave);
        function("contract_config_is_generated_when_missing", helper -> {
            try {
                contractConfigIsGeneratedWhenMissing(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        function("contract_config_edit_changes_the_volatility_profile", helper -> {
            try {
                contractConfigEditChangesTheVolatilityProfile(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        function("invalid_contract_config_falls_back_without_overwriting", helper -> {
            try {
                invalidContractConfigFallsBackWithoutOverwriting(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
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

    /** 同一个行情步里，两名玩家收到的每个合约的指数价格相同：全服共享同一个市场；每个合约每步各收到一个包。 */
    private static void twoPlayersReceiveSameIndexPriceInSameStep(GameTestHelper helper) {
        ServerPlayer alice = MockClient.join(helper, "alice");
        ServerPlayer bob = MockClient.join(helper, "bob");
        helper.runAfterDelay(45, () -> {
            try {
                for (String symbol : ServerMarket.symbols()) {
                    Map<Long, Double> aliceReceived = indexPricesByStep(alice, symbol);
                    Map<Long, Double> bobReceived = indexPricesByStep(bob, symbol);
                    Set<Long> commonSteps = new TreeSet<>(aliceReceived.keySet());
                    commonSteps.retainAll(bobReceived.keySet());

                    helper.assertTrue(commonSteps.size() >= 2, symbol + "：两名玩家应至少共同收到 2 个行情步，实际 alice="
                            + aliceReceived.keySet() + " bob=" + bobReceived.keySet());
                    for (long step : commonSteps) {
                        helper.assertValueEqual(bobReceived.get(step), aliceReceived.get(step),
                                symbol + " 第 " + step + " 个行情步的指数价格");
                    }
                }
                helper.succeed();
            } finally {
                MockClient.leave(alice);
                MockClient.leave(bob);
            }
        });
    }

    /** 行情只由服务器 tick 推进：40 个 tick 恰好推进 2 个行情步，所有合约共用同一个行情步。 */
    private static void marketStepAdvancesEveryTwentyTicks(GameTestHelper helper) {
        String symbol = ServerMarket.symbols().get(0);
        long startStep = ServerMarket.snapshot(symbol).step();
        helper.runAfterDelay(40, () -> {
            helper.assertValueEqual(ServerMarket.snapshot(symbol).step(), startStep + 2, "40 个 tick 后的行情步");
            for (MarketSnapshot snapshot : ServerMarket.snapshots()) {
                helper.assertValueEqual(snapshot.step(), startStep + 2, snapshot.symbol() + " 与其他合约共用行情步");
            }
            helper.succeed();
        });
    }

    /** 行情快照载荷（两个合约）经编码、解码后保持不变。 */
    private static void marketSnapshotPayloadRoundTrips(GameTestHelper helper) {
        List<MarketSnapshotPayload> payloads = List.of(
                new MarketSnapshotPayload(new MarketSnapshot(7L, DIAMOND, 1012.345678, 1013.1, 1012.9, -0.00025, 6.25),
                        new Stats24h(1000.5, 1020.75, 995.25, 1013.1, 12345.5)),
                new MarketSnapshotPayload(new MarketSnapshot(7L, GUNPOWDER, 99.87654, 100.1, 100.0, 0.0003, 71.5),
                        new Stats24h(95.5, 104.75, 91.25, 100.1, 98765.5)));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());

        for (MarketSnapshotPayload payload : payloads) {
            MarketSnapshotPayload.STREAM_CODEC.encode(buffer, payload);
            helper.assertValueEqual(MarketSnapshotPayload.STREAM_CODEC.decode(buffer), payload,
                    payload.snapshot().symbol() + " 解码后的行情快照载荷");
        }
        helper.succeed();
    }

    /** K 线请求与历史载荷（带合约）经编码、解码后保持不变；未知的周期编号和超长的交易代码会被拒绝。 */
    private static void klinePayloadsRoundTrip(GameTestHelper helper) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        KlineRequestPayload request = new KlineRequestPayload(GUNPOWDER, CandlePeriod.H4);
        KlineRequestPayload.STREAM_CODEC.encode(buffer, request);
        helper.assertValueEqual(KlineRequestPayload.STREAM_CODEC.decode(buffer), request, "解码后的 K 线请求载荷");

        KlineHistoryPayload history = new KlineHistoryPayload(GUNPOWDER, CandlePeriod.M15, List.of(
                new Candle(900, 100.5, 103.25, 98.0, 101.75, 42.5),
                new Candle(1_800, 101.75, 101.75, 90.125, 91.0, 7.0)));
        KlineHistoryPayload.STREAM_CODEC.encode(buffer, history);
        helper.assertValueEqual(KlineHistoryPayload.STREAM_CODEC.decode(buffer), history, "解码后的 K 线历史载荷");

        RegistryFriendlyByteBuf bad = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        ByteBufCodecs.stringUtf8(1_000).encode(bad, DIAMOND);
        ByteBufCodecs.VAR_INT.encode(bad, CandlePeriod.values().length);
        boolean rejected = false;
        try {
            KlineRequestPayload.STREAM_CODEC.decode(bad);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        helper.assertTrue(rejected, "未知的周期编号应被拒绝");

        RegistryFriendlyByteBuf tooLong = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        ByteBufCodecs.stringUtf8(1_000).encode(tooLong, "X".repeat(65));
        ByteBufCodecs.VAR_INT.encode(tooLong, CandlePeriod.M1.ordinal());
        boolean tooLongRejected = false;
        try {
            KlineRequestPayload.STREAM_CODEC.decode(tooLong);
        } catch (RuntimeException e) {
            tooLongRejected = true;
        }
        helper.assertTrue(tooLongRejected, "超长的交易代码应被拒绝");
        helper.succeed();
    }

    /**
     * 服务端按请求的合约和周期返回历史 K 线，最后一根是当前 K 线，与此刻该合约的最新价格一致；
     * 之后随行情步更新，周期越大越是由 1m 聚合而成（边界对齐）。没有的合约不予响应。
     */
    private static void klineHistoryFollowsTheMarket(GameTestHelper helper) {
        ServerPlayer player = MockClient.join(helper, "carol");
        helper.runAfterDelay(45, () -> {
            try {
                for (String symbol : ServerMarket.symbols()) {
                    ServerMarket.sendKlines(player, symbol, CandlePeriod.M1);
                    ServerMarket.sendKlines(player, symbol, CandlePeriod.M15);
                }
                ServerMarket.sendKlines(player, "NOSUCHCRP", CandlePeriod.M1);
                List<KlineHistoryPayload> responses = MockClient.received(player, KlineHistoryPayload.class);
                helper.assertValueEqual(responses.size(), ServerMarket.symbols().size() * 2, "收到的 K 线历史响应数（没有的合约不响应）");

                int index = 0;
                for (String symbol : ServerMarket.symbols()) {
                    KlineHistoryPayload minutes = responses.get(index++);
                    KlineHistoryPayload quarters = responses.get(index++);
                    helper.assertValueEqual(minutes.symbol(), symbol, "响应属于哪个合约");
                    helper.assertValueEqual(quarters.symbol(), symbol, "响应属于哪个合约");
                    helper.assertValueEqual(minutes.period(), CandlePeriod.M1, symbol + " 第一个响应的周期");
                    helper.assertValueEqual(quarters.period(), CandlePeriod.M15, symbol + " 第二个响应的周期");

                    MarketSnapshot snapshot = ServerMarket.snapshot(symbol);
                    Candle currentMinute = minutes.candles().get(minutes.candles().size() - 1);
                    Candle currentQuarter = quarters.candles().get(quarters.candles().size() - 1);
                    helper.assertValueEqual(currentMinute.close(), snapshot.lastPrice(), symbol + " 当前 1m 的收盘价");
                    helper.assertValueEqual(currentMinute.start(), CandlePeriod.M1.startOf(snapshot.time().totalSeconds()),
                            symbol + " 当前 1m 的起点按市场时间对齐");
                    helper.assertValueEqual(currentQuarter.close(), currentMinute.close(), symbol + " 当前 15m 与 1m 同价收盘");
                    helper.assertValueEqual(currentQuarter.start() % CandlePeriod.M15.seconds(), 0L, "15m 的起点对齐 15 个市场分钟");
                    helper.assertTrue(currentQuarter.start() <= currentMinute.start(), "15m 包含当前这根 1m");
                }
                helper.succeed();
            } finally {
                MockClient.leave(player);
            }
        });
    }

    /** 市场（两个合约，带一次排队中的跳跃）写进存档文件、重新读出后，市场时间、价格和模型状态与存档时一致，之后紧接着继续推进，不补算。 */
    private static void marketSurvivesSaveAndReloadWithoutCatchUp(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        List<ContractSpec> specs = ContractSpec.defaults();
        Market market = new Market(2026L, specs);
        for (int tick = 0; tick < Market.TICKS_PER_STEP * 500 + 7; tick++) {
            market.tick();
        }
        market.queueJump(GUNPOWDER, 0.12);
        market.pause();
        List<MarketSnapshot> saved = market.snapshots();
        Path dataFolder = Files.createTempDirectory("creepercapital-market");
        try {
            SavedDataStorage writer = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            writer.set(MarketSavedData.TYPE, MarketSavedData.of(market));
            writer.saveAndJoin();
            writer.close();

            // 全新的存储实例，模拟停服后重新开服
            SavedDataStorage reader = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            MarketSavedData reloaded = reader.get(MarketSavedData.TYPE);
            helper.assertTrue(reloaded != null, "存档里应当读得出市场");
            Market restored = reloaded.bind(specs);
            helper.assertValueEqual(restored.snapshots(), saved, "重新加载后的市场快照");
            helper.assertValueEqual(restored.state(), market.state(), "重新加载后的市场状态（含行情模型、溢价与采样、排队的跳跃）");
            helper.assertValueEqual(restored.snapshot(DIAMOND).time(), saved.get(0).time(), "重新加载后的市场时间");
            helper.assertTrue(restored.isPaused(), "暂停状态应随存档保存");
            restored.resume();
            market.resume();

            // 存档时已经攒了 7 个 tick：再过 13 个 tick 恰好是下一个行情步，且与没停过的市场一模一样
            List<MarketSnapshot> expectedNext = List.of();
            List<MarketSnapshot> actualNext = List.of();
            for (int tick = 0; tick < Market.TICKS_PER_STEP - 7; tick++) {
                List<MarketSnapshot> expected = market.tick();
                List<MarketSnapshot> actual = restored.tick();
                expectedNext = expected.isEmpty() ? expectedNext : expected;
                actualNext = actual.isEmpty() ? actualNext : actual;
            }
            helper.assertValueEqual(actualNext, expectedNext, "重启后的第一个行情步（两个合约）");
            helper.assertValueEqual(actualNext.get(0).step(), saved.get(0).step() + 1, "重启后的第一个行情步序号紧接存档时的最后一步");
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

    /**
     * 只有 OP 能用 /creeper dev market jump：普通玩家排不进跳跃；OP 排进去的跳跃落在指定合约上（幅度按百分比），
     * 没有的合约、不合法的幅度被拒绝。同一个函数内不会有行情步插进来，所以可以直接读排队中的跳跃。
     */
    private static void devMarketJumpCommandIsOpOnly(GameTestHelper helper) throws RuntimeException {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack op = server.createCommandSourceStack();
        CommandSourceStack regularPlayer = op.withPermission(PermissionSet.NO_PERMISSIONS);
        String symbol = ServerMarket.symbols().get(0);
        double before = pendingJump(symbol);

        server.getCommands().performPrefixedCommand(regularPlayer, "creeper dev market jump " + symbol + " 7");
        helper.assertValueEqual(pendingJump(symbol), before, "普通玩家不应能让合约跳跃");

        server.getCommands().performPrefixedCommand(op, "creeper dev market jump " + symbol + " 7");
        helper.assertTrue(Math.abs(pendingJump(symbol) - before - Math.log1p(0.07)) < 1e-12,
                "OP 应能让合约跳跃 +7%，实际排队 " + (pendingJump(symbol) - before));

        // 排进去的是下一个行情步里发生的跳跃：别的合约不受影响
        for (String other : ServerMarket.symbols()) {
            if (!other.equals(symbol)) {
                helper.assertValueEqual(pendingJump(other), 0.0, other + " 不应被牵连");
            }
        }

        double afterOp = pendingJump(symbol);
        try {
            helper.assertValueEqual(server.getCommands().getDispatcher().execute("creeper dev market jump NOSUCHCRP 5", op), 0,
                    "没有的合约应报错");
            helper.assertValueEqual(pendingJump(symbol), afterOp, "报错的命令不改变市场");
            helper.assertTrue(failsToParse(server, op, "creeper dev market jump " + symbol + " -100"), "跌幅 100% 应被拒绝");
            helper.assertTrue(failsToParse(server, op, "creeper dev market jump " + symbol + " abc"), "幅度不是数字应被拒绝");
            helper.assertTrue(failsToParse(server, op, "creeper dev market jump " + symbol), "缺幅度应被拒绝");
            helper.assertTrue(failsToParse(server, regularPlayer, "creeper dev market jump " + symbol + " 7"), "普通玩家无权使用");
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException(e);
        }
        helper.assertValueEqual(pendingJump(symbol), afterOp, "被拒绝的命令不改变市场");
        helper.succeed();
    }

    private static double pendingJump(String symbol) {
        for (ContractState contract : ServerMarket.state().contracts()) {
            if (contract.symbol().equals(symbol)) {
                return contract.index().pendingJump();
            }
        }
        throw new IllegalStateException("没有这个合约：" + symbol);
    }

    private static boolean failsToParse(MinecraftServer server, CommandSourceStack source, String command) {
        try {
            server.getCommands().getDispatcher().execute(command, source);
            return false;
        } catch (CommandSyntaxException e) {
            return true;
        }
    }

    /** 双层定价之前的存档没有 pricing 字段：照常读出为单合约（DIAMONDCRP），从零溢价开始。 */
    private static void prePricingSaveLoadsWithZeroPremium(GameTestHelper helper) {
        CompoundTag oldSave = new CompoundTag();
        oldSave.putLong("step", 300L);
        oldSave.putInt("ticks_into_step", 4);
        oldSave.putDouble("index_price", 987.5);
        oldSave.putLong("random_state", 12345L);

        Market market = MarketSavedData.CODEC.parse(NbtOps.INSTANCE, oldSave).getOrThrow().bind(ContractSpec.defaults());
        ContractState diamond = market.state().contracts().get(0);

        helper.assertValueEqual(diamond.symbol(), DIAMOND, "旧存档里的唯一合约");
        helper.assertValueEqual(diamond.pricing(), PricingState.INITIAL, "旧存档的定价状态");
        helper.assertTrue(diamond.liveMinute() == null, "没有 K 线的旧存档不带正在走的 1m");
        helper.assertValueEqual(diamond.lastVolume(), 0.0, "没有 K 线的旧存档成交量从 0 开始");
        helper.assertValueEqual(diamond.randomState(), 12345L, "旧存档的随机数状态沿用");
        helper.assertTrue(Math.abs(market.snapshot(DIAMOND).lastPrice() - 987.5) < 1e-9, "零溢价时最新价格等于指数价格");
        helper.succeed();
    }

    /** 逼真行情模型之前（#6）的单合约存档：价格、暂停、成交量和正在走的 1m 都保留，新配置里的 GUNPOWDERCRP 从起始价格开始。 */
    private static void singleContractSaveLoadsAsDiamond(GameTestHelper helper) {
        CompoundTag pricing = new CompoundTag();
        pricing.putDouble("premium", 0.0004);
        pricing.putInt("premium_index_samples", 3);
        pricing.putDouble("premium_index_weighted_sum", 0.0021);
        pricing.put("basis_samples", new ListTag());
        CompoundTag liveMinute = new CompoundTag();
        liveMinute.putLong("start", 660L);
        liveMinute.putDouble("open", 986.0);
        liveMinute.putDouble("high", 990.0);
        liveMinute.putDouble("low", 985.0);
        liveMinute.putDouble("close", 987.9);
        liveMinute.putDouble("volume", 31.5);
        CompoundTag oldSave = new CompoundTag();
        oldSave.putLong("step", 118L);
        oldSave.putInt("ticks_into_step", 11);
        oldSave.putDouble("index_price", 987.5);
        oldSave.putLong("random_state", 777L);
        oldSave.putBoolean("paused", true);
        oldSave.put("pricing", pricing);
        oldSave.putDouble("last_volume", 6.5);
        oldSave.put("live_minute", liveMinute);

        Market market = MarketSavedData.CODEC.parse(NbtOps.INSTANCE, oldSave).getOrThrow().bind(ContractSpec.defaults());
        ContractState diamond = market.state().contracts().get(0);
        ContractState gunpowder = market.state().contracts().get(1);

        helper.assertValueEqual(market.symbols(), List.of(DIAMOND, GUNPOWDER), "按当前配置的合约");
        helper.assertValueEqual(market.state().step(), 118L, "市场时间沿用");
        helper.assertValueEqual(market.state().ticksIntoStep(), 11, "行情步内已攒的 tick 沿用");
        helper.assertTrue(market.isPaused(), "暂停状态沿用");
        helper.assertTrue(Math.abs(market.snapshot(DIAMOND).indexPrice() - 987.5) < 1e-9, "钻石的指数价格沿用");
        helper.assertValueEqual(diamond.pricing().premium(), 0.0004, "溢价沿用");
        helper.assertValueEqual(diamond.lastVolume(), 6.5, "成交量沿用");
        helper.assertValueEqual(diamond.liveMinute().start(), 660L, "正在走的 1m 沿用");
        helper.assertTrue(Math.abs(market.snapshot(GUNPOWDER).indexPrice() - 100.0) < 1e-9, "新加的火药从起始价格开始");
        helper.assertValueEqual(gunpowder.pricing(), PricingState.INITIAL, "新加的火药没有溢价");
        helper.succeed();
    }

    /** 配置里去掉的合约，状态原样留在存档里：按只含钻石的配置恢复再保存，火药的状态不丢，重新加回配置后接着走。 */
    private static void contractsRemovedFromConfigStayInTheSave(GameTestHelper helper) {
        List<ContractSpec> both = ContractSpec.defaults();
        Market market = new Market(11L, both);
        market.advanceSteps(500);
        MarketSavedData original = MarketSavedData.of(market);
        ContractState gunpowder = market.state().contracts().get(1);

        MarketSavedData reloaded = MarketSavedData.CODEC.parse(NbtOps.INSTANCE,
                MarketSavedData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow()).getOrThrow();
        Market onlyDiamond = reloaded.bind(List.of(both.get(0)));
        onlyDiamond.advanceSteps(10);
        helper.assertValueEqual(onlyDiamond.symbols(), List.of(DIAMOND), "只含钻石的配置");

        // 再保存、再读出：火药的状态还在，并且没被推进
        MarketSavedData again = MarketSavedData.CODEC.parse(NbtOps.INSTANCE,
                MarketSavedData.CODEC.encodeStart(NbtOps.INSTANCE, reloaded).getOrThrow()).getOrThrow();
        helper.assertValueEqual(again.state().contracts().get(1), gunpowder, "去掉的合约状态原样保留");
        Market readded = again.bind(both);
        helper.assertValueEqual(readded.symbols(), List.of(DIAMOND, GUNPOWDER), "重新加回配置");
        helper.assertValueEqual(readded.state().contracts().get(1), gunpowder, "重新加回的合约接着停下的状态走");
        helper.succeed();
    }

    /** 配置文件不存在时自动生成内置的两个合约；生成的文件能读回同一份配置，目录不存在也会创建。 */
    private static void contractConfigIsGeneratedWhenMissing(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("creepercapital-config");
        try {
            Path file = directory.resolve("config").resolve(ContractConfig.FILE_NAME);

            List<ContractSpec> loaded = ContractConfig.load(file);

            helper.assertValueEqual(loaded, ContractSpec.defaults(), "缺失时使用内置的两个合约");
            helper.assertTrue(Files.exists(file), "应当生成配置文件");
            String text = Files.readString(file, StandardCharsets.UTF_8);
            helper.assertTrue(text.contains(DIAMOND) && text.contains(GUNPOWDER) && text.contains("daily_volatility"),
                    "生成的文件应包含两个合约及其波动率倾向：" + text);
            helper.assertValueEqual(ContractConfig.load(file), ContractSpec.defaults(), "生成的文件读回同一份配置");
            helper.assertValueEqual(Files.readString(file, StandardCharsets.UTF_8), text, "读取不改写文件");
        } finally {
            deleteRecursively(directory);
        }
        helper.succeed();
    }

    /** 改配置文件会改变合约的波动率倾向，也可以增加合约、调整顺序。 */
    private static void contractConfigEditChangesTheVolatilityProfile(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("creepercapital-config");
        try {
            Path file = directory.resolve(ContractConfig.FILE_NAME);
            ContractConfig.load(file);
            String edited = Files.readString(file, StandardCharsets.UTF_8)
                    // 把火药的日波动率从 0.1 改到 0.2，并追加第三个合约
                    .replace("\"daily_volatility\": 0.1,", "\"daily_volatility\": 0.2,")
                    .replaceFirst("\\]\\s*\\}\\s*$", """
                            ,
                            {
                              "symbol": "BLAZERODCRP",
                              "underlying": "minecraft:blaze_rod",
                              "initial_price": 250.0,
                              "base_volume": 10.0,
                              "volatility": {
                                "daily_volatility": 0.06,
                                "jumps_per_day": 0.5,
                                "jump_min": 0.04,
                                "jump_max": 0.12,
                                "premium_bias": 0.002
                              }
                            }
                            ]
                            }
                            """);
            Files.writeString(file, edited, StandardCharsets.UTF_8);

            List<ContractSpec> loaded = ContractConfig.load(file);

            helper.assertValueEqual(loaded.stream().map(ContractSpec::symbol).toList(), List.of(DIAMOND, GUNPOWDER, "BLAZERODCRP"),
                    "配置里的合约，按文件顺序");
            helper.assertValueEqual(loaded.get(1).volatility().dailyVolatility(), 0.2, "改过的火药日波动率");
            helper.assertValueEqual(loaded.get(0), ContractSpec.defaults().get(0), "没改的钻石保持原样");
            helper.assertValueEqual(loaded.get(2).initialPrice(), 250.0, "新增合约的起始价格");
            Market market = new Market(1L, loaded);
            helper.assertValueEqual(market.spec(GUNPOWDER).volatility().dailyVolatility(), 0.2, "市场使用改过的波动率倾向");
            helper.assertTrue(Math.abs(market.snapshot("BLAZERODCRP").indexPrice() - 250.0) < 1e-9, "新增合约从自己的起始价格开始");
        } finally {
            deleteRecursively(directory);
        }
        helper.succeed();
    }

    /** 配置文件有错（JSON 损坏、没有合约、交易代码重复或不合法、缺字段、波动率兑现不了）时回退到内置合约，并且不覆盖文件。 */
    private static void invalidContractConfigFallsBackWithoutOverwriting(GameTestHelper helper) throws IOException {
        String valid = """
                {"symbol": "GOLDCRP", "underlying": "minecraft:gold_ingot", "initial_price": 50.0, "base_volume": 20.0,
                 "volatility": {"daily_volatility": 0.05, "jumps_per_day": 0.5, "jump_min": 0.03, "jump_max": 0.1,
                                "premium_bias": 0.001}}""";
        Map<String, String> invalid = new TreeMap<>(Map.of(
                "JSON 损坏", "{ \"contracts\": [ ",
                "顶层不是对象", "[]",
                "没有合约", "{\"contracts\": []}",
                "交易代码重复", "{\"contracts\": [" + valid + "," + valid + "]}",
                "交易代码不合法", "{\"contracts\": [" + valid.replace("GOLDCRP", "gold") + "]}",
                "缺字段", "{\"contracts\": [" + valid.replace("\"base_volume\": 20.0,", "") + "]}",
                "起始价格为负", "{\"contracts\": [" + valid.replace("\"initial_price\": 50.0", "\"initial_price\": -1.0") + "]}",
                "波动率低于跳跃自身造成的波动",
                "{\"contracts\": [" + valid.replace("\"daily_volatility\": 0.05", "\"daily_volatility\": 0.01")
                        .replace("\"jumps_per_day\": 0.5", "\"jumps_per_day\": 5.0") + "]}"));
        Path directory = Files.createTempDirectory("creepercapital-config");
        try {
            Path file = directory.resolve(ContractConfig.FILE_NAME);
            for (Map.Entry<String, String> entry : invalid.entrySet()) {
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);

                List<ContractSpec> loaded = ContractConfig.load(file);

                helper.assertValueEqual(loaded, ContractSpec.defaults(), "无效配置（" + entry.getKey() + "）应回退到内置合约");
                helper.assertValueEqual(Files.readString(file, StandardCharsets.UTF_8), entry.getValue(),
                        "无效配置（" + entry.getKey() + "）不应被覆盖");
            }
            // 合法的自定义配置（一个合约）照常读出，不会被内置合约替换
            Files.writeString(file, "{\"contracts\": [" + valid + "]}", StandardCharsets.UTF_8);
            helper.assertValueEqual(ContractConfig.load(file).stream().map(ContractSpec::symbol).toList(), List.of("GOLDCRP"),
                    "合法的自定义配置照常生效");
        } finally {
            deleteRecursively(directory);
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

    private static Map<Long, Double> indexPricesByStep(ServerPlayer player, String symbol) {
        Map<Long, Double> prices = new TreeMap<>();
        for (MarketSnapshotPayload payload : MockClient.received(player, MarketSnapshotPayload.class)) {
            if (payload.snapshot().symbol().equals(symbol)) {
                prices.put(payload.snapshot().step(), payload.snapshot().indexPrice());
            }
        }
        return prices;
    }
}
