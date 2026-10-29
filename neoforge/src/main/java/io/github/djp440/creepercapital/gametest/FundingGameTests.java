package io.github.djp440.creepercapital.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.trading.FundingRecord;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.network.AccountPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.network.TradingStatePayload;
import io.github.djp440.creepercapital.server.ServerAccounts;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerTrading;
import io.github.djp440.creepercapital.server.TradingSavedData;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * 资金费结算的 GameTest：行情步钩子里的接线（只在结算点结算、在线玩家实时收到更新、离线玩家照常结算、逐仓扣到 0 为止）
 * 与存档往返。金额公式、方向和边界的细节在 core 的 {@code FundingSettlementTest}，结算节奏与重启对齐在 {@code FundingScheduleTest}。
 * <p>
 * 活市场不能被推进，所以用「活市场此刻的快照换一个结算点的行情步编号和费率」合成结算点那一步，直接交给行情步钩子。
 */
final class FundingGameTests {
    private static final String DIAMOND = "DIAMONDCRP";
    /** 够大的结算点：4800 的整数倍，远超活市场的行情步，不会和真实结算点撞上。 */
    private static final long SETTLEMENT_STEP = 4800L * 1_000_000;

    private FundingGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("funding_settles_only_at_the_settlement_step_and_notifies_online_player",
                FundingGameTests::settlesOnlyAtTheSettlementStepAndNotifiesOnlinePlayer);
        function.accept("funding_settles_offline_players_and_cross_balance_and_conserves_funds",
                FundingGameTests::settlesOfflinePlayersAndCrossBalanceAndConservesFunds);
        function.accept("funding_drains_isolated_margin_to_zero_and_never_goes_negative",
                FundingGameTests::drainsIsolatedMarginToZeroAndNeverGoesNegative);
        function.accept("funding_records_survive_save_and_reload_and_old_saves_load", helper -> {
            try {
                recordsSurviveSaveAndReloadAndOldSavesLoad(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static ContractSpec spec() {
        return ServerMarket.spec(DIAMOND);
    }

    /** 活市场此刻的快照，换成给定行情步编号和资金费率（价格不变）：结算点那一步。 */
    private static MarketSnapshot at(long step, double rate) {
        MarketSnapshot live = ServerMarket.snapshot(DIAMOND);
        return new MarketSnapshot(step, DIAMOND, live.indexPrice(), live.lastPrice(), live.markPrice(), rate, 0);
    }

    /** 半档的数量，在第一档之内，没有滑点；档的大小随活市场的价格和配置文件的数量精度变化，所以现算。 */
    private static long lots() {
        return Math.max(1, spec().liquidityCurve(ServerMarket.snapshot(DIAMOND).lastPrice()).levelLots() / 2);
    }

    /** 缺省分档下高杠杆只允许小仓位，所以用低杠杆。 */
    private static int leverage() {
        return Math.min(5, spec().maxLeverage());
    }

    /** 测试这一侧独立写的资金费期望：名义价值（标记价格 × 数量，取整到 1e-6）× 费率，四舍五入到 1e-6。 */
    private static Money expectedFunding(double mark, long lots, double rate) {
        BigDecimal notional = BigDecimal.valueOf(mark).multiply(BigDecimal.valueOf(lots))
                .movePointLeft(spec().quantityDecimals()).setScale(6, RoundingMode.HALF_UP);
        return Money.ofUnits(notional.movePointRight(6).multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.HALF_UP)
                .longValueExact());
    }

    private static void open(ServerPlayer player, OrderPayload.Action action, long lots) {
        ServerTrading.handleOrder(player, new OrderPayload(action, DIAMOND, lots, leverage()));
    }

    private static void runStep(GameTestHelper helper, List<MarketSnapshot> snapshots) {
        ServerTrading.onMarketStep(helper.getLevel().getServer(), snapshots);
    }

    /** 离线玩家的清理：平仓、取走余额（不经过 MockClient.leave，人已经不在线了）。 */
    private static void cleanUpOffline(MinecraftServer server, UUID id) {
        TradingBook book = ServerTrading.book();
        for (Position position : book.positions(id)) {
            book.close(id, ServerMarket.spec(position.symbol()), ServerMarket.snapshot(position.symbol()), position.lots());
        }
        for (String symbol : ServerMarket.symbols()) {
            book.setMarginMode(id, symbol, MarginMode.ISOLATED);
        }
        Money left = ServerAccounts.balance(id);
        if (left.isPositive()) {
            ServerAccounts.withdraw(server, id, left);
        }
    }

    /** 非结算点什么也不发生；结算点那一步，多头（逐仓）从保证金里付给池子，在线玩家收到提示、资产和带记录的交易状态。 */
    private static void settlesOnlyAtTheSettlementStepAndNotifiesOnlinePlayer(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "FundingLong", "1000");
        try {
            UUID id = player.getUUID();
            long lots = lots();
            open(player, OrderPayload.Action.OPEN_LONG, lots);
            Position position = ServerTrading.book().position(id, DIAMOND).orElseThrow();
            Money poolBefore = ServerTrading.poolBalance();
            Money totalBefore = TradingGameTests.total(id);
            int stateMessages = MockClient.received(player, TradingStatePayload.class).size();

            // 非结算点：前一步、后一步都不结算
            runStep(helper, List.of(at(SETTLEMENT_STEP - 1, 0.0001)));
            runStep(helper, List.of(at(SETTLEMENT_STEP + 1, 0.0001)));
            helper.assertTrue(ServerTrading.book().fundingRecords(id).isEmpty(), "非结算点不结算");
            helper.assertValueEqual(ServerTrading.poolBalance(), poolBefore, "非结算点池子不动");

            MarketSnapshot settlement = at(SETTLEMENT_STEP, 0.0001);
            helper.assertValueEqual(settlement.secondsUntilFunding(), 0L, "合成的结算点：行情栏倒计时为 00:00:00");
            runStep(helper, List.of(settlement));

            Money expected = expectedFunding(settlement.markPrice(), lots, 0.0001);
            helper.assertTrue(expected.isPositive(), "测试用的仓位应当产生非零资金费：" + expected);
            List<FundingRecord> records = ServerTrading.book().fundingRecords(id);
            helper.assertValueEqual(records.size(), 1, "恰好一条资金费记录");
            FundingRecord record = records.get(0);
            helper.assertValueEqual(record.amount(), Money.ZERO.minus(expected), "多头在正费率下支付 名义价值 × 费率");
            helper.assertValueEqual(record.side(), Side.LONG, "记录的方向");
            helper.assertValueEqual(record.rate(), 0.0001, "记录的费率");
            helper.assertValueEqual(record.markPrice(), settlement.markPrice(), "记录的标记价格");
            helper.assertValueEqual(record.marketSeconds(), SETTLEMENT_STEP * 6, "记录的市场时间");
            helper.assertValueEqual(record.shortfall(), Money.ZERO, "没有未收付的部分");
            helper.assertValueEqual(ServerTrading.book().position(id, DIAMOND).orElseThrow().margin(),
                    position.margin().minus(expected), "逐仓：从仓位保证金里扣");
            helper.assertValueEqual(ServerTrading.poolBalance(), poolBefore.plus(expected), "池子收到资金费");
            helper.assertValueEqual(TradingGameTests.total(id), totalBefore, "玩家加池子的总额不变");

            // 在线玩家实时收到：提示、资产、带资金费记录的交易状态
            OrderResultPayload message = TradingGameTests.last(MockClient.received(player, OrderResultPayload.class));
            helper.assertValueEqual(message.code(), "funding_paid", "支付提示");
            helper.assertValueEqual(message.args().get(0), DIAMOND, "提示里的合约");
            helper.assertValueEqual(message.args().get(1), expected.toDisplayString(), "提示里的金额");
            List<TradingStatePayload> states = MockClient.received(player, TradingStatePayload.class);
            helper.assertTrue(states.size() > stateMessages, "结算后重发了交易状态");
            helper.assertValueEqual(TradingGameTests.last(states).funding(), records, "交易状态里带着资金费记录");
            AccountPayload account = TradingGameTests.last(MockClient.received(player, AccountPayload.class));
            helper.assertValueEqual(account.assets().walletBalance(),
                    ServerTrading.assets(id).walletBalance(), "资产随之更新");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /**
     * 同样数量的在线多头（逐仓）与离线空头（全仓）：费率为正时多头付、空头收；离线玩家同样结算，全仓收付进余额；
     * 两边名义价值相同，池子净额为零。
     */
    private static void settlesOfflinePlayersAndCrossBalanceAndConservesFunds(GameTestHelper helper) {
        ServerPlayer online = TradingGameTests.trader(helper, "FundingOnline", "1000");
        ServerPlayer offline = TradingGameTests.trader(helper, "FundingOffline", "1000");
        UUID offlineId = offline.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        try {
            long lots = lots();
            ServerTrading.book().setMarginMode(offlineId, DIAMOND, MarginMode.CROSS);
            open(online, OrderPayload.Action.OPEN_LONG, lots);
            open(offline, OrderPayload.Action.OPEN_SHORT, lots);
            helper.assertValueEqual(ServerTrading.book().position(offlineId, DIAMOND).orElseThrow().mode(), MarginMode.CROSS,
                    "离线玩家的仓位是全仓");
            MockClient.leave(offline);
            Money balanceBefore = ServerAccounts.balance(offlineId);
            Money poolBefore = ServerTrading.poolBalance();
            Money totalBefore = TradingGameTests.total(online.getUUID()).plus(TradingGameTests.total(offlineId));

            MarketSnapshot settlement = at(SETTLEMENT_STEP, 0.0002);
            runStep(helper, List.of(settlement));

            Money expected = expectedFunding(settlement.markPrice(), lots, 0.0002);
            helper.assertTrue(expected.isPositive(), "测试用的仓位应当产生非零资金费：" + expected);
            FundingRecord shortRecord = ServerTrading.book().fundingRecords(offlineId).get(0);
            helper.assertValueEqual(shortRecord.amount(), expected, "空头在正费率下收到资金费");
            helper.assertValueEqual(shortRecord.mode(), MarginMode.CROSS, "记录的保证金模式");
            helper.assertValueEqual(ServerAccounts.balance(offlineId), balanceBefore.plus(expected), "全仓：收付进余额");
            helper.assertValueEqual(ServerTrading.book().fundingRecords(online.getUUID()).get(0).amount(),
                    Money.ZERO.minus(expected), "在线多头付出同样的数额");
            helper.assertValueEqual(ServerTrading.poolBalance(), poolBefore, "多空数量相同，池子净额为零");
            helper.assertValueEqual(TradingGameTests.total(online.getUUID()).plus(TradingGameTests.total(offlineId)),
                    totalBefore, "玩家与池子之间的总额不变");
        } finally {
            cleanUpOffline(server, offlineId);
            TradingGameTests.finish(online);
        }
        helper.succeed();
    }

    /**
     * 费率大到逐仓保证金不够付：保证金扣到 0 为止，可用余额不动，差额记入记录，在线玩家得到「未能收付」的提示；
     * 保证金被抽干的仓位在同一个行情步随后的强平检查里被强平（#12 接手，顺序是先资金费、后强平），
     * 强平不碰可用余额，玩家 + 池子 + 基金的总额不变。
     */
    private static void drainsIsolatedMarginToZeroAndNeverGoesNegative(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "FundingDrained", "1000");
        try {
            UUID id = player.getUUID();
            long lots = lots();
            open(player, OrderPayload.Action.OPEN_LONG, lots);
            Position position = ServerTrading.book().position(id, DIAMOND).orElseThrow();
            Money balanceBefore = ServerAccounts.balance(id);
            Money totalBefore = TradingGameTests.total(id).plus(ServerTrading.book().fund().balance());

            // 费率 500%：应付远大于保证金
            MarketSnapshot settlement = at(SETTLEMENT_STEP, 5.0);
            runStep(helper, List.of(settlement));

            Money due = expectedFunding(settlement.markPrice(), lots, 5.0);
            helper.assertTrue(due.compareTo(position.margin()) > 0, "应付 " + due + " 应超过保证金 " + position.margin());
            FundingRecord record = ServerTrading.book().fundingRecords(id).get(0);
            helper.assertValueEqual(record.amount(), Money.ZERO.minus(position.margin()), "付出的就是全部保证金");
            helper.assertValueEqual(record.shortfall(), due.minus(position.margin()), "差额记为未收付");
            helper.assertTrue(ServerTrading.book().position(id, DIAMOND).isEmpty(), "保证金被抽干的仓位在同一步被强平");
            PlayerTrade liquidation = TradingGameTests.last(ServerTrading.book().trades(id));
            helper.assertValueEqual(liquidation.kind(), TradeKind.LIQUIDATION, "成交历史里是强平记录");
            helper.assertValueEqual(liquidation.fee(), Money.ZERO, "保证金一分不剩，清算费为 0");
            helper.assertValueEqual(ServerAccounts.balance(id), balanceBefore, "逐仓不动可用余额");
            helper.assertValueEqual(TradingGameTests.total(id).plus(ServerTrading.book().fund().balance()), totalBefore,
                    "玩家 + 池子 + 基金的总额不变");
            List<String> codes = MockClient.received(player, OrderResultPayload.class).stream().map(OrderResultPayload::code).toList();
            helper.assertTrue(codes.indexOf("funding_partial") >= 0, "未能全额收付的提示");
            helper.assertTrue(codes.indexOf("liquidated") > codes.indexOf("funding_partial"), "随后是强平通知：" + codes);
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /** 资金费记录随交易账存档往返，恢复后编号接着往下；没有这两个字段的旧存档照常加载。 */
    private static void recordsSurviveSaveAndReloadAndOldSavesLoad(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        UUID alice = UUID.randomUUID();
        ContractSpec diamond = ContractSpec.defaults().get(0);
        MarketSnapshot opening = new MarketSnapshot(100, DIAMOND, 1000, 1000, 1000, 0.0001, 0);
        MarketSnapshot settlement = new MarketSnapshot(4800, DIAMOND, 1000, 1000, 1100, 0.0001, 0);
        Path dataFolder = Files.createTempDirectory("creepercapital-funding");
        try {
            AccountBook accounts = new AccountBook();
            accounts.deposit(alice, Money.parse("1000"));
            SavedDataStorage writer = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            TradingSavedData data = writer.computeIfAbsent(TradingSavedData.TYPE);
            TradingBook book = data.bind(accounts);
            book.open(alice, diamond, opening, Side.LONG, 2000, 10);
            book.settleFunding(settlement);
            data.setDirty();
            writer.saveAndJoin();
            writer.close();

            SavedDataStorage reader = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            TradingBook restored = reader.get(TradingSavedData.TYPE).bind(accounts);
            helper.assertValueEqual(restored.fundingRecords(alice), book.fundingRecords(alice), "重新加载后的资金费记录");
            helper.assertValueEqual(restored.fundingRecords(alice).get(0).amount(), Money.parse("-0.22"),
                    "2.000 × 1100 × 0.01% = 0.22，多头付");
            helper.assertValueEqual(restored.position(alice, DIAMOND), book.position(alice, DIAMOND), "扣过的保证金一并读回");
            restored.settleFunding(new MarketSnapshot(9600, DIAMOND, 1000, 1000, 1100, 0.0001, 0));
            helper.assertValueEqual(restored.fundingRecords(alice).get(1).id(), 2L, "恢复后接着编号");
            reader.close();
        } finally {
            deleteRecursively(dataFolder);
        }

        // #13 之前的存档：没有 last_funding_id 和 funding
        String oldSave = """
                {"pool": 1000000000000, "last_trade_id": 0,
                 "positions": [{"player": %s,
                   "position": {"symbol": "DIAMONDCRP", "side": "LONG", "lots": 1000, "entry_price": 1000.0,
                                "leverage": 10, "margin": 100000000, "quantity_decimals": 3}}],
                 "trades": []}
                """.formatted(java.util.Arrays.toString(UUIDUtil.uuidToIntArray(alice)));
        TradingBook legacy = TradingSavedData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(oldSave)).getOrThrow()
                .bind(new AccountBook());
        helper.assertTrue(legacy.fundingRecords(alice).isEmpty(), "旧存档没有资金费记录");
        helper.assertValueEqual(legacy.settleFunding(settlement).get(0).id(), 1L, "旧存档加载后可以正常结算");
        helper.succeed();
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
