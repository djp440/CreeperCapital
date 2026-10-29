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

import com.mojang.serialization.DataResult;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.trading.CancelReason;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderStatus;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Protection;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.network.CancelOrderPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.network.TradingStatePayload;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerTrading;
import io.github.djp440.creepercapital.server.TradingSavedData;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * 止盈止损与只减仓的 GameTest：下单载荷的接线（附带 / 补设 / 只减仓 / 限价平仓）、仓位平掉后的自动失效、
 * 行情步触发（含离线玩家）、委托附加条款的存档与旧存档兼容。触发判定、成交价与资金守恒的细节在 core 的 {@code ProtectionOrderTest}。
 * 活市场的价格、数量精度、杠杆上限都是配置决定的，所以价格、手数与杠杆都按当前合约现算。
 */
final class ProtectionGameTests {
    private static final String DIAMOND = "DIAMONDCRP";

    private ProtectionGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("protection_orders_attach_sync_and_expire_with_the_position", ProtectionGameTests::attachSyncAndExpire);
        function.accept("stop_loss_triggers_on_market_step_for_offline_players", ProtectionGameTests::stopLossTriggersForOfflinePlayers);
        function.accept("reduce_only_and_limit_close_orders_follow_the_rules", ProtectionGameTests::reduceOnlyRules);
        function.accept("protection_orders_survive_save_and_reload", helper -> {
            try {
                protectionSurvivesSaveAndReload(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static ContractSpec spec() {
        return ServerMarket.spec(DIAMOND);
    }

    /** 1 个标的物对应的手数。 */
    private static long lots() {
        return BigDecimal.TEN.pow(spec().quantityDecimals()).longValueExact();
    }

    private static int leverage() {
        return Math.min(10, spec().maxLeverage());
    }

    private static double last() {
        return ServerMarket.snapshot(DIAMOND).lastPrice();
    }

    private static OrderPayload openLong(double takeProfit, double stopLoss, TriggerSource source) {
        return new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(), leverage(), OrderPayload.Execution.MARKET, 0,
                takeProfit, stopLoss, source, false);
    }

    private static OrderResultPayload result(ServerPlayer player) {
        return TradingGameTests.last(MockClient.received(player, OrderResultPayload.class));
    }

    /**
     * 市价开多附带止盈（标记价格）与止损（标记价格）：随开仓立即生效、同步给玩家并带着触发价来源；
     * 玩家市价全平后，两张委托自动失效，历史委托里是「已撤销 / 仓位已平」。
     */
    private static void attachSyncAndExpire(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "ProtTrader1", "100000");
        try {
            UUID id = player.getUUID();
            double mark = ServerMarket.snapshot(DIAMOND).markPrice();
            double takeProfit = Math.floor(mark * 1.5);
            double stopLoss = Math.floor(mark * 0.5);

            ServerTrading.handleOrder(player, openLong(takeProfit, stopLoss, TriggerSource.MARK));

            helper.assertValueEqual(result(player).code(), "open_long", "开仓成功");
            List<Order> open = ServerTrading.book().orders().open(id);
            helper.assertValueEqual(open.stream().map(Order::type).toList(), List.of(OrderType.TAKE_PROFIT, OrderType.STOP_LOSS),
                    "随开仓生成止盈、止损委托");
            helper.assertValueEqual(open.stream().map(Order::price).toList(), List.of(takeProfit, stopLoss), "触发价");
            helper.assertValueEqual(open.get(0).triggerSource(), TriggerSource.MARK, "触发价来源");
            helper.assertValueEqual(open.get(0).lots(), lots(), "数量 = 开仓数量");
            helper.assertTrue(open.get(0).reduceOnly(), "止盈止损只减仓");
            TradingStatePayload state = TradingGameTests.last(MockClient.received(player, TradingStatePayload.class));
            helper.assertValueEqual(state.orders().stream().filter(Order::isOpen).count(), 2L, "同步给玩家的当前委托");
            helper.assertValueEqual(state.orders().get(0).triggerSource(), TriggerSource.MARK, "同步了触发价来源");

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.CLOSE, DIAMOND, lots(), 0));

            helper.assertValueEqual(result(player).code(), "close", "全平成功");
            helper.assertTrue(ServerTrading.book().orders().open(id).isEmpty(), "仓位平掉后止盈止损自动失效");
            List<Order> history = ServerTrading.book().orders().history(id);
            helper.assertValueEqual(history.size(), 2, "失效的委托进历史");
            for (Order expired : history) {
                helper.assertValueEqual(expired.status(), OrderStatus.CANCELLED, "状态");
                helper.assertValueEqual(expired.cancelReason(), CancelReason.POSITION_CLOSED, "失效原因");
            }
            TradingStatePayload after = TradingGameTests.last(MockClient.received(player, TradingStatePayload.class));
            helper.assertValueEqual(after.orders().stream().filter(Order::isOpen).count(), 0L, "同步：没有当前委托了");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /**
     * 离线玩家带着止损（最新价格 −1% 处），用 dev 跳跃让下一个行情步价格大跌：止损触发并按市价平仓，
     * 手续费是成交名义价值的 0.05%（taker）；委托转为已成交，仓位清空。
     */
    private static void stopLossTriggersForOfflinePlayers(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "ProtTrader2", "100000");
        UUID id = player.getUUID();
        double stopLoss = Math.floor(last() * 0.99);
        // 低杠杆开仓：跳空亏损不会吃光保证金，手续费才不会被保证金截断，可以按 0.05% 精确断言
        ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(),
                Math.min(2, spec().maxLeverage()), OrderPayload.Execution.MARKET, 0, 0, stopLoss, TriggerSource.LAST, false));
        helper.assertValueEqual(ServerTrading.book().orders().open(id).size(), 1, "附带的止损生效");
        MockClient.leave(player);
        ServerMarket.queueJump(DIAMOND, -0.1);

        helper.succeedWhen(() -> {
            helper.assertTrue(ServerTrading.book().orders().open(id).isEmpty(), "离线玩家的止损也应触发");
            helper.assertTrue(ServerTrading.book().positions(id).isEmpty(), "止损触发后仓位清空");
            Order done = ServerTrading.book().orders().history(id).get(0);
            helper.assertValueEqual(done.type(), OrderType.STOP_LOSS, "委托类型");
            helper.assertValueEqual(done.status(), OrderStatus.FILLED, "止损已成交");
            helper.assertTrue(done.fillPrice() < stopLoss, "跳空越过触发价，成交价低于触发价");
            PlayerTrade close = ServerTrading.book().trades(id).get(ServerTrading.book().trades(id).size() - 1);
            helper.assertValueEqual(close.kind(), TradeKind.CLOSE, "成交历史里是一笔平仓");
            helper.assertValueEqual(close.price(), done.fillPrice(), "委托的成交价就是这笔平仓的成交价");
            BigDecimal notional = BigDecimal.valueOf(close.price()).multiply(BigDecimal.valueOf(close.lots()))
                    .movePointLeft(spec().quantityDecimals()).setScale(6, RoundingMode.HALF_UP);
            Money fee = Money.parse(notional.multiply(new BigDecimal("0.0005")).setScale(6, RoundingMode.HALF_UP).toPlainString());
            helper.assertValueEqual(close.fee(), fee, "触发后按市价成交，收 taker 费 0.05%");
            LimitOrderGameTests.cleanUpOffline(helper, id);
        });
    }

    /**
     * 只减仓与限价平仓：没有仓位时拒绝；开多带只减仓是卖出缩减多仓，数量超过仓位时缩减到仓位，永远不会开出空仓；
     * 给仓位补设止盈止损：触发价会立即触发就拒绝，合法则生成委托，可以撤单。
     */
    private static void reduceOnlyRules(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "ProtTrader3", "100000");
        try {
            UUID id = player.getUUID();
            double price = Math.floor(last());
            OrderPayload sellReduceOnly = new OrderPayload(OrderPayload.Action.OPEN_SHORT, DIAMOND, lots() * 5, leverage(),
                    OrderPayload.Execution.MARKET, 0, 0, 0, TriggerSource.LAST, true);

            ServerTrading.handleOrder(player, sellReduceOnly);
            helper.assertValueEqual(result(player).code(), "no_position", "没有仓位时只减仓拒绝");
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.CLOSE, DIAMOND, lots(), 0,
                    OrderPayload.Execution.LIMIT, price));
            helper.assertValueEqual(result(player).code(), "no_position", "没有仓位时限价平仓拒绝");
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.PROTECT, DIAMOND, lots(), 0,
                    OrderPayload.Execution.MARKET, 0, price * 2, 0, TriggerSource.LAST, false));
            helper.assertValueEqual(result(player).code(), "no_position", "没有仓位时不能设止盈止损");
            helper.assertTrue(ServerTrading.book().positions(id).isEmpty(), "拒绝不产生仓位");

            ServerTrading.handleOrder(player, openLong(0, 0, TriggerSource.LAST));
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.PROTECT, DIAMOND, lots(), 0,
                    OrderPayload.Execution.MARKET, 0, price * 0.5, 0, TriggerSource.LAST, false));
            helper.assertValueEqual(result(player).code(), "invalid_trigger_price", "止盈价低于最新价会立即触发，拒绝");
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.PROTECT, DIAMOND, lots(), 0,
                    OrderPayload.Execution.MARKET, 0, price * 2, price * 0.5, TriggerSource.LAST, false));
            helper.assertValueEqual(result(player).code(), "protection_placed", "合法的止盈止损生成委托");
            helper.assertValueEqual(ServerTrading.book().orders().open(id).size(), 2, "止盈、止损各一张");
            long takeProfitId = ServerTrading.book().orders().open(id).get(0).id();
            ServerTrading.handleCancel(player, new CancelOrderPayload(takeProfitId));
            helper.assertValueEqual(ServerTrading.book().orders().open(id).size(), 1, "止盈止损可以单独撤单");

            ServerTrading.handleOrder(player, sellReduceOnly);

            helper.assertValueEqual(result(player).code(), "close", "只减仓卖出按平仓处理");
            helper.assertTrue(ServerTrading.book().positions(id).isEmpty(), "数量超过仓位时缩减到仓位，没有反向开仓");
            PlayerTrade trade = ServerTrading.book().trades(id).get(ServerTrading.book().trades(id).size() - 1);
            helper.assertValueEqual(trade.lots(), lots(), "实际成交数量 = 仓位数量");
            helper.assertTrue(ServerTrading.book().orders().open(id).isEmpty(), "仓位平掉后剩下的止损随之失效");

            // 限价平仓（只减仓限价单）：重新开仓后挂一张远高于市价的卖单，挂着不成交，撤单即可
            ServerTrading.handleOrder(player, openLong(0, 0, TriggerSource.LAST));
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.CLOSE, DIAMOND, lots(), 0,
                    OrderPayload.Execution.LIMIT, price * 3));
            helper.assertValueEqual(result(player).code(), "limit_placed", "限价平仓挂出");
            Order resting = ServerTrading.book().orders().open(id).get(0);
            helper.assertTrue(resting.reduceOnly(), "限价平仓是只减仓限价单");
            helper.assertValueEqual(resting.frozen(), Money.ZERO, "平仓类委托不冻结资金");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /**
     * 止盈止损、只减仓限价单随存档保留：写进存档文件再用全新存储读出，委托及附加条款（触发价来源、只减仓、限价开仓附带的止盈止损）一致，
     * 恢复后的止盈照常触发；#15 之前的存档（委托没有 extras 字段）照常加载。
     */
    private static void protectionSurvivesSaveAndReload(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        UUID alice = UUID.randomUUID();
        ContractSpec diamond = ContractSpec.defaults().get(0);
        MarketSnapshot at = new MarketSnapshot(100, DIAMOND, 1000, 1000, 1000, 0.0001, 0);
        Path dataFolder = Files.createTempDirectory("creepercapital-protection");
        try {
            AccountBook accounts = new AccountBook();
            accounts.deposit(alice, Money.parse("10000"));
            SavedDataStorage writer = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            TradingSavedData data = writer.computeIfAbsent(TradingSavedData.TYPE);
            TradingBook book = data.bind(accounts);
            book.open(alice, diamond, at, Side.LONG, 2000, 10);
            book.orders().placeProtection(alice, diamond, at, 2000, new Protection(1100, 900, TriggerSource.MARK));
            book.orders().placeReduceOnlyLimit(alice, diamond, at, 1000, 1500);
            book.orders().placeLimit(alice, diamond, at, Side.LONG, 1000, 10, 950, new Protection(1200, 800, TriggerSource.MARK));
            data.setDirty();
            writer.saveAndJoin();
            writer.close();

            SavedDataStorage reader = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            TradingBook restored = reader.get(TradingSavedData.TYPE).bind(accounts);
            helper.assertValueEqual(restored.orders().open(alice), book.orders().open(alice), "重新加载后的委托（含附加条款）");
            helper.assertValueEqual(restored.orders().open(alice).size(), 4, "止盈、止损、只减仓限价单、附带止盈止损的限价开仓单");
            restored.orders().onSnapshot(diamond, new MarketSnapshot(120, DIAMOND, 1000, 1000, 1100, 0.0001, 0));
            helper.assertTrue(restored.position(alice, DIAMOND).isEmpty(), "恢复后标记价格到了止盈价，止盈照常触发");
            reader.close();

            // 旧存档：把每张委托的 extras 字段去掉再读
            CompoundTag encoded = (CompoundTag) TradingSavedData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
            ListTag orders = encoded.getListOrEmpty("orders");
            for (int i = 0; i < orders.size(); i++) {
                orders.getCompound(i).ifPresent(order -> order.remove("extras"));
            }
            DataResult<TradingSavedData> legacy = TradingSavedData.CODEC.parse(NbtOps.INSTANCE, (Tag) encoded);
            TradingBook old = legacy.getOrThrow().bind(new AccountBook());
            helper.assertValueEqual(old.orders().open(alice).size(), 4, "没有 extras 的旧存档照常加载");
            helper.assertTrue(old.orders().open(alice).stream().noneMatch(Order::reduceOnly), "旧委托没有附加条款");
        } finally {
            deleteRecursively(dataFolder);
        }
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
