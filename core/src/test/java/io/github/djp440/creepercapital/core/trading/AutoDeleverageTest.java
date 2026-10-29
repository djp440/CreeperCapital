package io.github.djp440.creepercapital.core.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.account.AccountBook;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.Market;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;

/**
 * 自动减仓（ADL，#17）。池子权益 = 池子现金 − 全体玩家的未实现盈亏（逐仓浮亏最多算到保证金）；权益为负时，
 * 按「盈利率 × 杠杆」从高到低，以<b>开仓价</b>缩减盈利仓位（被缩减部分的盈利归零，保证金退回，无手续费），直到权益不再为负。
 * <p>
 * 手算场景（火药，数量精度 0 位，标记价格 130，池子现金 100，三位玩家各一个逐仓仓位）：
 * <ul>
 * <li>甲：多 10 手，开仓价 100，10 倍，保证金 100；浮盈 300，盈利率 3.0，得分 3.0 × 10 = 30</li>
 * <li>乙：多 20 手，开仓价 100，5 倍，保证金 400；浮盈 600，盈利率 1.5，得分 1.5 × 5 = 7.5</li>
 * <li>丙：空 5 手，开仓价 100，20 倍，保证金 25；浮亏 150，只算到保证金 25</li>
 * </ul>
 * 权益 = 100 − 300 − 600 + 25 = −775。
 */
class AutoDeleverageTest {
    private static final ContractSpec GUNPOWDER = ContractSpec.defaults().get(1);
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final long SECONDS = 900;

    private AccountBook accounts;
    private TradingBook book;

    @BeforeEach
    void setUp() {
        accounts = new AccountBook();
    }

    private static ToDoubleFunction<String> mark(double gunpowder) {
        return symbol -> symbol.equals("GUNPOWDERCRP") ? gunpowder : Double.NaN;
    }

    private static Position gunpowder(Side side, long lots, int leverage, String margin) {
        return new Position("GUNPOWDERCRP", side, lots, 100, leverage, Money.parse(margin), 0);
    }

    /** 直接用存档状态摆出仓位：池子现金、仓位都是给定的，玩家可用余额为 0。 */
    private void scenario(String pool, Map<UUID, Position> positions) {
        Map<UUID, List<Position>> owned = new HashMap<>();
        positions.forEach((player, position) -> owned.put(player, List.of(position)));
        book = TradingBook.restore(accounts, new TradingState(Money.parse(pool), 0, owned, Map.of()));
    }

    private void threePlayers(String pool) {
        scenario(pool, Map.of(A, gunpowder(Side.LONG, 10, 10, "100"), B, gunpowder(Side.LONG, 20, 5, "400"),
                C, gunpowder(Side.SHORT, 5, 20, "25")));
    }

    private Money total() {
        Money total = book.pool().balance().plus(book.fund().balance());
        for (UUID player : List.of(A, B, C)) {
            total = total.plus(accounts.balance(player));
            for (Position position : book.positions(player)) {
                total = total.plus(position.margin());
            }
        }
        return total;
    }

    // ---- 触发条件 ----

    @Test
    @DisplayName("池子权益为 −775：浮盈 900 加上池子现金 100 都不够，浮亏只算到保证金")
    void poolEquityIsNegative() {
        threePlayers("100");

        assertEquals(Money.parse("-775"), book.poolEquity(mark(130)));
    }

    @Test
    @DisplayName("权益不为负时不触发：池子 1000 → 权益 125")
    void solventPoolIsLeftAlone() {
        threePlayers("1000");

        assertEquals(Money.parse("125"), book.poolEquity(mark(130)));
        assertTrue(book.checkSolvency(mark(130), SECONDS).isEmpty());
        assertEquals(10, book.position(A, "GUNPOWDERCRP").orElseThrow().lots());
    }

    // ---- 按排名依次缩减 ----

    @Test
    @DisplayName("按得分依次缩减：甲（30）全部缩减，乙（7.5）缩减 16 手，直到权益回到 5")
    void reducesByRankUntilSolvent() {
        threePlayers("100");
        Money before = total();

        List<AutoDeleverageEvent> events = book.checkSolvency(mark(130), SECONDS);

        assertEquals(2, events.size());
        AutoDeleverageEvent first = events.get(0);
        assertEquals(A, first.player());
        assertEquals(10, first.lots());
        assertEquals(0, first.remainingLots());
        assertEquals(100.0, first.price());
        assertEquals(130.0, first.markPrice());
        assertEquals(Money.parse("300"), first.lostProfit());
        assertEquals(Money.parse("100"), first.releasedMargin());
        assertEquals(1, first.rank());
        AutoDeleverageEvent second = events.get(1);
        assertEquals(B, second.player());
        // 乙每手浮盈 30，权益还差 475，16 手 = 480 刚好够（15 手 450 不够）
        assertEquals(16, second.lots());
        assertEquals(4, second.remainingLots());
        assertEquals(Money.parse("480"), second.lostProfit());
        assertEquals(Money.parse("320"), second.releasedMargin());
        assertEquals(2, second.rank());

        assertEquals(Money.parse("5"), book.poolEquity(mark(130)));
        assertTrue(book.position(A, "GUNPOWDERCRP").isEmpty());
        Position left = book.position(B, "GUNPOWDERCRP").orElseThrow();
        assertEquals(4, left.lots());
        assertEquals(Money.parse("80"), left.margin());
        assertEquals(100.0, left.entryPrice(), "剩下的部分开仓价不变");
        assertEquals(5, book.position(C, "GUNPOWDERCRP").orElseThrow().lots(), "亏损的丙不受影响");
        assertEquals(Money.parse("100"), accounts.balance(A), "被缩减部分的保证金退回可用余额");
        assertEquals(Money.parse("320"), accounts.balance(B));
        assertEquals(before, total(), "玩家 + 池子 + 基金的总额不变");
        assertEquals(Money.parse("100"), book.pool().balance(), "池子现金没动：盈利被抹掉，不是被支付");
    }

    @Test
    @DisplayName("自动减仓记一笔成交：种类 ADL，价格是开仓价，无手续费，已实现盈亏 0")
    void recordsAnAdlTrade() {
        threePlayers("100");

        AutoDeleverageEvent event = book.checkSolvency(mark(130), SECONDS).get(0);

        PlayerTrade trade = book.trades(A).get(0);
        assertEquals(TradeKind.ADL, trade.kind());
        assertEquals(event.tradeId(), trade.id());
        assertEquals(Side.LONG, trade.side());
        assertEquals(100.0, trade.price());
        assertEquals(10, trade.lots());
        assertEquals(Money.ZERO, trade.fee());
        assertEquals(Money.ZERO, trade.realizedPnl());
        assertEquals(SECONDS, trade.marketSeconds());
    }

    @Test
    @DisplayName("离线汇总（#19）：自动减仓记成交额，等于成交历史里那行的价格 x 数量（甲 100 x 10 = 1000，乙 100 x 16 = 1600）")
    void offlineSummaryMatchesTheTradeHistory() {
        threePlayers("100");
        OfflineSummaries summaries = OfflineSummaries.restore(Map.of());

        for (AutoDeleverageEvent event : book.checkSolvency(mark(130), SECONDS)) {
            summaries.record(event, GUNPOWDER.quantityDecimals());
        }

        OfflineSummaries.Summary a = summaries.pending(A);
        assertEquals(1, a.count(OfflineSummaries.EventType.AUTO_DELEVERAGE));
        assertEquals(Money.parse("1000"), a.amount(OfflineSummaries.EventType.AUTO_DELEVERAGE));
        PlayerTrade tradeA = book.trades(A).get(0);
        assertEquals(TradingBook.notional(tradeA.price(), tradeA.lots(), GUNPOWDER.quantityDecimals()),
                a.amount(OfflineSummaries.EventType.AUTO_DELEVERAGE));
        assertEquals(Money.parse("1600"), summaries.pending(B).amount(OfflineSummaries.EventType.AUTO_DELEVERAGE));
        assertFalse(summaries.hasPending(C));
    }

    @Test
    @DisplayName("限定玩家：只缩减允许的玩家，其他人不动（GameTest 共用账本时用）")
    void restrictedToSomePlayers() {
        threePlayers("100");

        List<AutoDeleverageEvent> events = book.checkSolvency(player -> player.equals(B), mark(130), SECONDS);

        assertEquals(1, events.size());
        assertEquals(20, events.get(0).lots(), "乙的 600 盈利全抹掉也不够 775，只能全缩减");
        assertEquals(10, book.position(A, "GUNPOWDERCRP").orElseThrow().lots());
    }

    @Test
    @DisplayName("全仓仓位同样按开仓价缩减，退回的保证金进可用余额")
    void crossPositionIsReducedToo() {
        scenario("100", Map.of(A, new Position("GUNPOWDERCRP", Side.LONG, 10, 100, 10, Money.parse("100"), 0, MarginMode.CROSS),
                B, gunpowder(Side.LONG, 20, 5, "400")));

        // 权益 100 − 300 − 600 = −800：甲全缩减（300）后还差 500，乙缩减 17 手（510）
        List<AutoDeleverageEvent> events = book.checkSolvency(mark(130), SECONDS);

        assertEquals(2, events.size());
        assertEquals(MarginMode.CROSS, events.get(0).mode());
        assertEquals(Money.parse("100"), accounts.balance(A));
        assertEquals(17, events.get(1).lots());
        assertEquals(Money.parse("10"), book.poolEquity(mark(130)));
    }

    @Test
    @DisplayName("只减仓、止盈止损委托随被全部缩减的仓位失效")
    void attachedOrdersFollowTheirPosition() {
        threePlayers("100");

        book.checkSolvency(mark(130), SECONDS);

        // 甲已被全部缩减：仓位没了，账本里不留残余
        assertTrue(book.positions(A).isEmpty());
        assertFalse(book.state().positions().containsKey(A));
    }

    // ---- ADL 指示灯 ----

    @Test
    @DisplayName("灯数：两个盈利仓位按排名 5、3 格；非盈利仓位显示最低的 1 格")
    void lightsFollowTheRank() {
        threePlayers("100");

        AdlRanking ranking = book.adlRanking(mark(130));

        assertEquals(List.of(A, B), ranking.entries().stream().map(AdlRanking.Entry::player).toList());
        assertEquals(30.0, ranking.entries().get(0).score(), 1e-9);
        assertEquals(7.5, ranking.entries().get(1).score(), 1e-9);
        assertEquals(5, ranking.lights(A, "GUNPOWDERCRP"));
        assertEquals(3, ranking.lights(B, "GUNPOWDERCRP"));
        assertEquals(1, ranking.lights(C, "GUNPOWDERCRP"), "亏损仓位不会被自动减仓，显示最低的一格");
    }

    @Test
    @DisplayName("灯数把盈利仓位按排名五等分：10 个仓位每两个一档")
    void lightsSplitIntoFifths() {
        assertEquals(5, AdlRanking.lightsFor(1, 10));
        assertEquals(5, AdlRanking.lightsFor(2, 10));
        assertEquals(4, AdlRanking.lightsFor(3, 10));
        assertEquals(4, AdlRanking.lightsFor(4, 10));
        assertEquals(1, AdlRanking.lightsFor(9, 10));
        assertEquals(1, AdlRanking.lightsFor(10, 10));
        assertEquals(5, AdlRanking.lightsFor(1, 1), "只有一个盈利仓位时它就是最先被减的");
        assertEquals(List.of(5, 4, 3, 2, 1), List.of(1, 2, 3, 4, 5).stream().map(rank -> AdlRanking.lightsFor(rank, 5)).toList());
    }

    @Test
    @DisplayName("灯数与实际排名一致：随机 40 个盈利仓位，灯数随排名不增，且自动减仓的顺序就是排名顺序")
    void lightsAgreeWithTheActualDeleveragingOrder() {
        Random random = new Random(17);
        Map<UUID, List<Position>> owned = new HashMap<>();
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID player = new UUID(1, i);
            players.add(player);
            int leverage = 1 + random.nextInt(50);
            long lots = 1 + random.nextInt(40);
            double entry = 60 + random.nextInt(60);
            Money notional = TradingBook.notional(entry, lots, 0);
            Money margin = Money.ofUnits((notional.units() + leverage - 1) / leverage);
            owned.put(player, List.of(new Position("GUNPOWDERCRP", random.nextBoolean() ? Side.LONG : Side.SHORT, lots,
                    entry, leverage, margin, 0)));
        }
        // 标记价格 100 对多头是 entry 60..119 里一半盈利、一半亏损，空头相反：盈利者约 20 个
        book = TradingBook.restore(accounts, new TradingState(Money.parse("1"), 0, owned, Map.of()));
        ToDoubleFunction<String> marks = mark(100);

        AdlRanking ranking = book.adlRanking(marks);
        List<AdlRanking.Entry> entries = ranking.entries();
        assertTrue(entries.size() >= 10, "盈利仓位足够多：" + entries.size());
        for (int i = 0; i < entries.size(); i++) {
            AdlRanking.Entry entry = entries.get(i);
            assertEquals(i + 1, entry.rank());
            assertEquals(AdlRanking.lightsFor(i + 1, entries.size()), entry.lights());
            assertEquals(entry.lights(), ranking.lights(entry.player(), entry.symbol()));
            assertTrue(entry.pnl().isPositive());
            if (i > 0) {
                assertTrue(entries.get(i - 1).score() >= entry.score(), "得分从高到低");
                assertTrue(entries.get(i - 1).lights() >= entry.lights(), "灯数不随排名增加");
            }
        }
        assertEquals(5, entries.get(0).lights());
        assertEquals(1, entries.get(entries.size() - 1).lights());

        // 池子只有 1 CRP，权益深度为负：自动减仓的顺序必须就是排名顺序（前缀）
        List<AutoDeleverageEvent> events = book.checkSolvency(marks, SECONDS);
        assertFalse(events.isEmpty());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(entries.get(i).player(), events.get(i).player(), "第 " + (i + 1) + " 个被减的就是排名第 " + (i + 1));
            assertEquals(i + 1, events.get(i).rank());
        }
        assertTrue(book.poolEquity(marks).units() >= 0);
    }

    // ---- 资金守恒与极端行情 ----

    @Test
    @DisplayName("用行情跳跃制造极端上涨：强平先处理，再自动减仓，池子恢复偿付能力，总额守恒，余额不为负")
    void marketJumpForcesDeleveraging() {
        Market market = new Market(20260101L, ContractSpec.defaults());
        market.advanceSteps(5);
        MarketSnapshot before = market.snapshot("DIAMONDCRP");
        ContractSpec spec = market.spec("DIAMONDCRP");
        pool(Money.parse("3000"));
        UUID bull = new UUID(2, 1);
        UUID bear = new UUID(2, 2);
        UUID small = new UUID(2, 3);
        // 多头 10 倍、空头 10 倍、小额多头 2 倍；三个仓位加起来约 1450 CRP，在池子权益 3000 的 50% 上限以内
        accounts.deposit(bull, Money.parse("50000"));
        accounts.deposit(bear, Money.parse("50000"));
        accounts.deposit(small, Money.parse("50000"));
        ToDoubleFunction<String> marks = symbol -> market.snapshot(symbol).markPrice();
        assertInstanceOf(TradeResult.Filled.class, book.open(bull, spec, before, Side.LONG, 1300, 10, marks));
        assertInstanceOf(TradeResult.Filled.class, book.open(bear, spec, before, Side.SHORT, 100, 10, marks));
        assertInstanceOf(TradeResult.Filled.class, book.open(small, spec, before, Side.LONG, 50, 2, marks));
        Money total = totalOf(bull, bear, small);
        assertTrue(book.poolEquity(marks).isPositive(), "跳跃之前池子是偿付的");
        assertTrue(book.checkSolvency(marks, before.time().totalSeconds()).isEmpty());

        // 价格涨到 4 倍：多头暴利，池子 3000 付不起；空头被强平
        market.queueJump("DIAMONDCRP", 3.0);
        MarketSnapshot after = market.advanceSteps(1).stream().filter(s -> s.symbol().equals("DIAMONDCRP")).findFirst()
                .orElseThrow();
        assertTrue(after.markPrice() > before.markPrice() * 3, "跳跃之后的标记价格 " + after.markPrice());
        long secs = after.time().totalSeconds();
        assertTrue(book.poolEquity(marks).units() < 0, "池子权益已为负");

        // 同一步的顺序：③ 强平 → ④ 偿付检查 / 自动减仓
        List<LiquidationEvent> liquidations = book.checkLiquidations(marks, s -> market.spec(s).marginTiers(), secs);
        List<AutoDeleverageEvent> reductions = book.checkSolvency(marks, secs);

        assertEquals(1, liquidations.size(), "空头被强平");
        assertEquals(bear, liquidations.get(0).player());
        assertFalse(reductions.isEmpty(), "多头被自动减仓");
        assertTrue(book.poolEquity(marks).units() >= 0, "自动减仓之后池子恢复偿付能力");
        assertEquals(total, totalOf(bull, bear, small), "强平和自动减仓前后总额不变");
        for (UUID player : List.of(bull, bear, small)) {
            assertFalse(accounts.balance(player).units() < 0);
        }
        // 盈利率 × 杠杆最高的是 10 倍的多头，先被减
        assertEquals(bull, reductions.get(0).player());
        // 减仓不是平仓：剩下的仓位还在，开仓价不变
        book.position(bull, "DIAMONDCRP").ifPresent(left -> assertEquals(reductions.get(0).price(), left.entryPrice()));
        // 再检查一次不会再减
        assertTrue(book.checkSolvency(marks, secs).isEmpty());
    }

    private void pool(Money balance) {
        book = new TradingBook(accounts, new LiquidityPool(balance));
    }

    private Money totalOf(UUID... players) {
        Money total = book.pool().balance().plus(book.fund().balance());
        for (UUID player : players) {
            total = total.plus(accounts.balance(player));
            for (Position position : book.positions(player)) {
                total = total.plus(position.margin());
            }
            total = total.plus(book.orders().frozen(player));
        }
        return total;
    }

    @Test
    @DisplayName("随机序列：开平仓、价格乱跳、强平、自动减仓，每一步总额守恒，余额不为负，减仓后权益不为负")
    void totalIsConservedWithAutoDeleveraging() {
        Random random = new Random(1717);
        UUID[] players = {new UUID(3, 1), new UUID(3, 2), new UUID(3, 3), new UUID(3, 4)};
        pool(Money.parse("4000"));
        for (UUID player : players) {
            accounts.deposit(player, Money.parse("3000"));
        }
        Money expected = Money.parse("4000").plus(Money.parse("12000"));
        ContractSpec diamond = ContractSpec.defaults().get(0);
        ContractSpec gun = ContractSpec.defaults().get(1);
        ContractSpec[] specs = {diamond, gun};
        int reductions = 0;
        int liquidations = 0;
        int rejectedByCap = 0;
        for (int i = 0; i < 3000; i++) {
            double diamondPrice = 1000 * (0.4 + random.nextDouble() * 2.2);
            double gunPrice = 100 * (0.4 + random.nextDouble() * 2.2);
            ToDoubleFunction<String> marks = symbol -> symbol.equals("DIAMONDCRP") ? diamondPrice : gunPrice;
            UUID player = players[random.nextInt(players.length)];
            ContractSpec spec = specs[random.nextInt(specs.length)];
            double last = spec == diamond ? diamondPrice : gunPrice;
            MarketSnapshot at = new MarketSnapshot(100 + i, spec.symbol(), last, last, last, 0.0001, 0);
            int action = random.nextInt(10);
            if (action < 5) {
                TradeResult result = book.open(player, spec, at, random.nextBoolean() ? Side.LONG : Side.SHORT,
                        1 + random.nextInt(spec == diamond ? 800 : 8), 1 + random.nextInt(8), marks);
                if (result instanceof TradeResult.Rejected rejected
                        && rejected.reason() == TradeResult.Reason.OPEN_INTEREST_LIMIT) {
                    rejectedByCap++;
                }
            } else if (action < 8) {
                long open = book.position(player, spec.symbol()).map(Position::lots).orElse(1L);
                book.close(player, spec, at, 1 + random.nextInt((int) open));
            }
            // 同一步的顺序：强平 → 偿付检查
            if (random.nextInt(3) == 0) {
                liquidations += book.checkLiquidations(marks, s -> s.equals("DIAMONDCRP") ? diamond.marginTiers()
                        : gun.marginTiers(), i).size();
                List<AutoDeleverageEvent> events = book.checkSolvency(marks, i);
                reductions += events.size();
                assertTrue(book.poolEquity(marks).units() >= 0, "第 " + i + " 步自动减仓后权益不为负");
            }
            Money total = book.pool().balance().plus(book.fund().balance());
            for (UUID each : players) {
                assertFalse(accounts.balance(each).units() < 0, "第 " + i + " 步 " + each + " 余额为负");
                total = total.plus(accounts.balance(each)).plus(book.orders().frozen(each));
                for (Position position : book.positions(each)) {
                    total = total.plus(position.margin());
                }
            }
            assertEquals(expected, total, "第 " + i + " 步资金守恒");
            // 补钱（开发命令式调账）
            for (UUID each : players) {
                if (accounts.balance(each).compareTo(Money.parse("500")) < 0) {
                    accounts.deposit(each, Money.parse("1000"));
                    expected = expected.plus(Money.parse("1000"));
                }
            }
        }
        assertTrue(reductions > 0, "随机序列里发生过自动减仓");
        assertTrue(liquidations > 0, "随机序列里发生过强平");
        assertTrue(rejectedByCap > 0, "随机序列里上限拒绝过开仓");
    }
}
