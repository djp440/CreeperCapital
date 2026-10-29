package io.github.djp440.creepercapital.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.trading.FundingRecord;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.EventType;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.Summary;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderStatus;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.server.OfflineSummarySavedData;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerOfflineSummary;
import io.github.djp440.creepercapital.server.ServerTrading;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * 离线事件汇总的 GameTest：玩家离线时的强平、资金费、委托成交、止盈止损触发记进汇总，金额与明细（成交历史、资金费记录、历史委托）
 * 一致；上线时聊天栏收到一条汇总、之后不再通知；在线玩家的事件不进汇总；未送达的汇总随存档往返。
 * 汇总的累加、取走和金额口径的细节在 core 的 {@code OfflineSummariesTest}。
 * <p>
 * 活市场不能被推进，行情步用「合成快照」直接交给行情步钩子（见 {@code FundingGameTests}）；强平用 2 倍价格的假快照开仓，
 * 活市场的真实价格只有开仓价的一半，同一个行情步的强平阶段必然触发。所有断言按当前合约现算，不假设内置合约参数。
 */
final class OfflineSummaryGameTests {
    private static final String DIAMOND = "DIAMONDCRP";
    private static final String KEY_PREFIX = "message.creepercapital.offline_summary.";
    /** 够大的结算点：4800 的整数倍，远超活市场的行情步。 */
    private static final long SETTLEMENT_STEP = 4800L * 1_000_000;

    private OfflineSummaryGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("offline_liquidation_and_funding_are_summarized_and_delivered_once",
                OfflineSummaryGameTests::offlineLiquidationAndFundingAreSummarizedAndDeliveredOnce);
        function.accept("online_events_are_not_summarized", OfflineSummaryGameTests::onlineEventsAreNotSummarized);
        function.accept("offline_order_fills_and_take_profit_are_summarized",
                OfflineSummaryGameTests::offlineOrderFillsAndTakeProfitAreSummarized);
        function.accept("undelivered_summary_survives_save_and_reload", helper -> {
            try {
                undeliveredSummarySurvivesSaveAndReload(helper);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static ContractSpec spec() {
        return ServerMarket.spec(DIAMOND);
    }

    /** 半档的数量，在第一档之内，没有滑点。 */
    private static long lots() {
        return Math.max(1, spec().liquidityCurve(ServerMarket.snapshot(DIAMOND).lastPrice()).levelLots() / 2);
    }

    /** 缺省分档下高杠杆只允许小仓位，所以用低杠杆。 */
    private static int leverage() {
        return Math.min(5, spec().maxLeverage());
    }

    private static OfflineSummaries summaries() {
        return ServerOfflineSummary.summaries();
    }

    /** 合成的行情步：价格全部取给定值、费率给定、成交量 0。 */
    private static MarketSnapshot at(long step, double price, double rate) {
        return new MarketSnapshot(step, DIAMOND, price, price, price, rate, 0);
    }

    /** 活市场此刻的快照换一个行情步编号和费率（价格不变）。 */
    private static MarketSnapshot atLive(long step, double rate) {
        MarketSnapshot live = ServerMarket.snapshot(DIAMOND);
        return new MarketSnapshot(step, DIAMOND, live.indexPrice(), live.lastPrice(), live.markPrice(), rate, 0);
    }

    /** 以 2 倍于活市场的价格开仓：活市场的真实价格只有开仓价的一半，下一次强平检查必然触发。 */
    private static void openAtDoublePrice(GameTestHelper helper, UUID player, long lots, int leverage) {
        MarketSnapshot live = ServerMarket.snapshot(DIAMOND);
        MarketSnapshot fake = new MarketSnapshot(live.step(), DIAMOND, live.indexPrice() * 2, live.lastPrice() * 2,
                live.markPrice() * 2, live.fundingRate(), 0);
        ServerTrading.book().open(player, spec(), fake, Side.LONG, lots, leverage);
        helper.assertTrue(ServerTrading.book().position(player, DIAMOND).isPresent(), "假快照开仓应成功");
    }

    /** 测试这一侧独立写的资金费期望：名义价值（标记价格 × 数量，取整到 1e-6）× 费率，四舍五入到 1e-6。 */
    private static Money expectedFunding(double mark, long lots, double rate) {
        BigDecimal notional = BigDecimal.valueOf(mark).multiply(BigDecimal.valueOf(lots))
                .movePointLeft(spec().quantityDecimals()).setScale(6, RoundingMode.HALF_UP);
        return Money.ofUnits(notional.movePointRight(6).multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.HALF_UP)
                .longValueExact());
    }

    /** 明细一侧：资金费记录的带符号金额合计。 */
    private static Money fundingDetails(UUID player) {
        Money total = Money.ZERO;
        for (FundingRecord record : ServerTrading.book().fundingRecords(player)) {
            total = total.plus(record.amount());
        }
        return total;
    }

    /** 明细一侧：成交历史里某类成交的已实现盈亏合计。 */
    private static Money tradeDetails(UUID player, TradeKind kind) {
        Money total = Money.ZERO;
        for (PlayerTrade trade : ServerTrading.book().trades(player)) {
            if (trade.kind() == kind) {
                total = total.plus(trade.realizedPnl());
            }
        }
        return total;
    }

    /** 明细一侧：历史委托里已成交限价委托的成交额（委托价 × 数量）合计。 */
    private static Money filledLimitTurnover(UUID player) {
        BigDecimal total = BigDecimal.ZERO;
        for (Order order : ServerTrading.book().orders().history(player)) {
            if (order.type() == OrderType.LIMIT && order.status() == OrderStatus.FILLED) {
                total = total.add(BigDecimal.valueOf(order.price()).multiply(BigDecimal.valueOf(order.lots()))
                        .movePointLeft(spec().quantityDecimals()));
            }
        }
        return Money.parse(total.setScale(6, RoundingMode.HALF_UP).toPlainString());
    }

    /** 一条聊天消息里的全部可翻译片段（汇总通知是一条消息，行之间是换行）。 */
    private static List<TranslatableContents> parts(Component message) {
        List<TranslatableContents> parts = new ArrayList<>();
        collect(message, parts);
        return parts;
    }

    private static void collect(Component component, List<TranslatableContents> parts) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            parts.add(translatable);
        }
        component.getSiblings().forEach(sibling -> collect(sibling, parts));
    }

    /** 玩家收到的汇总通知（以标题键识别），按先后。 */
    private static List<List<TranslatableContents>> summaryMessages(ServerPlayer player) {
        return MockClient.systemMessages(player).stream().map(OfflineSummaryGameTests::parts)
                .filter(parts -> !parts.isEmpty() && parts.get(0).getKey().equals(KEY_PREFIX + "header")).toList();
    }

    private static List<String> keys(List<TranslatableContents> parts) {
        return parts.stream().map(TranslatableContents::getKey).toList();
    }

    private static List<String> args(TranslatableContents part) {
        return java.util.Arrays.stream(part.getArgs()).map(String::valueOf).toList();
    }

    /**
     * 玩家离线时，结算点那一步先资金费、后强平：汇总里是 1 次资金费和 1 次强平，金额等于资金费记录与成交历史里强平那一行；
     * 玩家上线时聊天栏收到恰好一条汇总，清空后再上线不再通知。
     */
    private static void offlineLiquidationAndFundingAreSummarizedAndDeliveredOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TradingGameTests.trader(helper, "OfflineSumA", "100000");
        UUID id = player.getUUID();
        long lots = lots();
        ServerPlayer rejoined = null;
        try {
            openAtDoublePrice(helper, id, lots, leverage());
            MockClient.leave(player);
            helper.assertFalse(summaries().hasPending(id), "刚下线，汇总是空的");

            MarketSnapshot settlement = atLive(SETTLEMENT_STEP, 0.0001);
            ServerTrading.onMarketStep(server, List.of(settlement));

            Money expectedFunding = expectedFunding(settlement.markPrice(), lots, 0.0001);
            helper.assertTrue(expectedFunding.isPositive(), "测试用的仓位应当产生非零资金费：" + expectedFunding);
            helper.assertTrue(ServerTrading.book().position(id, DIAMOND).isEmpty(), "离线玩家被强平");
            Summary pending = summaries().pending(id);
            helper.assertValueEqual(pending.count(EventType.FUNDING), 1L, "离线期间 1 次资金费");
            helper.assertValueEqual(pending.amount(EventType.FUNDING), Money.ZERO.minus(expectedFunding), "多头付 名义价值 × 费率");
            helper.assertValueEqual(pending.amount(EventType.FUNDING), fundingDetails(id), "汇总 = 资金费记录合计");
            helper.assertValueEqual(pending.count(EventType.LIQUIDATION), 1L, "离线期间 1 个仓位被强平");
            helper.assertTrue(pending.amount(EventType.LIQUIDATION).units() < 0, "强平的已实现盈亏是亏损");
            helper.assertValueEqual(pending.amount(EventType.LIQUIDATION), tradeDetails(id, TradeKind.LIQUIDATION),
                    "汇总 = 成交历史里强平行的已实现盈亏合计");
            helper.assertValueEqual(pending.count(EventType.ORDER_FILL) + pending.count(EventType.TRIGGER), 0L, "没有别的事件");

            // 上线：聊天栏恰好一条汇总，行依次是 强平、资金费，最后是去哪看明细
            rejoined = MockClient.join(helper, "OfflineSumA", id);
            List<List<TranslatableContents>> messages = summaryMessages(rejoined);
            helper.assertValueEqual(messages.size(), 1, "上线时只通知一次");
            List<TranslatableContents> message = messages.get(0);
            helper.assertValueEqual(keys(message), List.of(KEY_PREFIX + "header", KEY_PREFIX + "liquidation",
                    KEY_PREFIX + "funding", KEY_PREFIX + "footer"), "标题、强平、资金费、明细提示");
            helper.assertValueEqual(args(message.get(1)), List.of("1", tradeDetails(id, TradeKind.LIQUIDATION).toDisplayString()),
                    "强平行的次数与金额");
            helper.assertValueEqual(args(message.get(2)),
                    List.of("1", Money.ZERO.minus(expectedFunding).toDisplayString()), "资金费行的次数与净收付");
            helper.assertFalse(summaries().hasPending(id), "送达后汇总已清空");

            // 再次离线、上线：没有新事件就不再通知
            MockClient.leave(rejoined);
            rejoined = MockClient.join(helper, "OfflineSumA", id);
            helper.assertTrue(summaryMessages(rejoined).isEmpty(), "汇总为空时上线不发消息");
        } finally {
            if (rejoined != null) {
                TradingGameTests.finish(rejoined);
            }
        }
        helper.succeed();
    }

    /** 玩家在线时发生的资金费和强平已经实时通知过，不进汇总，之后上线也不会重复通知。 */
    private static void onlineEventsAreNotSummarized(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TradingGameTests.trader(helper, "OfflineSumB", "100000");
        UUID id = player.getUUID();
        ServerPlayer rejoined = null;
        try {
            openAtDoublePrice(helper, id, lots(), leverage());

            ServerTrading.onMarketStep(server, List.of(atLive(SETTLEMENT_STEP, 0.0001)));

            helper.assertValueEqual(ServerTrading.book().fundingRecords(id).size(), 1, "在线时也结算了资金费");
            helper.assertTrue(ServerTrading.book().position(id, DIAMOND).isEmpty(), "在线时也被强平");
            List<OrderResultPayload> notices = MockClient.received(player, OrderResultPayload.class);
            helper.assertTrue(notices.stream().anyMatch(n -> n.code().equals("funding_paid")), "在线玩家实时收到资金费提示");
            helper.assertTrue(notices.stream().anyMatch(n -> n.code().equals("liquidated")), "在线玩家实时收到强平提示");
            helper.assertFalse(summaries().hasPending(id), "在线期间的事件不进汇总");

            MockClient.leave(player);
            rejoined = MockClient.join(helper, "OfflineSumB", id);
            helper.assertTrue(summaryMessages(rejoined).isEmpty(), "上线时没有汇总可发");
        } finally {
            TradingGameTests.finish(rejoined != null ? rejoined : player);
        }
        helper.succeed();
    }

    /**
     * 玩家离线时，挂着的限价买单被价格下穿成交、止盈单被价格上穿触发：汇总里各 1 笔，成交额等于历史委托的委托价 × 数量，
     * 止盈的已实现盈亏等于成交历史里平仓那一行；上线时汇总通知里有对应两行。
     * 合成的价格只偏离 5%，尽量少影响共用账本里别的测试的挂单。
     */
    private static void offlineOrderFillsAndTakeProfitAreSummarized(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TradingGameTests.trader(helper, "OfflineSumC", "100000");
        UUID id = player.getUUID();
        long lots = lots();
        int leverage = leverage();
        double last = ServerMarket.snapshot(DIAMOND).lastPrice();
        double takeProfit = Math.round(last * 1.03 * 100) / 100.0;
        double limit = Math.floor(last * 0.97 * 100) / 100.0;
        ServerPlayer rejoined = null;
        try {
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots, leverage,
                    OrderPayload.Execution.MARKET, 0, takeProfit, 0, TriggerSource.LAST, false));
            helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(), "open_long",
                    "市价开多（附带止盈）");
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots, leverage,
                    OrderPayload.Execution.LIMIT, limit));
            helper.assertValueEqual(ServerTrading.book().orders().open(id).size(), 2, "一张止盈、一张限价买单挂着");
            MockClient.leave(player);

            // 价格下穿到限价以下：限价买单成交；再上穿到止盈价以上：止盈触发
            ServerTrading.onMarketStep(server, List.of(at(SETTLEMENT_STEP + 1, last * 0.95, 0)));
            helper.assertValueEqual(summaries().pending(id).count(EventType.ORDER_FILL), 1L, "离线时 1 张限价委托成交");
            ServerTrading.onMarketStep(server, List.of(at(SETTLEMENT_STEP + 2, last * 1.05, 0)));

            Summary pending = summaries().pending(id);
            helper.assertValueEqual(pending.count(EventType.TRIGGER), 1L, "离线时 1 次止盈触发");
            helper.assertValueEqual(pending.amount(EventType.ORDER_FILL), filledLimitTurnover(id), "汇总 = 历史委托的成交额合计");
            BigDecimal turnover = BigDecimal.valueOf(limit).multiply(BigDecimal.valueOf(lots))
                    .movePointLeft(spec().quantityDecimals());
            helper.assertValueEqual(pending.amount(EventType.ORDER_FILL),
                    Money.parse(turnover.setScale(6, RoundingMode.HALF_UP).toPlainString()), "成交额 = 委托价 × 数量");
            helper.assertTrue(pending.amount(EventType.TRIGGER).isPositive(), "止盈是盈利");
            helper.assertValueEqual(pending.amount(EventType.TRIGGER), tradeDetails(id, TradeKind.CLOSE),
                    "汇总 = 成交历史里平仓行的已实现盈亏合计");
            helper.assertValueEqual(pending.count(EventType.FUNDING) + pending.count(EventType.LIQUIDATION), 0L, "没有别的事件");

            rejoined = MockClient.join(helper, "OfflineSumC", id);
            List<List<TranslatableContents>> messages = summaryMessages(rejoined);
            helper.assertValueEqual(messages.size(), 1, "上线时只通知一次");
            List<TranslatableContents> message = messages.get(0);
            helper.assertValueEqual(keys(message), List.of(KEY_PREFIX + "header", KEY_PREFIX + "order_fill",
                    KEY_PREFIX + "trigger", KEY_PREFIX + "footer"), "标题、委托成交、止盈止损触发、明细提示");
            helper.assertValueEqual(args(message.get(1)).get(0), "1", "委托成交笔数");
            helper.assertValueEqual(args(message.get(2)),
                    List.of("1", "+" + tradeDetails(id, TradeKind.CLOSE).toDisplayString()), "止盈止损行：笔数与带正号的盈亏");
        } finally {
            TradingGameTests.finish(rejoined != null ? rejoined : player);
        }
        helper.succeed();
    }

    /** 未送达的汇总随存档往返，恢复后还能接着累加、玩家上线取走后不再出现；没有这个存档文件（旧世界）就是空的。 */
    private static void undeliveredSummarySurvivesSaveAndReload(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Path dataFolder = Files.createTempDirectory("creepercapital-offline-summary");
        try {
            SavedDataStorage writer = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            OfflineSummarySavedData data = writer.computeIfAbsent(OfflineSummarySavedData.TYPE);
            helper.assertTrue(data.summaries().state().isEmpty(), "没有存档文件的旧世界，汇总是空的");
            data.summaries().record(alice, EventType.LIQUIDATION, DIAMOND, Money.parse("-192.9"), 600);
            data.summaries().record(alice, EventType.LIQUIDATION, DIAMOND, Money.parse("-7.1"), 700);
            data.summaries().record(alice, EventType.FUNDING, "GUNPOWDERCRP", Money.parse("0.000001"), 100);
            data.summaries().record(bob, EventType.TRIGGER, DIAMOND, Money.parse("199"), 720);
            data.setDirty();
            writer.saveAndJoin();
            writer.close();

            SavedDataStorage reader = new SavedDataStorage(level, dataFolder, DataFixers.getDataFixer(), level.registryAccess());
            OfflineSummaries restored = reader.get(OfflineSummarySavedData.TYPE).summaries();
            helper.assertValueEqual(restored.state(), data.summaries().state(), "重新加载后的汇总");
            Summary summary = restored.pending(alice);
            helper.assertValueEqual(summary.count(EventType.LIQUIDATION), 2L, "强平次数");
            helper.assertValueEqual(summary.amount(EventType.LIQUIDATION), Money.parse("-200"), "强平金额合计");
            helper.assertValueEqual(summary.amount(EventType.FUNDING), Money.ofUnits(1), "最小金额单位不丢");
            helper.assertValueEqual(summary.entries().get(0).firstSeconds(), 600L, "最早时间");
            helper.assertValueEqual(summary.entries().get(0).lastSeconds(), 700L, "最近时间");
            restored.record(alice, EventType.LIQUIDATION, DIAMOND, Money.parse("-1"), 800);
            helper.assertValueEqual(restored.pending(alice).count(EventType.LIQUIDATION), 3L, "恢复后接着累加");
            restored.take(bob);
            helper.assertValueEqual(restored.state().keySet(), java.util.Set.of(alice), "取走的玩家不在状态里");
            reader.close();
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
