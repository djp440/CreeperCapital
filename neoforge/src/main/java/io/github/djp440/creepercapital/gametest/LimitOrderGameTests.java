package io.github.djp440.creepercapital.gametest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.mojang.serialization.DataResult;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.trading.CancelReason;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderStatus;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.network.AccountPayload;
import io.github.djp440.creepercapital.network.CancelOrderPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.network.TradingStatePayload;
import io.github.djp440.creepercapital.server.ServerAccounts;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerTrading;
import io.github.djp440.creepercapital.server.TradingSavedData;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/**
 * 限价委托的 GameTest：挂单冻结与撤单的下单链路、行情步触发（含离线玩家）、委托的存档兼容。
 * 价格穿越与资金守恒的细节在 core 的 {@code OrderBookTest}；这里验证接线：载荷、同步给玩家的状态、行情步钩子和存档。
 */
final class LimitOrderGameTests {
    private static final String DIAMOND = "DIAMONDCRP";

    private LimitOrderGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("limit_order_rests_freezes_funds_and_cancels", LimitOrderGameTests::limitOrderRestsFreezesFundsAndCancels);
        function.accept("limit_orders_fill_on_market_step_for_online_and_offline_players",
                LimitOrderGameTests::limitOrdersFillOnMarketStepForOnlineAndOfflinePlayers);
        function.accept("limit_orders_are_rejected_while_paused_but_can_be_cancelled",
                LimitOrderGameTests::limitOrdersAreRejectedWhilePausedButCanBeCancelled);
        function.accept("trading_saved_data_without_orders_still_loads", LimitOrderGameTests::tradingSavedDataWithoutOrdersStillLoads);
    }

    /** 活市场的合约参数是配置文件决定的（数量精度、杠杆上限都可能不是内置值），手数和杠杆按它现算。 */
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

    private static OrderPayload buyLimit(double price) {
        return new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(), leverage(), OrderPayload.Execution.LIMIT, price);
    }

    /** 比最新价低得多的整数价格，保证挂单不会立即成交。 */
    private static double farBelowMarket() {
        return Math.floor(ServerMarket.snapshot(DIAMOND).lastPrice() * 0.5);
    }

    /** 1 个标的物、整数委托价 p 时冻结的资金（名义价值正好是 p）：保证金 p / 杠杆（向上取整到 6 位）加 maker 手续费 0.02% × p。 */
    private static Money frozenFor(double price) {
        BigDecimal p = BigDecimal.valueOf(price);
        BigDecimal margin = p.divide(BigDecimal.valueOf(leverage()), 6, RoundingMode.CEILING);
        BigDecimal fee = p.multiply(new BigDecimal("0.0002")).setScale(6, RoundingMode.HALF_UP);
        return Money.parse(margin.add(fee).toPlainString());
    }

    private static Order orderById(List<Order> orders, long id) {
        return orders.stream().filter(order -> order.id() == id).findFirst().orElseThrow();
    }

    /** 挂单冻结保证金与预估手续费，可用余额下降而钱包余额不变，同步给玩家；撤单后全部释放，进历史委托。 */
    private static void limitOrderRestsFreezesFundsAndCancels(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "LimitTrader1", "1000");
        try {
            UUID id = player.getUUID();
            double price = farBelowMarket();
            Money frozen = frozenFor(price);
            Money totalBefore = TradingGameTests.total(id);

            ServerTrading.handleOrder(player, buyLimit(price));

            OrderResultPayload placed = TradingGameTests.last(MockClient.received(player, OrderResultPayload.class));
            helper.assertTrue(placed.success(), "挂单应成功");
            helper.assertValueEqual(placed.code(), "limit_placed", "结果代码");
            Order order = ServerTrading.book().orders().open(id).get(0);
            helper.assertValueEqual(order.frozen(), frozen, "冻结 = 保证金 + maker 预估手续费");
            helper.assertValueEqual(ServerAccounts.balance(id), Money.parse("1000").minus(frozen), "可用余额减去冻结");
            helper.assertTrue(ServerTrading.book().positions(id).isEmpty(), "挂单不产生仓位");
            helper.assertValueEqual(TradingGameTests.total(id), totalBefore, "挂单后总额（含冻结）不变");
            AccountPayload account = TradingGameTests.last(MockClient.received(player, AccountPayload.class));
            helper.assertValueEqual(account.assets().availableBalance(), Money.parse("1000").minus(frozen), "资产页可用余额");
            helper.assertValueEqual(account.assets().walletBalance(), Money.parse("1000"), "资产页钱包余额仍含冻结");
            TradingStatePayload state = TradingGameTests.last(MockClient.received(player, TradingStatePayload.class));
            helper.assertValueEqual(orderById(state.orders(), order.id()).status(), OrderStatus.OPEN, "同步给玩家的当前委托");

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.CLOSE, DIAMOND, lots(), 0,
                    OrderPayload.Execution.LIMIT, price));
            helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(),
                    "no_position", "没有仓位时限价平仓被拒绝（限价平仓由 #15 接管）");

            ServerTrading.handleCancel(player, new CancelOrderPayload(999_999));
            helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(),
                    "no_such_order", "撤不存在的委托");
            helper.assertValueEqual(ServerTrading.book().orders().open(id).size(), 1, "撤错编号不影响挂单");

            ServerTrading.handleCancel(player, new CancelOrderPayload(order.id()));

            OrderResultPayload cancelled = TradingGameTests.last(MockClient.received(player, OrderResultPayload.class));
            helper.assertTrue(cancelled.success(), "撤单应成功");
            helper.assertValueEqual(cancelled.code(), "order_cancelled", "撤单结果代码");
            helper.assertValueEqual(ServerAccounts.balance(id), Money.parse("1000"), "撤单后余额完全恢复");
            helper.assertTrue(ServerTrading.book().orders().open(id).isEmpty(), "撤单后没有挂单");
            Order done = orderById(TradingGameTests.last(MockClient.received(player, TradingStatePayload.class)).orders(), order.id());
            helper.assertValueEqual(done.status(), OrderStatus.CANCELLED, "历史委托里是已撤销");
            helper.assertValueEqual(done.cancelReason(), CancelReason.USER, "撤销原因");
            helper.assertValueEqual(TradingGameTests.total(id), totalBefore, "撤单后总额不变");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /**
     * 两名玩家（一名在线、一名已离线）各挂一张低于市价的限价买单，再用 dev 跳跃让下一个行情步价格大跌（跳空穿过委托价）：
     * 两张都按委托价成交、收 maker 费；在线的收到提示。验证行情步钩子对离线玩家同样触发。
     */
    private static void limitOrdersFillOnMarketStepForOnlineAndOfflinePlayers(GameTestHelper helper) {
        ServerPlayer online = TradingGameTests.trader(helper, "LimitTrader2", "1000");
        ServerPlayer offline = TradingGameTests.trader(helper, "LimitTrader3", "1000");
        UUID onlineId = online.getUUID();
        UUID offlineId = offline.getUUID();
        double price = Math.floor(ServerMarket.snapshot(DIAMOND).lastPrice() * 0.99);
        ServerTrading.handleOrder(online, buyLimit(price));
        ServerTrading.handleOrder(offline, buyLimit(price));
        helper.assertValueEqual(ServerTrading.book().orders().open(onlineId).size(), 1, "在线玩家挂单");
        helper.assertValueEqual(ServerTrading.book().orders().open(offlineId).size(), 1, "待离线玩家挂单");
        MockClient.leave(offline);
        // 请求 −10%：实际跳幅由行情模型限定（可能只有几个百分点），但一定远大于 −1%，下一个行情步跳空穿过委托价
        ServerMarket.queueJump(DIAMOND, -0.1);
        BigDecimal makerFee = BigDecimal.valueOf(price).multiply(new BigDecimal("0.0002"));

        helper.succeedWhen(() -> {
            helper.assertTrue(ServerTrading.book().orders().open(onlineId).isEmpty(), "在线玩家的委托应已触发");
            helper.assertTrue(ServerTrading.book().orders().open(offlineId).isEmpty(), "离线玩家的委托也应已触发");
            for (UUID id : List.of(onlineId, offlineId)) {
                Position position = ServerTrading.book().position(id, DIAMOND).orElseThrow();
                helper.assertValueEqual(position.entryPrice(), price, "跳空仍按委托价成交");
                helper.assertValueEqual(ServerTrading.book().trades(id).get(0).fee(), Money.parse(makerFee.toPlainString()),
                        "收 maker 费 0.02%");
                Order filled = ServerTrading.book().orders().history(id).get(0);
                helper.assertValueEqual(filled.status(), OrderStatus.FILLED, "历史委托里是已成交");
            }
            OrderResultPayload notice = TradingGameTests.last(MockClient.received(online, OrderResultPayload.class));
            helper.assertValueEqual(notice.code(), "limit_filled", "在线玩家收到成交提示");
            TradingStatePayload state = TradingGameTests.last(MockClient.received(online, TradingStatePayload.class));
            helper.assertValueEqual(state.positions().size(), 1, "同步了新仓位");
            // 收尾：平掉仓位、取走余额，让共用账本恢复原样
            TradingGameTests.finish(online);
            cleanUpOffline(helper, offlineId);
        });
    }

    static void cleanUpOffline(GameTestHelper helper, UUID id) {
        TradingBook book = ServerTrading.book();
        for (Position position : book.positions(id)) {
            book.close(id, ServerMarket.spec(position.symbol()), ServerMarket.snapshot(position.symbol()), position.lots());
        }
        Money left = ServerAccounts.balance(id);
        if (left.isPositive()) {
            ServerAccounts.withdraw(helper.getLevel().getServer(), id, left);
        }
    }

    /** 市场暂停时不接新的限价单（价格冻结），但允许撤单；暂停必须在同一个 tick 内恢复。 */
    private static void limitOrdersAreRejectedWhilePausedButCanBeCancelled(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "LimitTrader4", "1000");
        try {
            ServerTrading.handleOrder(player, buyLimit(farBelowMarket()));
            long id = ServerTrading.book().orders().open(player.getUUID()).get(0).id();
            ServerMarket.pause();
            try {
                ServerTrading.handleOrder(player, buyLimit(farBelowMarket()));
                helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(),
                        "market_paused", "暂停时不接限价单");
                ServerTrading.handleCancel(player, new CancelOrderPayload(id));
            } finally {
                ServerMarket.resume();
            }
            helper.assertTrue(ServerTrading.book().orders().open(player.getUUID()).isEmpty(), "暂停时也能撤单");
            helper.assertValueEqual(ServerAccounts.balance(player.getUUID()), Money.parse("1000"), "撤单释放资金");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /** #14 之前的存档没有委托字段：照常读出，没有委托，编号从头开始。 */
    private static void tradingSavedDataWithoutOrdersStillLoads(GameTestHelper helper) {
        CompoundTag legacy = new CompoundTag();
        legacy.putLong("pool", Money.parse("1000000").units());
        legacy.putLong("last_trade_id", 5);
        legacy.put("positions", new ListTag());
        legacy.put("trades", new ListTag());

        DataResult<TradingSavedData> parsed = TradingSavedData.CODEC.parse(NbtOps.INSTANCE, (Tag) legacy);

        TradingBook book = parsed.getOrThrow().bind(new AccountBook());
        helper.assertValueEqual(book.state().lastTradeId(), 5L, "旧字段照常读出");
        helper.assertTrue(book.state().orders().isEmpty(), "旧存档没有委托");
        helper.assertValueEqual(book.state().lastOrderId(), 0L, "委托编号从头开始");
        helper.assertTrue(book.orders().history(UUID.randomUUID()).isEmpty(), "可以正常使用委托簿");
        helper.succeed();
    }
}
