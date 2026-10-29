package io.github.djp440.creepercapital.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.MarketTrade;
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
            finish(trader);
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
            finish(trader);
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
