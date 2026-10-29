package io.github.djp440.creepercapital.gametest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.MarketTrade;
import io.github.djp440.creepercapital.core.trading.LiquidationEvent;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.network.LatestTradesPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.server.ServerAccounts;
import io.github.djp440.creepercapital.server.ServerLatestTrades;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerTrading;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 「最新成交」的 GameTest：玩家成交全服广播（含标出玩家名）、新玩家登录补发最近成交、模拟成交随行情步发出且不动任何余额、
 * 服务端缓冲只保留最近若干笔。所有测试共用同一个活市场与账本，所以只断言自己造出的那几笔。
 */
final class LatestTradesGameTests {
    private static final String DIAMOND = "DIAMONDCRP";

    private LatestTradesGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("latest_trades_payload_round_trips", LatestTradesGameTests::payloadRoundTrips);
        function.accept("player_trade_is_broadcast_to_every_online_player_with_the_name",
                LatestTradesGameTests::playerTradeIsBroadcastToEveryOnlinePlayer);
        function.accept("player_joining_later_receives_the_recent_trades", LatestTradesGameTests::playerJoiningLaterReceivesRecentTrades);
        function.accept("simulated_trades_are_broadcast_and_leave_every_balance_alone",
                LatestTradesGameTests::simulatedTradesAreBroadcastAndLeaveEveryBalanceAlone);
        function.accept("server_keeps_only_the_most_recent_trades_per_contract", LatestTradesGameTests::serverKeepsOnlyTheMostRecentTrades);
        function.accept("liquidation_reaches_another_players_tape", LatestTradesGameTests::liquidationReachesAnotherPlayersTape);
        function.accept("limit_fill_reaches_another_players_tape", LatestTradesGameTests::limitFillReachesAnotherPlayersTape);
    }

    private static ContractSpec spec() {
        return ServerMarket.spec(DIAMOND);
    }

    /** 半档的数量：在第一档之内，名义价值不大，不会碰到分档的仓位上限。 */
    private static long lots() {
        return Math.max(1, spec().liquidityCurve(ServerMarket.snapshot(DIAMOND).lastPrice()).levelLots() / 2);
    }

    /**
     * 强平的成交也走成交监听：另一名在线玩家的最新成交里出现带玩家名的强平（逐仓多头被强平是卖出，按触发强平的标记价格、全部数量）。
     * 直接调强平阶段并限定只检查本测试的玩家，免得注入的极端价格误伤共用账本里别的测试。
     */
    private static void liquidationReachesAnotherPlayersTape(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer trader = TradingGameTests.trader(helper, "TapeLiq1", "100000");
        ServerPlayer watcher = MockClient.join(helper, "TapeLiqWatcher");
        try {
            UUID id = trader.getUUID();
            ServerTrading.handleOrder(trader, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(),
                    Math.min(10, spec().maxLeverage())));
            Position position = ServerTrading.book().position(id, DIAMOND).orElseThrow();
            // 标记价格腰斩：10 倍以内的杠杆都远远越过强平线
            double mark = position.entryPrice() * 0.5;
            long seconds = ServerMarket.snapshot(DIAMOND).time().totalSeconds();

            List<LiquidationEvent> events = ServerTrading.liquidate(server, seconds,
                    symbol -> DIAMOND.equals(symbol) ? mark : Double.NaN, other -> other.equals(id), new LinkedHashSet<>());

            helper.assertValueEqual(events.size(), 1, "本测试的玩家被强平");
            List<MarketTrade> seen = playerTradesOf(watcher, "TapeLiq1");
            helper.assertValueEqual(seen.size(), 2, "旁观玩家看到开仓和强平两笔");
            MarketTrade liquidation = seen.get(1);
            helper.assertTrue(liquidation.isPlayer() && !liquidation.buy(), "强平多头是卖出，且标出玩家名");
            helper.assertValueEqual(liquidation.price(), mark, "强平按标记价格成交");
            helper.assertValueEqual(liquidation.lots(), position.lots(), "强平全部数量");
            helper.assertValueEqual(liquidation.symbol(), DIAMOND, "交易代码");
        } finally {
            TradingGameTests.finish(trader);
            MockClient.leave(watcher);
        }
        helper.succeed();
    }

    /**
     * 限价挂单在行情步成交也走成交监听：玩家挂一张略低于市价的买单后下线，价格跳空穿过委托价，
     * 另一名在线玩家的最新成交里出现这笔 maker 成交（买入，按委托价）。
     */
    private static void limitFillReachesAnotherPlayersTape(GameTestHelper helper) {
        ServerPlayer trader = TradingGameTests.trader(helper, "TapeLimit1", "1000");
        ServerPlayer watcher = MockClient.join(helper, "TapeLimitWatcher");
        UUID id = trader.getUUID();
        double price = Math.floor(ServerMarket.snapshot(DIAMOND).lastPrice() * 0.99);
        // 低杠杆：成交后同一个行情步里价格已经大跌，高杠杆的多头会被强平，而这里要验证的是限价成交
        ServerTrading.handleOrder(trader, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, 1000L, Math.min(2, spec().maxLeverage()),
                OrderPayload.Execution.LIMIT, price));
        helper.assertValueEqual(ServerTrading.book().orders().open(id).size(), 1, "挂出一张限价买单");
        long placedLots = ServerTrading.book().orders().open(id).get(0).lots();
        ServerMarket.queueJump(DIAMOND, -0.1);

        helper.succeedWhen(() -> {
            helper.assertTrue(ServerTrading.book().orders().open(id).isEmpty(), "委托应已在行情步成交");
            List<MarketTrade> seen = playerTradesOf(watcher, "TapeLimit1");
            helper.assertValueEqual(seen.size(), 1, "旁观玩家看到这笔限价成交");
            helper.assertTrue(seen.get(0).isPlayer() && seen.get(0).buy(), "限价买入是买入，且标出玩家名");
            helper.assertValueEqual(seen.get(0).price(), price, "按委托价成交");
            helper.assertValueEqual(seen.get(0).lots(), placedLots, "数量");
            // 断言全部通过后才收尾：断言失败会在下个 tick 重试，不能提前把旁观玩家和仓位清掉
            MockClient.leave(watcher);
            TradingGameTests.finish(trader);
        });
    }

    private static void payloadRoundTrips(GameTestHelper helper) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        LatestTradesPayload payload = new LatestTradesPayload(List.of(
                new MarketTrade(DIAMOND, 86_400, true, 1000.1875, 1234, ""),
                new MarketTrade(DIAMOND, 86_406, false, 999.8125, 5, "Alex")));

        LatestTradesPayload.STREAM_CODEC.encode(buffer, payload);

        helper.assertValueEqual(LatestTradesPayload.STREAM_CODEC.decode(buffer), payload, "最新成交载荷");
        helper.succeed();
    }

    /** 下单、平仓都走成交监听：另一名在线玩家收到带玩家名的高亮成交，方向分别是买入、卖出。 */
    private static void playerTradeIsBroadcastToEveryOnlinePlayer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer trader = MockClient.join(helper, "TapeTrader1");
        ServerPlayer watcher = MockClient.join(helper, "TapeWatcher1");
        ServerAccounts.deposit(server, trader.getUUID(), Money.parse("1000"));
        try {
            ServerTrading.handleOrder(trader, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, 100, 10));
            ServerTrading.handleOrder(trader, new OrderPayload(OrderPayload.Action.CLOSE, DIAMOND, 100, 0));

            List<MarketTrade> seen = playerTradesOf(watcher, "TapeTrader1");
            helper.assertValueEqual(seen.size(), 2, "旁观玩家看到交易者的两笔成交");
            helper.assertTrue(seen.get(0).isPlayer() && seen.get(0).buy(), "开多是买入，且标出玩家名");
            helper.assertTrue(!seen.get(1).buy(), "平多是卖出");
            helper.assertValueEqual(seen.get(0).symbol(), DIAMOND, "交易代码");
            helper.assertValueEqual(seen.get(0).lots(), 100L, "数量");
            helper.assertValueEqual(seen.get(0).price(),
                    ServerTrading.book().trades(trader.getUUID()).get(0).price(), "成交价与玩家自己的成交记录一致");
            helper.assertValueEqual(playerTradesOf(trader, "TapeTrader1").size(), 2, "交易者自己也收到");
        } finally {
            TradingGameTests.finish(trader);
            MockClient.leave(watcher);
        }
        helper.succeed();
    }

    private static List<MarketTrade> playerTradesOf(ServerPlayer receiver, String name) {
        List<MarketTrade> trades = new ArrayList<>();
        for (LatestTradesPayload payload : MockClient.received(receiver, LatestTradesPayload.class)) {
            payload.trades().stream().filter(trade -> trade.player().equals(name)).forEach(trades::add);
        }
        return trades;
    }

    /** 刚登录的玩家一次性收到服务端缓冲里的最近成交，其中有之前的玩家成交。 */
    private static void playerJoiningLaterReceivesRecentTrades(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer trader = MockClient.join(helper, "TapeTrader2");
        ServerAccounts.deposit(server, trader.getUUID(), Money.parse("1000"));
        ServerPlayer latecomer = null;
        try {
            ServerTrading.handleOrder(trader, new OrderPayload(OrderPayload.Action.OPEN_SHORT, DIAMOND, 50, 10));
            helper.assertTrue(ServerLatestTrades.recent(DIAMOND).stream().anyMatch(trade -> trade.player().equals("TapeTrader2")),
                    "服务端缓冲里有这笔玩家成交");

            latecomer = MockClient.join(helper, "TapeLatecomer");

            List<MarketTrade> seen = playerTradesOf(latecomer, "TapeTrader2");
            helper.assertValueEqual(seen.size(), 1, "晚进服的玩家登录时补发了这笔成交");
            helper.assertTrue(!seen.get(0).buy(), "开空是卖出");
        } finally {
            TradingGameTests.finish(trader);
            if (latecomer != null) {
                MockClient.leave(latecomer);
            }
        }
        helper.succeed();
    }

    /**
     * 模拟成交随行情步发给所有在线玩家，数量之和等于该步成交量；生成与广播前后，所有账户余额、仓位保证金、流动性池都不变。
     * 用一个远在未来的虚构行情步驱动，不动活市场自己的推进。
     */
    private static void simulatedTradesAreBroadcastAndLeaveEveryBalanceAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer watcher = MockClient.join(helper, "TapeWatcher3");
        ServerAccounts.deposit(server, watcher.getUUID(), Money.parse("500"));
        try {
            ServerTrading.handleOrder(watcher, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, 100, 10));
            Money poolBefore = ServerTrading.poolBalance();
            Money balanceBefore = ServerAccounts.balance(watcher.getUUID());
            List<Position> positionsBefore = ServerTrading.book().positions(watcher.getUUID());
            MarketSnapshot live = ServerMarket.snapshot(DIAMOND);
            long step = 5_000_000;
            MarketSnapshot fake = new MarketSnapshot(step, DIAMOND, live.indexPrice(), live.lastPrice(), live.markPrice(), 0, 4.0);
            int received = MockClient.received(watcher, LatestTradesPayload.class).size();

            ServerLatestTrades.onMarketStep(server, List.of(fake));

            List<LatestTradesPayload> payloads = MockClient.received(watcher, LatestTradesPayload.class);
            helper.assertValueEqual(payloads.size(), received + 1, "一个行情步发一个包");
            List<MarketTrade> batch = payloads.get(payloads.size() - 1).trades();
            helper.assertValueEqual(batch.stream().mapToLong(MarketTrade::lots).sum(), 4000L, "数量之和 = 成交量 4.0 个 = 4000 手");
            helper.assertTrue(batch.stream().noneMatch(MarketTrade::isPlayer), "模拟成交没有玩家名");
            helper.assertValueEqual(ServerTrading.poolBalance(), poolBefore, "流动性池余额不变");
            helper.assertValueEqual(ServerAccounts.balance(watcher.getUUID()), balanceBefore, "玩家可用余额不变");
            helper.assertValueEqual(ServerTrading.book().positions(watcher.getUUID()), positionsBefore, "仓位与保证金不变");
        } finally {
            finish(watcher);
        }
        helper.succeed();
    }

    /** 服务端的环形缓冲每个合约最多 50 笔，且保留的是最新的。 */
    private static void serverKeepsOnlyTheMostRecentTrades(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MarketSnapshot live = ServerMarket.snapshot(DIAMOND);
        MarketSnapshot last = null;
        for (long step = 6_000_000; step < 6_000_040; step++) {
            last = new MarketSnapshot(step, DIAMOND, live.indexPrice(), live.lastPrice(), live.markPrice(), 0, 4.0);
            ServerLatestTrades.onMarketStep(server, List.of(last));
        }

        List<MarketTrade> recent = ServerLatestTrades.recent(DIAMOND);
        helper.assertValueEqual(recent.size(), ServerLatestTrades.BUFFER_PER_CONTRACT, "缓冲上限");
        helper.assertValueEqual(recent.get(recent.size() - 1).marketSeconds(), last.time().totalSeconds(), "最后一笔来自最新的行情步");
        helper.succeed();
    }

    /** 清理：平掉仓位、取走余额（资金流向池子与玩家之间，总额不变），玩家离线。 */
    private static void finish(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        TradingBook book = ServerTrading.book();
        UUID id = player.getUUID();
        for (Position position : book.positions(id)) {
            book.close(id, ServerMarket.spec(position.symbol()), ServerMarket.snapshot(position.symbol()), position.lots());
        }
        Money left = ServerAccounts.balance(id);
        if (left.isPositive()) {
            ServerAccounts.withdraw(server, id, left);
        }
        MockClient.leave(player);
    }
}
