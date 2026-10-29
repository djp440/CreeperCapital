package io.github.djp440.creepercapital.gametest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.trading.AdlRanking;
import io.github.djp440.creepercapital.core.trading.AutoDeleverageEvent;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.EventType;
import io.github.djp440.creepercapital.core.trading.OfflineSummaries.Summary;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.network.AdlLightsPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.server.ServerAccounts;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerOfflineSummary;
import io.github.djp440.creepercapital.server.ServerTrading;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 持仓总量上限与自动减仓（ADL，#17）的 GameTest：服务端接线、通知、成交历史里的自动减仓、指示灯同步、离线汇总。
 * 上限的算法、ADL 的排名与减仓量、资金守恒在 core 的 {@code OpenInterestCapTest} 和 {@code AutoDeleverageTest}。
 * <p>
 * 所有 GameTest 共用一个活账本，池子权益是几十万量级，没法靠一个小仓位让它转负，所以：
 * ADL 直接调 {@link ServerTrading#deleverage}，传入人为放大的标记价格，并限定只处理本测试的玩家；
 * 持仓总量上限用若干「填充玩家」在同一个 tick 内把剩余空间吃到只剩一点，断言完马上平掉，其他测试看不到。
 * 活市场的价格、数量精度、杠杆上限、分档都是配置决定的，所以手数与杠杆按当前合约现算，不假设内置合约参数。
 */
final class AdlGameTests {
    private static final String DIAMOND = "DIAMONDCRP";
    private static final String KEY_PREFIX = "message.creepercapital.offline_summary.";

    private AdlGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("open_interest_cap_rejects_opens_with_a_message_and_follows_free_room",
                AdlGameTests::openInterestCapRejectsOpens);
        function.accept("adl_stage_reduces_at_entry_price_and_notifies_the_player", AdlGameTests::adlStageReducesAndNotifies);
        function.accept("adl_lights_synced_to_the_player_agree_with_the_ranking", AdlGameTests::lightsAgreeWithTheRanking);
        function.accept("offline_adl_is_summarized_and_matches_the_trade_history", AdlGameTests::offlineAdlIsSummarized);
    }

    private static ContractSpec spec() {
        return ServerMarket.spec(DIAMOND);
    }

    private static final ToDoubleFunction<String> LIVE_MARKS = symbol -> ServerMarket.symbols().contains(symbol)
            ? ServerMarket.snapshot(symbol).markPrice() : Double.NaN;

    /** 半档的数量：名义价值不大，不会碰到分档的仓位上限。 */
    private static long lots() {
        return Math.max(1, spec().liquidityCurve(ServerMarket.snapshot(DIAMOND).lastPrice()).levelLots() / 2);
    }

    /** 缺省分档下高杠杆只允许小仓位，所以用低杠杆。 */
    private static int leverage() {
        return Math.min(5, spec().maxLeverage());
    }

    private static Money systemTotal(UUID player) {
        TradingBook book = ServerTrading.book();
        Money total = book.pool().balance().plus(book.fund().balance()).plus(ServerAccounts.balance(player))
                .plus(book.orders().frozen(player));
        for (Position position : book.positions(player)) {
            total = total.plus(position.margin());
        }
        return total;
    }

    /** 名义价值折成手数（向下取整，至少 1 手）。 */
    private static long lotsFor(BigDecimal notional, double price) {
        BigDecimal lots = notional.divide(BigDecimal.valueOf(price), 12, RoundingMode.DOWN)
                .movePointRight(spec().quantityDecimals()).setScale(0, RoundingMode.DOWN);
        return Math.max(1, lots.longValueExact());
    }

    private static BigDecimal asDecimal(Money money) {
        return BigDecimal.valueOf(money.units()).movePointLeft(6);
    }

    /** 放大的标记价格：让这个仓位的浮盈远超池子权益，ADL 必然触发（只对 DIAMOND 生效，别的合约没有价格）。 */
    private static ToDoubleFunction<String> hugeMark(Position position) {
        TradingBook book = ServerTrading.book();
        double equity = Math.max(1, asDecimal(book.poolEquity(LIVE_MARKS)).doubleValue());
        double notional = Math.max(1, asDecimal(TradingBook.notional(position.entryPrice(), position.lots(),
                position.quantityDecimals())).doubleValue());
        double mark = position.entryPrice() * (3 + 2 * equity / notional);
        return symbol -> DIAMOND.equals(symbol) ? mark : Double.NaN;
    }

    /** 以一半的价格开多：活市场的真实价格约是开仓价的两倍，这个仓位一开出来就大幅盈利。 */
    private static Position openAtHalfPrice(GameTestHelper helper, UUID player, long lots, int leverage) {
        MarketSnapshot live = ServerMarket.snapshot(DIAMOND);
        MarketSnapshot fake = new MarketSnapshot(live.step(), DIAMOND, live.indexPrice() / 2, live.lastPrice() / 2,
                live.markPrice() / 2, live.fundingRate(), 0);
        ServerTrading.book().open(player, spec(), fake, Side.LONG, lots, leverage, LIVE_MARKS);
        helper.assertTrue(ServerTrading.book().position(player, DIAMOND).isPresent(), "假快照开仓应成功");
        return ServerTrading.book().position(player, DIAMOND).orElseThrow();
    }

    /**
     * 持仓总量上限：用杠杆 1 的「填充玩家」把这个合约的剩余空间吃到只剩一点，玩家再开一笔超出剩余空间的仓位被拒绝，
     * 提示里带上限；被拒的单子什么都没改；填充玩家平仓腾出空间后，同样的单子又能开。平仓本身不受上限限制。
     */
    private static void openInterestCapRejectsOpens(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TradingGameTests.trader(helper, "AdlCap1", "1000000");
        List<UUID> fillers = new ArrayList<>();
        try {
            TradingBook book = ServerTrading.book();
            UUID id = player.getUUID();
            ContractSpec spec = spec();
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(), 1));
            helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(), "open_long",
                    "空间足够时正常开仓");
            long held = book.position(id, DIAMOND).orElseThrow().lots();

            // 填充：按略高于最新价估值（成交价含半个点差和滑点），每个填充玩家不超过杠杆 1 下的仓位上限
            Money cap = book.openInterestCap(spec, LIVE_MARKS);
            Money limit = spec.marginTiers().maxNotionalAt(1);
            BigDecimal stopRoom = asDecimal(cap).multiply(new BigDecimal("0.06")).min(asDecimal(limit).multiply(new BigDecimal("0.3")));
            for (int i = 0; i < 40; i++) {
                double price = ServerMarket.snapshot(DIAMOND).lastPrice() * 1.05;
                BigDecimal room = asDecimal(book.openInterestCap(spec, LIVE_MARKS)).subtract(asDecimal(book.openInterest(spec, price)));
                if (room.compareTo(stopRoom) <= 0) {
                    break;
                }
                BigDecimal step = asDecimal(limit).multiply(new BigDecimal("0.9")).min(room.subtract(stopRoom.divide(BigDecimal.TWO)));
                UUID filler = UUID.randomUUID();
                fillers.add(filler);
                long fillLots = lotsFor(step, price);
                ServerAccounts.deposit(server, filler, Money.parse(step.multiply(new BigDecimal("1.3")).add(BigDecimal.TEN)
                        .setScale(6, RoundingMode.UP).toPlainString()));
                book.open(filler, spec, ServerMarket.snapshot(DIAMOND), Side.LONG, fillLots, 1, LIVE_MARKS);
                helper.assertTrue(book.position(filler, DIAMOND).isPresent(), "填充玩家应当开仓成功：" + step);
            }
            helper.assertFalse(fillers.isEmpty(), "剩余空间应当大于停止线，至少要填充一次");

            double price = ServerMarket.snapshot(DIAMOND).lastPrice();
            BigDecimal room = asDecimal(book.openInterestCap(spec, LIVE_MARKS)).subtract(asDecimal(book.openInterest(spec, price)));
            // 填充时按高 5% 的价格估值（留出成交价的点差与滑点），所以按最新价算剩下的空间会比停止线略多
            helper.assertTrue(room.signum() > 0 && room.multiply(new BigDecimal("1.5")).compareTo(asDecimal(limit).multiply(new BigDecimal("0.9"))) < 0,
                    "只剩一点空间（下面超出它的单子不会先碰到仓位上限）：" + room);
            long over = lotsFor(room.multiply(new BigDecimal("1.5")), price);
            Money freeBefore = ServerAccounts.balance(id);
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, over, 1));
            OrderResultPayload rejected = TradingGameTests.last(MockClient.received(player, OrderResultPayload.class));
            helper.assertValueEqual(rejected.code(), "open_interest_limit", "超出剩余空间的开仓被拒");
            helper.assertFalse(rejected.success(), "拒单不是成功提示");
            helper.assertValueEqual(rejected.args().get(0), book.openInterestCap(spec, LIVE_MARKS).toDisplayString(),
                    "提示里带着当前的上限");
            helper.assertValueEqual(book.position(id, DIAMOND).orElseThrow().lots(), held, "被拒的单子不改仓位");
            helper.assertValueEqual(ServerAccounts.balance(id), freeBefore, "被拒的单子不动余额");

            // 限价挂单同样检查：挂单时就拒绝，不冻结资金
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, over, 1,
                    OrderPayload.Execution.LIMIT, Math.floor(price)));
            helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(),
                    "open_interest_limit", "限价挂单超限同样被拒");
            helper.assertTrue(book.orders().open(id).isEmpty(), "被拒的限价单没有挂出去");

            // 平仓不受上限限制
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.CLOSE, DIAMOND, held / 2 > 0 ? held / 2 : held, 1));
            helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(), "close",
                    "接近上限时仍然可以平仓");

            // 填充玩家平仓腾出空间，同样的单子又能开
            for (UUID filler : fillers) {
                Position position = book.position(filler, DIAMOND).orElseThrow();
                book.close(filler, spec, ServerMarket.snapshot(DIAMOND), position.lots());
            }
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, over, 1));
            helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(), "open_long",
                    "空间腾出来之后同样的单子可以开");
        } finally {
            for (UUID filler : fillers) {
                cleanUp(server, filler);
            }
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /** 清理填充玩家：平掉残余仓位，余额取走。 */
    private static void cleanUp(MinecraftServer server, UUID filler) {
        TradingBook book = ServerTrading.book();
        for (Position position : book.positions(filler)) {
            book.close(filler, ServerMarket.spec(position.symbol()), ServerMarket.snapshot(position.symbol()), position.lots());
        }
        Money left = ServerAccounts.balance(filler);
        if (left.isPositive()) {
            ServerAccounts.withdraw(server, filler, left);
        }
    }

    /**
     * 池子权益转负时，ADL 阶段以开仓价缩减盈利仓位：成交历史里是一笔自动减仓（价格是开仓价、无手续费、已实现盈亏 0），
     * 保证金按比例退回可用余额，玩家 + 池子 + 基金的总额不变，权益回到非负；在线玩家收到通知，不进离线汇总。
     */
    private static void adlStageReducesAndNotifies(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TradingGameTests.trader(helper, "AdlStage1", "100000");
        try {
            UUID id = player.getUUID();
            TradingBook book = ServerTrading.book();
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(), leverage()));
            helper.assertValueEqual(TradingGameTests.last(MockClient.received(player, OrderResultPayload.class)).code(), "open_long",
                    "开仓成功");
            Position position = book.position(id, DIAMOND).orElseThrow();
            ToDoubleFunction<String> marks = hugeMark(position);
            helper.assertTrue(book.poolEquity(marks).units() < 0, "放大的标记价格让池子权益转负");
            Money totalBefore = systemTotal(id);
            Money freeBefore = ServerAccounts.balance(id);
            Money equityBefore = book.poolEquity(marks);
            Set<UUID> touched = new LinkedHashSet<>();

            List<AutoDeleverageEvent> events = ServerTrading.deleverage(server,
                    ServerMarket.snapshot(DIAMOND).time().totalSeconds(), marks, other -> other.equals(id), touched);

            helper.assertValueEqual(events.size(), 1, "本测试的玩家被自动减仓一次");
            AutoDeleverageEvent event = events.get(0);
            helper.assertValueEqual(event.player(), id, "被减仓的是本测试的玩家");
            helper.assertValueEqual(event.price(), position.entryPrice(), "按开仓价成交");
            helper.assertTrue(event.lots() >= 1 && event.lots() <= position.lots(), "缩减量在仓位之内");
            helper.assertValueEqual(event.remainingLots(), position.lots() - event.lots(), "剩余数量");
            helper.assertTrue(event.lostProfit().isPositive(), "放弃了浮盈");
            // 共用的活账本里可能还有别的测试挂着的盈利仓位（限定了玩家，不动它们），所以不断言权益回到非负，
            // 只断言：池子现金没动，权益恰好回升被放弃的那部分浮盈
            helper.assertValueEqual(book.poolEquity(marks), equityBefore.plus(event.lostProfit()), "权益回升 = 放弃的浮盈");
            if (event.remainingLots() == 0) {
                helper.assertTrue(book.position(id, DIAMOND).isEmpty(), "整个仓位被减光");
            } else {
                helper.assertValueEqual(book.position(id, DIAMOND).orElseThrow().lots(), event.remainingLots(), "仓位只剩一部分");
            }
            helper.assertValueEqual(ServerAccounts.balance(id), freeBefore.plus(event.releasedMargin()), "保证金退回可用余额");
            helper.assertValueEqual(systemTotal(id), totalBefore, "玩家 + 池子 + 基金的总额不变");

            PlayerTrade trade = TradingGameTests.last(book.trades(id));
            helper.assertValueEqual(trade.kind(), TradeKind.ADL, "成交历史里是自动减仓");
            helper.assertValueEqual(trade.id(), event.tradeId(), "事件指向这笔成交");
            helper.assertValueEqual(trade.price(), position.entryPrice(), "成交价是开仓价");
            helper.assertValueEqual(trade.lots(), event.lots(), "成交数量");
            helper.assertValueEqual(trade.fee(), Money.ZERO, "自动减仓不收手续费");
            helper.assertValueEqual(trade.realizedPnl(), Money.ZERO, "开仓价成交，已实现盈亏为 0");

            helper.assertTrue(touched.contains(id), "被减仓的玩家记入需要刷新状态的集合");
            OrderResultPayload notice = TradingGameTests.last(MockClient.received(player, OrderResultPayload.class));
            helper.assertValueEqual(notice.code(), "auto_deleveraged", "玩家收到自动减仓通知");
            helper.assertFalse(notice.success(), "自动减仓通知不是成功提示");
            helper.assertValueEqual(notice.args().get(0), DIAMOND, "通知里带着合约");
            helper.assertFalse(ServerOfflineSummary.summaries().hasPending(id), "在线时的自动减仓不进离线汇总");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /**
     * 仓位页的 ADL 指示灯：服务端随交易状态同步的灯数，与同一时刻 core 的排名一致；盈利仓位至少排在榜上，
     * 灯数不超过 5，排名靠前的灯数不少于靠后的。
     */
    private static void lightsAgreeWithTheRanking(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "AdlLights1", "100000");
        try {
            UUID id = player.getUUID();
            TradingBook book = ServerTrading.book();
            openAtHalfPrice(helper, id, lots(), leverage());

            ServerTrading.sendState(player);

            AdlLightsPayload payload = TradingGameTests.last(MockClient.received(player, AdlLightsPayload.class));
            AdlRanking ranking = book.adlRanking(LIVE_MARKS);
            helper.assertTrue(payload.lights().containsKey(DIAMOND), "盈利的仓位在指示灯同步里");
            helper.assertValueEqual(payload.lights(), ranking.lightsOf(id), "同步的灯数 = 同一时刻排名里的灯数");
            helper.assertValueEqual(payload.lights().get(DIAMOND), ranking.lights(id, DIAMOND), "这个仓位的灯数");
            int previous = AdlRanking.LIGHTS;
            for (AdlRanking.Entry entry : ranking.entries()) {
                helper.assertTrue(entry.lights() >= 1 && entry.lights() <= previous, "排名靠前的灯数不少于靠后的：" + entry);
                previous = entry.lights();
            }
            helper.assertValueEqual(ranking.entries().get(0).lights(), AdlRanking.LIGHTS, "排名第一的亮满 5 格");
            helper.assertValueEqual(ranking.lights(UUID.randomUUID(), DIAMOND), 1, "没有盈利仓位的显示最低的 1 格");

            // 平仓之后这个仓位不再在排名里
            book.close(id, spec(), ServerMarket.snapshot(DIAMOND), book.position(id, DIAMOND).orElseThrow().lots());
            helper.assertTrue(book.adlRanking(LIVE_MARKS).lightsOf(id).isEmpty(), "仓位平掉后不在排名里");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /**
     * 玩家离线时被自动减仓：离线汇总里记一次，金额是成交额，等于成交历史里自动减仓那一行的价格 x 数量；
     * 玩家上线时聊天栏收到一条汇总，行里有自动减仓的次数与成交额，之后清空。
     */
    private static void offlineAdlIsSummarized(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = TradingGameTests.trader(helper, "AdlOffline1", "100000");
        UUID id = player.getUUID();
        ServerPlayer rejoined = null;
        try {
            TradingBook book = ServerTrading.book();
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(), leverage()));
            Position position = book.position(id, DIAMOND).orElseThrow();
            ToDoubleFunction<String> marks = hugeMark(position);
            MockClient.leave(player);
            helper.assertFalse(ServerOfflineSummary.summaries().hasPending(id), "刚下线，汇总是空的");

            List<AutoDeleverageEvent> events = ServerTrading.deleverage(server,
                    ServerMarket.snapshot(DIAMOND).time().totalSeconds(), marks, other -> other.equals(id), new LinkedHashSet<>());

            helper.assertValueEqual(events.size(), 1, "离线玩家同样被自动减仓");
            Summary pending = ServerOfflineSummary.summaries().pending(id);
            helper.assertValueEqual(pending.count(EventType.AUTO_DELEVERAGE), 1L, "汇总里记了 1 次自动减仓");
            Money details = Money.ZERO;
            for (PlayerTrade trade : book.trades(id)) {
                if (trade.kind() == TradeKind.ADL) {
                    details = details.plus(TradingBook.notional(trade.price(), trade.lots(), spec().quantityDecimals()));
                }
            }
            helper.assertTrue(details.isPositive(), "成交历史里有自动减仓记录");
            helper.assertValueEqual(pending.amount(EventType.AUTO_DELEVERAGE), details, "汇总金额 = 成交历史里自动减仓行的成交额合计");

            rejoined = MockClient.join(helper, "AdlOffline1", id);
            List<List<TranslatableContents>> messages = MockClient.systemMessages(rejoined).stream()
                    .map(AdlGameTests::parts).filter(p -> !p.isEmpty() && p.get(0).getKey().equals(KEY_PREFIX + "header")).toList();
            helper.assertValueEqual(messages.size(), 1, "上线时收到一条汇总");
            List<String> keys = messages.get(0).stream().map(TranslatableContents::getKey).toList();
            helper.assertValueEqual(keys, List.of(KEY_PREFIX + "header", KEY_PREFIX + "auto_deleverage", KEY_PREFIX + "footer"),
                    "标题、自动减仓、明细提示");
            List<String> args = java.util.Arrays.stream(messages.get(0).get(1).getArgs()).map(String::valueOf).toList();
            helper.assertValueEqual(args, List.of("1", details.toDisplayString()), "自动减仓行的次数与成交额");
            helper.assertFalse(ServerOfflineSummary.summaries().hasPending(id), "送达后汇总已清空");
        } finally {
            TradingGameTests.finish(rejoined != null ? rejoined : player);
        }
        helper.succeed();
    }

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
}
