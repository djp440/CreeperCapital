package io.github.djp440.creepercapital.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarginTiers;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.CancelReason;
import io.github.djp440.creepercapital.core.trading.FundingRecord;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.MarginResult;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderExtras;
import io.github.djp440.creepercapital.core.trading.OrderStatus;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Quantities;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradeResult;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.network.AccountPayload;
import io.github.djp440.creepercapital.network.AdlLightsPayload;
import io.github.djp440.creepercapital.network.CancelOrderPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.network.TradingStatePayload;
import io.github.djp440.creepercapital.server.ContractConfig;
import io.github.djp440.creepercapital.server.ServerAccounts;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerTrading;
import io.github.djp440.creepercapital.server.TradingSavedData;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.UUIDUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * 市价开平仓与流动性池的 GameTest：下单链路（成交价、保证金、资金守恒、同步给玩家）、拒单提示、
 * dev 注资命令的权限、交易账的存档往返、载荷编解码和配置里的交易参数。
 * 所有测试共用同一个活市场（行情步只在 tick 之间推进，所以同一个 tick 里读到的价格是稳定的）。
 */
final class TradingGameTests {
    private static final String DIAMOND = "DIAMONDCRP";

    private TradingGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("order_payloads_round_trip", TradingGameTests::orderPayloadsRoundTrip);
        function.accept("market_order_fills_at_last_price_plus_half_spread_and_conserves_funds",
                TradingGameTests::marketOrderFillsAtLastPricePlusHalfSpreadAndConservesFunds);
        function.accept("short_order_fills_below_last_price_and_closes_partially",
                TradingGameTests::shortOrderFillsBelowLastPriceAndClosesPartially);
        function.accept("big_order_slips_along_the_curve_but_leaves_the_price_alone",
                TradingGameTests::bigOrderSlipsAlongTheCurveButLeavesThePriceAlone);
        function.accept("rejected_orders_explain_why_and_change_nothing", TradingGameTests::rejectedOrdersExplainWhyAndChangeNothing);
        function.accept("orders_are_rejected_while_the_market_is_paused", TradingGameTests::ordersAreRejectedWhileTheMarketIsPaused);
        function.accept("dev_pool_fund_is_op_only", TradingGameTests::devPoolFundIsOpOnly);
        function.accept("trading_state_survives_save_and_reload", helper -> {
            try {
                tradingStateSurvivesSaveAndReload(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        function.accept("margin_orders_switch_mode_and_adjust_leverage", TradingGameTests::marginOrdersSwitchModeAndAdjustLeverage);
        function.accept("margin_modes_survive_save_and_reload_and_old_saves_default_to_isolated", helper -> {
            try {
                marginModesSurviveSaveAndReloadAndOldSavesDefaultToIsolated(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        LimitOrderGameTests.register(function);
        FundingGameTests.register(function);
        ProtectionGameTests.register(function);
        LiquidationGameTests.register(function);
        AdlGameTests.register(function);
        StageOrderGameTests.register(function);
        OfflineSummaryGameTests.register(function);
        function.accept("contract_config_reads_trading_parameters", helper -> {
            try {
                contractConfigReadsTradingParameters(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** 下单、交易状态与下单结果的编解码不丢字段，金额按最小单位精确往返。 */
    private static void orderPayloadsRoundTrip(GameTestHelper helper) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        OrderPayload order = new OrderPayload(OrderPayload.Action.OPEN_SHORT, DIAMOND, 2500, 25);
        OrderPayload.STREAM_CODEC.encode(buffer, order);
        helper.assertValueEqual(OrderPayload.STREAM_CODEC.decode(buffer), order, "下单载荷");
        OrderPayload limit = new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, 1000, 10, OrderPayload.Execution.LIMIT,
                987.65);
        OrderPayload.STREAM_CODEC.encode(buffer, limit);
        helper.assertValueEqual(OrderPayload.STREAM_CODEC.decode(buffer), limit, "限价下单载荷");
        OrderPayload protection = new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, 1000, 10, OrderPayload.Execution.MARKET,
                0, 1100.5, 900.25, TriggerSource.MARK, true);
        OrderPayload.STREAM_CODEC.encode(buffer, protection);
        helper.assertValueEqual(OrderPayload.STREAM_CODEC.decode(buffer), protection, "带止盈止损与只减仓的下单载荷");
        OrderPayload protect = new OrderPayload(OrderPayload.Action.PROTECT, DIAMOND, 1000, 0, OrderPayload.Execution.MARKET, 0,
                1100, 0, TriggerSource.LAST, false);
        OrderPayload.STREAM_CODEC.encode(buffer, protect);
        helper.assertValueEqual(OrderPayload.STREAM_CODEC.decode(buffer), protect, "补设止盈止损的载荷");
        CancelOrderPayload cancel = new CancelOrderPayload(42);
        CancelOrderPayload.STREAM_CODEC.encode(buffer, cancel);
        helper.assertValueEqual(CancelOrderPayload.STREAM_CODEC.decode(buffer), cancel, "撤单载荷");

        UUID player = UUID.randomUUID();
        TradingStatePayload state = new TradingStatePayload(
                List.of(new TradingParams(DIAMOND, 0.5, 125, 3, 0.0005, MarginTiers.standard(125))),
                List.of(new Position(DIAMOND, Side.SHORT, 2500, 999.75, 25, Money.parse("99.975"), 3, MarginMode.CROSS)),
                List.of(new PlayerTrade(7, player, DIAMOND, Side.SHORT, TradeKind.OPEN, 999.75, 2500, Money.parse("1.249687"),
                        Money.parse("-0.000001"), 86400),
                        new PlayerTrade(8, player, DIAMOND, Side.LONG, TradeKind.LIQUIDATION, 950.5, 2500, Money.parse("11.88125"),
                                Money.parse("-125.5"), 86500),
                        new PlayerTrade(10, player, DIAMOND, Side.LONG, TradeKind.ADL, 999.75, 700, Money.ZERO, Money.ZERO, 86600)),
                List.of(new Order(3, player, DIAMOND, Side.LONG, OrderType.LIMIT, 987.65, 1000, 10, Money.parse("98.8"),
                                OrderStatus.OPEN, 0, Money.ZERO, 86000, 0, CancelReason.NONE),
                        new Order(4, player, DIAMOND, Side.SHORT, OrderType.LIMIT, 1010, 500, 5, Money.ZERO,
                                OrderStatus.CANCELLED, 0, Money.ZERO, 86010, 86100, CancelReason.FILL_REJECTED),
                        new Order(9, player, DIAMOND, Side.SHORT, OrderType.LIMIT, 1020, 500, 5, Money.ZERO,
                                OrderStatus.CANCELLED, 0, Money.ZERO, 86011, 86500, CancelReason.LIQUIDATED),
                        new Order(5, player, DIAMOND, Side.LONG, OrderType.TAKE_PROFIT, 1100.5, 1000, 0, Money.ZERO,
                                OrderStatus.OPEN, 0, Money.ZERO, 86020, 0, CancelReason.NONE,
                                new OrderExtras(true, TriggerSource.MARK, 0, 0)),
                        new Order(6, player, DIAMOND, Side.LONG, OrderType.LIMIT, 950, 1000, 10, Money.parse("95.19"),
                                OrderStatus.OPEN, 0, Money.ZERO, 86030, 0, CancelReason.NONE,
                                new OrderExtras(false, TriggerSource.LAST, 1100.5, 900.25))),
                List.of(DIAMOND),
                List.of(new FundingRecord(5, player, DIAMOND, Side.SHORT, MarginMode.CROSS, 2500, -0.000123, 1034.56,
                        Money.parse("2586.4"), Money.parse("0.318127"), Money.parse("0.000002"), 28800)));
        TradingStatePayload.STREAM_CODEC.encode(buffer, state);
        helper.assertValueEqual(TradingStatePayload.STREAM_CODEC.decode(buffer), state, "交易状态载荷");

        OrderResultPayload result = new OrderResultPayload(false, "insufficient_balance", List.of("201.05025", "100.00"));
        OrderResultPayload.STREAM_CODEC.encode(buffer, result);
        helper.assertValueEqual(OrderResultPayload.STREAM_CODEC.decode(buffer), result, "下单结果载荷");

        AdlLightsPayload lights = new AdlLightsPayload(java.util.Map.of(DIAMOND, 4, "GUNPOWDERCRP", 1));
        AdlLightsPayload.STREAM_CODEC.encode(buffer, lights);
        helper.assertValueEqual(AdlLightsPayload.STREAM_CODEC.decode(buffer), lights, "ADL 指示灯载荷");
        helper.succeed();
    }

    static ServerPlayer trader(GameTestHelper helper, String name, String deposit) {
        ServerPlayer player = MockClient.join(helper, name);
        ServerAccounts.deposit(helper.getLevel().getServer(), player.getUUID(), Money.parse(deposit));
        return player;
    }

    /** 清理：平掉仓位、取走余额，让共用的账本不留测试痕迹（资金流向池子与玩家之间，总额不变）。 */
    static void finish(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        TradingBook book = ServerTrading.book();
        for (Order order : book.orders().open(player.getUUID())) {
            book.orders().cancel(player.getUUID(), order.id(), 0);
        }
        for (Position position : book.positions(player.getUUID())) {
            book.close(player.getUUID(), ServerMarket.spec(position.symbol()), ServerMarket.snapshot(position.symbol()),
                    position.lots());
        }
        // 共用的账本里不留全仓设置（平完仓位后才能切回）
        for (String symbol : ServerMarket.symbols()) {
            book.setMarginMode(player.getUUID(), symbol, MarginMode.ISOLATED);
        }
        Money left = ServerAccounts.balance(player.getUUID());
        if (left.isPositive()) {
            ServerAccounts.withdraw(server, player.getUUID(), left);
        }
        MockClient.leave(player);
    }

    static Money total(UUID player) {
        Money total = ServerTrading.poolBalance().plus(ServerAccounts.balance(player))
                .plus(ServerTrading.book().orders().frozen(player));
        for (Position position : ServerTrading.book().positions(player)) {
            total = total.plus(position.margin());
        }
        return total;
    }

    static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }

    /** 开多再平仓（小单，在第一档之内）：成交价是最新价格 ± 半个点差，保证金与手续费按规则划转，玩家与池子的总额不变，结果与状态同步给玩家。 */
    private static void marketOrderFillsAtLastPricePlusHalfSpreadAndConservesFunds(GameTestHelper helper) {
        ServerPlayer player = trader(helper, "OrderTrader1", "1000");
        try {
            UUID id = player.getUUID();
            ContractSpec spec = ServerMarket.spec(DIAMOND);
            MarketSnapshot snapshot = ServerMarket.snapshot(DIAMOND);
            Money poolBefore = ServerTrading.poolBalance();
            Money totalBefore = total(id);

            // 半档的数量（名义价值约 250 CRP），在第一档之内，没有滑点；档的大小随活市场的价格和配置文件的数量精度变化，所以现算
            long lots = Math.max(1, spec.liquidityCurve(snapshot.lastPrice()).levelLots() / 2);
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots, 10));

            Position position = ServerTrading.book().position(id, DIAMOND).orElseThrow();
            double expectedFill = snapshot.lastPrice() + spec.spread() / 2;
            helper.assertValueEqual(position.entryPrice(), expectedFill, "多头成交价 = 最新价格 + 半个点差");
            helper.assertValueEqual(position.lots(), lots, "仓位数量");
            helper.assertValueEqual(position.leverage(), 10, "杠杆");
            PlayerTrade opened = last(ServerTrading.book().trades(id));
            helper.assertTrue(ServerTrading.poolBalance().equals(poolBefore.plus(opened.fee())), "手续费归流动性池");
            helper.assertTrue(opened.fee().isPositive(), "开仓收取手续费");
            helper.assertValueEqual(total(id), totalBefore, "开仓后玩家、保证金、池子的总额不变");

            OrderResultPayload result = last(MockClient.received(player, OrderResultPayload.class));
            helper.assertTrue(result.success(), "成交应回报成功");
            helper.assertValueEqual(result.code(), "open_long", "结果代码");
            TradingStatePayload state = last(MockClient.received(player, TradingStatePayload.class));
            helper.assertValueEqual(state.positions(), List.of(position), "同步给玩家的仓位");
            helper.assertValueEqual(last(state.trades()), opened, "同步给玩家的成交历史含这一笔");
            helper.assertTrue(state.contracts().contains(spec.trading()), "同步合约的下单参数");
            AccountPayload account = last(MockClient.received(player, AccountPayload.class));
            helper.assertValueEqual(account.assets().walletBalance(), ServerAccounts.balance(id).plus(position.margin()),
                    "钱包余额含保证金");

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.CLOSE, DIAMOND, lots, 0));

            helper.assertTrue(ServerTrading.book().position(id, DIAMOND).isEmpty(), "全部平仓后没有仓位");
            PlayerTrade closed = last(ServerTrading.book().trades(id));
            helper.assertValueEqual(closed.kind(), TradeKind.CLOSE, "平仓成交");
            helper.assertValueEqual(closed.price(), snapshot.lastPrice() - spec.spread() / 2, "平多成交价 = 最新价格 − 半个点差");
            helper.assertValueEqual(total(id), totalBefore, "平仓后总额不变");
            helper.assertValueEqual(ServerTrading.book().trades(id).size(), 2, "开平仓各记一笔玩家成交");
        } finally {
            finish(player);
        }
        helper.succeed();
    }

    /** 开空成交价沿买盘逐档变差，低于买一价；部分平仓只减仓位、不改开仓均价；火药的数量精度是 0 位。 */
    private static void shortOrderFillsBelowLastPriceAndClosesPartially(GameTestHelper helper) {
        ServerPlayer player = trader(helper, "OrderTrader2", "5000");
        try {
            UUID id = player.getUUID();
            String gunpowder = ServerMarket.symbols().get(1);
            ContractSpec spec = ServerMarket.spec(gunpowder);
            MarketSnapshot snapshot = ServerMarket.snapshot(gunpowder);
            Money totalBefore = total(id);

            // 吃 10 档（名义价值约 5,000 CRP）：档的大小随活市场的价格和配置文件的数量精度变化，所以按当前曲线现算手数
            long lots = spec.liquidityCurve(snapshot.lastPrice()).levelLots() * 10;
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_SHORT, gunpowder, lots, 20));
            Position opened = ServerTrading.book().position(id, gunpowder).orElseThrow();
            helper.assertValueEqual(opened.side(), Side.SHORT, "空头");
            // 均价 = 同一条曲线按这个数量算出的卖出均价，低于买一价
            helper.assertValueEqual(opened.entryPrice(), spec.liquidityCurve(snapshot.lastPrice()).averageFillPrice(false, lots),
                    "空头成交价 = 沿买盘逐档卖出的均价");
            helper.assertTrue(opened.entryPrice() < snapshot.lastPrice() - spec.spread() / 2, "大单有滑点，比买一价更差");

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.CLOSE, gunpowder, lots / 4, 0));
            Position remaining = ServerTrading.book().position(id, gunpowder).orElseThrow();
            helper.assertValueEqual(remaining.lots(), lots - lots / 4, "部分平仓后剩余数量");
            helper.assertValueEqual(remaining.entryPrice(), opened.entryPrice(), "部分平仓不改变开仓均价");
            helper.assertTrue(remaining.margin().compareTo(opened.margin()) < 0, "保证金按比例退回");
            helper.assertValueEqual(total(id), totalBefore, "总额不变");
        } finally {
            finish(player);
        }
        helper.succeed();
    }

    /** 大单沿虚拟流动性曲线成交（均价比买一价差），成交后最新价格不变，随后另一位玩家的小单仍按买一价成交。 */
    private static void bigOrderSlipsAlongTheCurveButLeavesThePriceAlone(GameTestHelper helper) {
        ServerPlayer whale = trader(helper, "OrderWhale", "100000");
        ServerPlayer minnow = trader(helper, "OrderMinnow", "1000");
        try {
            String gunpowder = ServerMarket.symbols().get(1);
            ContractSpec spec = ServerMarket.spec(gunpowder);
            MarketSnapshot before = ServerMarket.snapshot(gunpowder);
            double touch = before.lastPrice() + spec.spread() / 2;
            // 吃 40 档（名义价值约 20,000 CRP，是参考单的 2 倍）：档的大小随活市场的价格和配置文件的数量精度变化，所以按当前曲线现算手数
            long lots = spec.liquidityCurve(before.lastPrice()).levelLots() * 40;
            double expected = spec.liquidityCurve(before.lastPrice()).averageFillPrice(true, lots);

            // 杠杆取低一点：缺省分档里高杠杆只允许小仓位（20x 只到 5,000 CRP 名义价值），2 万 CRP 的大单要用低杠杆才不触发仓位上限
            int leverage = Math.min(5, spec.maxLeverage());
            ServerTrading.handleOrder(whale, new OrderPayload(OrderPayload.Action.OPEN_LONG, gunpowder, lots, leverage));
            Position big = ServerTrading.book().position(whale.getUUID(), gunpowder).orElseThrow();
            helper.assertValueEqual(big.entryPrice(), expected, "大单成交价 = 曲线按这个数量算出的均价");
            // 名义价值超过参考单，平均滑点至少是合约标定的滑点（曲线单调）
            helper.assertTrue(big.entryPrice() > touch * (1 + spec.depthSlippage()), "大单滑点明显：" + big.entryPrice() + " vs " + touch);

            helper.assertValueEqual(ServerMarket.snapshot(gunpowder).lastPrice(), before.lastPrice(), "成交后最新价格不变");
            ServerTrading.handleOrder(minnow, new OrderPayload(OrderPayload.Action.OPEN_LONG, gunpowder, 1, 5));
            helper.assertValueEqual(ServerTrading.book().position(minnow.getUUID(), gunpowder).orElseThrow().entryPrice(), touch,
                    "别人的大单不影响这位玩家：仍按买一价成交");
        } finally {
            finish(whale);
            finish(minnow);
        }
        helper.succeed();
    }

    /** 余额不足、杠杆越界、没有仓位可平、没有这个合约：都回报原因，账户、池子、仓位不变。 */
    private static void rejectedOrdersExplainWhyAndChangeNothing(GameTestHelper helper) {
        ServerPlayer player = trader(helper, "OrderTrader3", "10");
        try {
            UUID id = player.getUUID();
            Money poolBefore = ServerTrading.poolBalance();

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, 1000, 10));
            OrderResultPayload insufficient = last(MockClient.received(player, OrderResultPayload.class));
            helper.assertFalse(insufficient.success(), "余额不足应拒单");
            helper.assertValueEqual(insufficient.code(), "insufficient_balance", "拒单原因");
            helper.assertTrue(insufficient.args().size() == 2, "说明需要多少、有多少：" + insufficient.args());

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, 1, 126));
            helper.assertValueEqual(last(MockClient.received(player, OrderResultPayload.class)).code(), "invalid_leverage",
                    "杠杆超过合约上限");
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.CLOSE, DIAMOND, 1, 0));
            helper.assertValueEqual(last(MockClient.received(player, OrderResultPayload.class)).code(), "no_position", "没有仓位可平");
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, "NOSUCHCRP", 1, 1));
            helper.assertValueEqual(last(MockClient.received(player, OrderResultPayload.class)).code(), "unknown_contract",
                    "没有这个合约");

            helper.assertValueEqual(ServerAccounts.balance(id), Money.parse("10"), "拒单不改变余额");
            helper.assertValueEqual(ServerTrading.poolBalance(), poolBefore, "拒单不改变池子");
            helper.assertTrue(ServerTrading.book().positions(id).isEmpty(), "拒单不产生仓位");
            helper.assertTrue(ServerTrading.book().trades(id).isEmpty(), "拒单不产生成交记录");
        } finally {
            finish(player);
        }
        helper.succeed();
    }

    /** 暂停市场时价格冻结，拒绝下单；恢复后可以下单。暂停必须在同一个 tick 内恢复，避免干扰其他测试。 */
    private static void ordersAreRejectedWhileTheMarketIsPaused(GameTestHelper helper) {
        ServerPlayer player = trader(helper, "OrderTrader4", "1000");
        try {
            ServerMarket.pause();
            try {
                ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, 1000, 10));
            } finally {
                ServerMarket.resume();
            }
            OrderResultPayload result = last(MockClient.received(player, OrderResultPayload.class));
            helper.assertValueEqual(result.code(), "market_paused", "暂停时拒单");
            helper.assertTrue(ServerTrading.book().positions(player.getUUID()).isEmpty(), "暂停时不应成交");
        } finally {
            finish(player);
        }
        helper.succeed();
    }

    /** 只有 OP 能用 {@code /creeper dev pool fund}；注资增加池子余额（共用账本里池子会多出这笔测试资金），非法数量被拒绝。 */
    private static void devPoolFundIsOpOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack op = server.createCommandSourceStack();
        CommandSourceStack regularPlayer = op.withPermission(PermissionSet.NO_PERMISSIONS);
        Money before = ServerTrading.poolBalance();
        try {
            helper.assertTrue(failsToParse(server, regularPlayer, "creeper dev pool fund 100"), "普通玩家无权给池子注资");
            helper.assertValueEqual(ServerTrading.poolBalance(), before, "普通玩家不应能注资");

            server.getCommands().performPrefixedCommand(op, "creeper dev pool fund 1234.567891");
            helper.assertValueEqual(ServerTrading.poolBalance(), before.plus(Money.parse("1234.567891")), "OP 注资（小数无误差）");

            for (String bad : List.of("0", "-5", "0.0000001", "abc")) {
                helper.assertValueEqual(server.getCommands().getDispatcher().execute("creeper dev pool fund " + bad, op), 0,
                        "非法数量 " + bad + " 应被拒绝");
            }
            helper.assertValueEqual(ServerTrading.poolBalance(), before.plus(Money.parse("1234.567891")), "非法数量不改变池子");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalStateException(e);
        }
        helper.succeed();
    }

    private static boolean failsToParse(MinecraftServer server, CommandSourceStack source, String command) {
        try {
            server.getCommands().getDispatcher().execute(command, source);
            return false;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return true;
        }
    }

    /** 仓位、成交历史和流动性池写进存档文件、用全新存储读出后，与存档前完全一致，并接着编号。 */
    private static void tradingStateSurvivesSaveAndReload(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        UUID alice = UUID.randomUUID();
        ContractSpec diamond = ContractSpec.defaults().get(0);
        MarketSnapshot at = new MarketSnapshot(100, DIAMOND, 1000, 1000, 1000, 0.0001, 0);
        Path dataFolder = Files.createTempDirectory("creepercapital-trading");
        try {
            AccountBook accounts = new AccountBook();
            accounts.deposit(alice, Money.parse("1000"));
            SavedDataStorage writer = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            TradingSavedData data = writer.computeIfAbsent(TradingSavedData.TYPE);
            TradingBook book = data.bind(accounts);
            helper.assertValueEqual(book.pool().balance(), Money.parse("1000000"), "新存档的池子初始 1,000,000 CRP");
            book.pool().fund(Money.parse("500.5"));
            helper.assertValueEqual(book.fund().balance(), Money.ZERO, "新存档的风险保障基金是空的");
            book.open(alice, diamond, at, Side.LONG, 2000, 10);
            book.open(alice, ContractSpec.defaults().get(1),
                    new MarketSnapshot(100, "GUNPOWDERCRP", 100, 100, 100, 0.0001, 0), Side.SHORT, 5, 5);
            book.close(alice, ContractSpec.defaults().get(1),
                    new MarketSnapshot(101, "GUNPOWDERCRP", 99, 99, 99, 0.0001, 0), 2);
            // 另一位玩家被强平，风险保障基金里有了钱（标记价格 903：亏掉几乎全部保证金，但没穿仓）
            UUID bob = UUID.randomUUID();
            accounts.deposit(bob, Money.parse("1000"));
            book.open(bob, diamond, at, Side.LONG, 2000, 10);
            var liquidations = book.checkLiquidations(player -> player.equals(bob),
                    symbol -> DIAMOND.equals(symbol) ? 903 : 99, symbol -> diamond.marginTiers(), 102);
            helper.assertValueEqual(liquidations.size(), 1, "这位玩家被强平");
            helper.assertTrue(book.fund().balance().isPositive(), "强平的剩余保证金进了风险保障基金");
            data.setDirty();
            writer.saveAndJoin();
            writer.close();

            SavedDataStorage reader = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            TradingSavedData reloaded = reader.get(TradingSavedData.TYPE);
            helper.assertTrue(reloaded != null, "存档里应当读得出交易账");
            TradingBook restored = reloaded.bind(accounts);
            helper.assertValueEqual(restored.pool().balance(), book.pool().balance(), "重新加载后的池子余额");
            helper.assertValueEqual(restored.positions(alice).size(), 2, "重新加载后的仓位数");
            helper.assertValueEqual(restored.position(alice, DIAMOND), book.position(alice, DIAMOND), "重新加载后的钻石仓位");
            helper.assertValueEqual(restored.position(alice, "GUNPOWDERCRP"), book.position(alice, "GUNPOWDERCRP"),
                    "重新加载后的火药仓位（部分平仓后）");
            helper.assertValueEqual(restored.trades(alice), book.trades(alice), "重新加载后的成交历史");
            helper.assertValueEqual(restored.fund().balance(), book.fund().balance(), "重新加载后的风险保障基金余额");
            helper.assertValueEqual(restored.trades(bob), book.trades(bob), "重新加载后的强平成交记录");
            helper.assertValueEqual(restored.trades(bob).get(1).kind(), TradeKind.LIQUIDATION, "强平记录的类型");
            TradeResult next = restored.close(alice, diamond, at, 2000);
            helper.assertValueEqual(((TradeResult.Filled) next).trade().id(), 6L, "恢复后接着编号");
            reader.close();
        } finally {
            deleteRecursively(dataFolder);
        }
        helper.succeed();
    }

    /** 配置里写了点差、最高杠杆、数量精度、流动性深度就按写的算；没写的用默认值（点差为起始价格的 0.05%，20 倍，3 位，滑点 0.1%）。 */
    private static void contractConfigReadsTradingParameters(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("creepercapital-trading-config");
        try {
            Path file = directory.resolve(ContractConfig.FILE_NAME);
            Files.writeString(file, """
                    {
                      "contracts": [
                        {
                          "symbol": "CUSTOMCRP",
                          "underlying": "minecraft:emerald",
                          "initial_price": 200.0,
                          "base_volume": 10.0,
                          "volatility": {
                            "daily_volatility": 0.06,
                            "jumps_per_day": 0.5,
                            "jump_min": 0.04,
                            "jump_max": 0.12,
                            "premium_bias": 0.002
                          },
                          "spread": 0.4,
                          "max_leverage": 75,
                          "quantity_decimals": 2,
                          "depth_slippage": 0.002,
                          "open_interest_ratio": 0.3
                        },
                        {
                          "symbol": "PLAINCRP",
                          "underlying": "minecraft:coal",
                          "initial_price": 80.0,
                          "base_volume": 10.0,
                          "volatility": {
                            "daily_volatility": 0.06,
                            "jumps_per_day": 0.5,
                            "jump_min": 0.04,
                            "jump_max": 0.12,
                            "premium_bias": 0.002
                          }
                        },
                        {
                          "symbol": "TIEREDCRP",
                          "underlying": "minecraft:iron_ingot",
                          "initial_price": 50.0,
                          "base_volume": 10.0,
                          "volatility": {
                            "daily_volatility": 0.06,
                            "jumps_per_day": 0.5,
                            "jump_min": 0.04,
                            "jump_max": 0.12,
                            "premium_bias": 0.002
                          },
                          "max_leverage": 10,
                          "maintenance_tiers": [
                            { "up_to": 1000.0, "max_leverage": 10, "maintenance_rate": 0.01 },
                            { "up_to": 5000.0, "max_leverage": 5, "maintenance_rate": 0.025 }
                          ]
                        }
                      ]
                    }
                    """, StandardCharsets.UTF_8);
            String original = Files.readString(file, StandardCharsets.UTF_8);

            List<ContractSpec> loaded = ContractConfig.load(file);

            // 没写分档的合约（含 #11 之前的旧配置文件）按最高杠杆推出缺省分档，而且不回写用户的文件
            helper.assertValueEqual(loaded.get(0).trading(),
                    new TradingParams("CUSTOMCRP", 0.4, 75, 2, 0.002, MarginTiers.standard(75)), "配置里写的交易参数");
            helper.assertValueEqual(loaded.get(1).trading(),
                    new TradingParams("PLAINCRP", 0.04, 20, 3, 0.001, MarginTiers.standard(20)), "没写的用默认值");
            // 持仓总量上限比例（#17）：写了的按配置，没写的（含旧配置文件）是 50%
            helper.assertValueEqual(loaded.get(0).openInterestRatio(), 0.3, "配置里写的持仓总量上限比例");
            helper.assertValueEqual(loaded.get(1).openInterestRatio(), ContractSpec.DEFAULT_OPEN_INTEREST_RATIO,
                    "没写的是缺省 50%");
            MarginTiers tiered = MarginTiers.of(List.of(
                    new MarginTiers.Definition(Money.parse("1000"), 10, 0.01),
                    new MarginTiers.Definition(Money.parse("5000"), 5, 0.025)));
            helper.assertValueEqual(loaded.get(2).marginTiers(), tiered, "配置里写的维持保证金分档");
            helper.assertValueEqual(loaded.get(2).marginTiers().maxLeverageAt(Money.parse("2000")), 5, "第二档的杠杆上限");
            helper.assertValueEqual(Files.readString(file, StandardCharsets.UTF_8), original, "读取不回写用户的配置文件");
            helper.assertValueEqual(ContractConfig.load(file).get(0), loaded.get(0), "再读一遍结果一致");
            Path generated = directory.resolve("generated.json");
            ContractConfig.load(generated);
            helper.assertTrue(Files.readString(generated, StandardCharsets.UTF_8).contains("\"max_leverage\": 125"),
                    "自动生成的配置文件里带着交易参数，方便调整");
            helper.assertTrue(Files.readString(generated, StandardCharsets.UTF_8).contains("maintenance_tiers"),
                    "自动生成的配置文件里带着维持保证金分档");
            helper.assertValueEqual(ContractConfig.load(generated), ContractSpec.defaults(), "生成的配置读回来与内置合约一致（含分档）");
            helper.assertTrue(Files.readString(generated, StandardCharsets.UTF_8).contains("\"depth_slippage\": 0.003"),
                    "自动生成的配置文件里带着流动性深度");
        } finally {
            deleteRecursively(directory);
        }
        helper.succeed();
    }

    /**
     * 保证金设置的下单链路：切全仓、全仓开仓、有仓位时切模式被拒、仓位的杠杆调整（保证金按新杠杆重算、总额不变）、
     * 平仓后再切回逐仓。价格和合约参数都取自活市场，不假设具体数值。
     */
    private static void marginOrdersSwitchModeAndAdjustLeverage(GameTestHelper helper) {
        ServerPlayer player = trader(helper, "MarginTrader1", "100000");
        try {
            UUID id = player.getUUID();
            ContractSpec spec = ServerMarket.spec(DIAMOND);
            long oneUnit = Quantities.parseLots("1", spec.quantityDecimals()).getAsLong();
            int leverage = Math.min(10, spec.maxLeverage());
            int lower = Math.max(1, leverage - 1);
            Money totalBefore = total(id);

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.SET_CROSS, DIAMOND, 0, 0));
            OrderResultPayload crossed = last(MockClient.received(player, OrderResultPayload.class));
            helper.assertValueEqual(crossed.code(), "set_cross", "切全仓的结果");
            helper.assertTrue(last(MockClient.received(player, TradingStatePayload.class)).crossSymbols().contains(DIAMOND),
                    "同步给玩家的状态里这个合约是全仓");

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, oneUnit, leverage));
            helper.assertValueEqual(last(MockClient.received(player, OrderResultPayload.class)).code(), "open_long", "全仓开多");
            Position opened = ServerTrading.book().position(id, DIAMOND).orElseThrow();
            helper.assertValueEqual(opened.mode(), MarginMode.CROSS, "新仓位沿用全仓");

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.SET_ISOLATED, DIAMOND, 0, 0));
            OrderResultPayload locked = last(MockClient.received(player, OrderResultPayload.class));
            helper.assertFalse(locked.success(), "有仓位时不能切换保证金模式");
            helper.assertValueEqual(locked.code(), "margin_mode_locked", "拒绝原因");
            helper.assertValueEqual(ServerTrading.book().marginMode(id, DIAMOND), MarginMode.CROSS, "拒绝后模式不变");

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.SET_LEVERAGE, DIAMOND, 0, lower));
            OrderResultPayload adjusted = last(MockClient.received(player, OrderResultPayload.class));
            helper.assertValueEqual(adjusted.code(), "set_leverage", "调整杠杆的结果");
            Position after = ServerTrading.book().position(id, DIAMOND).orElseThrow();
            helper.assertValueEqual(after.leverage(), lower, "仓位的杠杆已调整");
            helper.assertTrue(lower == leverage || after.margin().compareTo(opened.margin()) > 0, "降低杠杆需要更多保证金");
            helper.assertValueEqual(after.lots(), opened.lots(), "调整杠杆不改变数量");
            helper.assertValueEqual(total(id), totalBefore, "调整杠杆只在可用余额与保证金之间划转，玩家、保证金、池子的总额不变");

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.SET_LEVERAGE, DIAMOND, 0, spec.maxLeverage() + 1));
            helper.assertValueEqual(last(MockClient.received(player, OrderResultPayload.class)).code(), "invalid_leverage",
                    "杠杆超过合约上限被拒");
            helper.assertValueEqual(ServerTrading.book().position(id, DIAMOND).orElseThrow().leverage(), lower, "被拒后杠杆不变");

            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.CLOSE, DIAMOND, oneUnit, 0));
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.SET_ISOLATED, DIAMOND, 0, 0));
            helper.assertValueEqual(last(MockClient.received(player, OrderResultPayload.class)).code(), "set_isolated",
                    "平仓后可以切回逐仓");
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.SET_LEVERAGE, DIAMOND, 0, leverage));
            helper.assertValueEqual(last(MockClient.received(player, OrderResultPayload.class)).code(), "no_position",
                    "没有仓位时不能调整杠杆");
        } finally {
            finish(player);
        }
        helper.succeed();
    }

    /** 全仓仓位与全仓设置写进存档后原样读回；没有保证金字段的旧存档读出来都是逐仓、没有全仓设置。 */
    private static void marginModesSurviveSaveAndReloadAndOldSavesDefaultToIsolated(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        UUID alice = UUID.randomUUID();
        ContractSpec diamond = ContractSpec.defaults().get(0);
        MarketSnapshot at = new MarketSnapshot(100, DIAMOND, 1000, 1000, 1000, 0.0001, 0);
        Path dataFolder = Files.createTempDirectory("creepercapital-margin");
        try {
            AccountBook accounts = new AccountBook();
            accounts.deposit(alice, Money.parse("100000"));
            SavedDataStorage writer = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            TradingSavedData data = writer.computeIfAbsent(TradingSavedData.TYPE);
            TradingBook book = data.bind(accounts);
            helper.assertTrue(book.setMarginMode(alice, DIAMOND, MarginMode.CROSS) instanceof MarginResult.Applied, "切全仓");
            book.open(alice, diamond, at, Side.LONG, 2000, 10);
            helper.assertTrue(book.setMarginMode(alice, "GUNPOWDERCRP", MarginMode.CROSS) instanceof MarginResult.Applied,
                    "没有仓位的合约也能预先设为全仓");
            data.setDirty();
            writer.saveAndJoin();
            writer.close();

            SavedDataStorage reader = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            TradingBook restored = reader.get(TradingSavedData.TYPE).bind(accounts);
            helper.assertValueEqual(restored.position(alice, DIAMOND).orElseThrow().mode(), MarginMode.CROSS, "全仓仓位读回");
            helper.assertValueEqual(restored.position(alice, DIAMOND), book.position(alice, DIAMOND), "仓位整体一致");
            helper.assertValueEqual(restored.marginMode(alice, "GUNPOWDERCRP"), MarginMode.CROSS, "没有仓位的全仓设置读回");
            helper.assertValueEqual(restored.marginMode(alice, "OTHERCRP"), MarginMode.ISOLATED, "其他合约仍是缺省逐仓");
            reader.close();
        } finally {
            deleteRecursively(dataFolder);
        }

        // #11 之前的存档：仓位里没有 margin_mode，顶层没有 margin_modes
        String oldSave = """
                {"pool": 1000000000000, "last_trade_id": 0,
                 "positions": [{"player": %s,
                   "position": {"symbol": "DIAMONDCRP", "side": "LONG", "lots": 1000, "entry_price": 1000.0,
                                "leverage": 10, "margin": 100000000, "quantity_decimals": 3}}],
                 "trades": []}
                """.formatted(java.util.Arrays.toString(UUIDUtil.uuidToIntArray(alice)));
        TradingSavedData legacy = TradingSavedData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(oldSave)).getOrThrow();
        TradingBook legacyBook = legacy.bind(new AccountBook());
        helper.assertValueEqual(legacyBook.position(alice, DIAMOND).orElseThrow().mode(), MarginMode.ISOLATED, "旧存档的仓位是逐仓");
        helper.assertValueEqual(legacyBook.marginMode(alice, DIAMOND), MarginMode.ISOLATED, "旧存档没有全仓设置");
        helper.assertValueEqual(legacyBook.fund().balance(), Money.ZERO, "#12 之前的存档没有风险保障基金，读出来是 0");
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
