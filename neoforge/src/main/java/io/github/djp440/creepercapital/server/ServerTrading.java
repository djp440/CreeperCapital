package io.github.djp440.creepercapital.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.CancelReason;
import io.github.djp440.creepercapital.core.trading.FundingRecord;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.MarginResult;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderEvent;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Quantities;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradeResult;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.network.AccountPayload;
import io.github.djp440.creepercapital.network.CancelOrderPayload;
import io.github.djp440.creepercapital.network.ModPayloads;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.network.TradingStatePayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 服务端的交易：玩家的市价 / 限价委托、仓位、成交历史与流动性池，随世界存档保存（{@link TradingSavedData}）。
 * 所有状态以服务端为准：客户端只发下单意图（{@link OrderPayload}），成交后服务端把最新的交易状态、资产和结果回给玩家。
 * <p>
 * 暂停市场时拒绝下单（价格冻结，不该有人在冻住的价格上成交）。每个行情步，持有仓位的在线玩家会收到按最新标记价格重算的资产。
 */
public final class ServerTrading {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 同步给客户端的成交历史条数上限。 */
    private static final int SYNCED_TRADES = 100;

    private static @Nullable TradingSavedData data;
    private static @Nullable TradingBook book;

    private ServerTrading() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerTrading::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ServerTrading::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerTrading::onPlayerLoggedIn);
    }

    /** 交易账，只能在服务器运行时调用。 */
    public static TradingBook book() {
        if (book == null) {
            throw new IllegalStateException("服务器未运行，没有交易账");
        }
        return book;
    }

    /** 此刻这名玩家的资产：仓位的未实现盈亏按各合约当前的标记价格算。 */
    public static Assets assets(UUID player) {
        return book().assets(player, ServerTrading::markPrice);
    }

    private static double markPrice(String symbol) {
        return ServerMarket.symbols().contains(symbol) ? ServerMarket.snapshot(symbol).markPrice() : Double.NaN;
    }

    public static Money poolBalance() {
        return book().pool().balance();
    }

    /**
     * 给流动性池注资（{@code /creeper dev pool fund}）。
     *
     * @return 注资后池子的余额
     * @throws IllegalArgumentException 数量不为正
     * @throws ArithmeticException      余额溢出，池子不变
     */
    public static Money fundPool(Money amount) {
        Money balance = book().pool().fund(amount);
        markDirty();
        return balance;
    }

    /** 处理玩家发来的市价委托，并把结果、最新的交易状态和资产回给他。 */
    public static void handleOrder(ServerPlayer player, OrderPayload order) {
        OrderResultPayload result;
        try {
            result = execute(player, order);
        } catch (RuntimeException e) {
            // 畸形的委托（数值溢出等）只拒这一单，不能让网络处理线程抛出去踢掉玩家
            LOGGER.warn("处理 {} 的委托 {} 时出错", player.getGameProfile().name(), order, e);
            result = rejected("invalid_quantity");
        }
        ModPayloads.sendTo(player, result);
        if (result.success()) {
            markDirty();
            sendState(player);
            ModPayloads.sendTo(player, new AccountPayload(assets(player.getUUID())));
        }
    }

    private static OrderResultPayload execute(ServerPlayer player, OrderPayload order) {
        if (!ServerMarket.symbols().contains(order.symbol())) {
            return rejected("unknown_contract", order.symbol());
        }
        if (ServerMarket.isPaused()) {
            return rejected("market_paused");
        }
        ContractSpec spec = ServerMarket.spec(order.symbol());
        UUID id = player.getUUID();
        if (order.action().isMarginSetting()) {
            return executeMargin(player, spec, order);
        }
        if (order.execution() == OrderPayload.Execution.LIMIT && order.action() == OrderPayload.Action.CLOSE) {
            // 限价平仓（止盈）属于 #15，现在只支持限价开仓
            return rejected("limit_close_unsupported");
        }
        TradeResult result = submit(id, spec, order);
        if (result instanceof TradeResult.Rejected rejected) {
            LOGGER.debug("拒绝 {} 的委托 {}：{} {}", player.getGameProfile().name(), order, rejected.reason(), rejected.args());
            return rejected(rejected.reason().name().toLowerCase(Locale.ROOT), rejected.args().toArray(String[]::new));
        }
        if (result instanceof TradeResult.Placed placed) {
            Order resting = placed.order();
            LOGGER.info("限价委托 #{} {} {} {} 数量 {} 委托价 {} 冻结 {}", resting.id(), player.getGameProfile().name(),
                    resting.side(), resting.symbol(), formatQuantity(resting.lots(), spec.quantityDecimals()),
                    resting.price(), resting.frozen());
            return new OrderResultPayload(true, "limit_placed", List.of(resting.symbol(),
                    formatQuantity(resting.lots(), spec.quantityDecimals()), formatPrice(resting.price())));
        }
        PlayerTrade trade = ((TradeResult.Filled) result).trade();
        LOGGER.info("玩家成交 #{} {} {} {} {} 数量 {} 价格 {} 手续费 {} 已实现盈亏 {}", trade.id(), player.getGameProfile().name(),
                trade.kind(), trade.side(), trade.symbol(), formatQuantity(trade.lots(), spec.quantityDecimals()),
                trade.price(), trade.fee(), trade.realizedPnl());
        return filled(trade, spec);
    }

    /** 切换保证金模式或调整杠杆；回报的文案键见 lang 里的 {@code message.creepercapital.order.set_*}。 */
    private static OrderResultPayload executeMargin(ServerPlayer player, ContractSpec spec, OrderPayload order) {
        UUID id = player.getUUID();
        MarginResult result = switch (order.action()) {
            case SET_CROSS -> book().setMarginMode(id, spec.symbol(), MarginMode.CROSS);
            case SET_ISOLATED -> book().setMarginMode(id, spec.symbol(), MarginMode.ISOLATED);
            default -> book().adjustLeverage(id, spec, ServerTrading::markPrice, order.leverage());
        };
        if (result instanceof MarginResult.Rejected rejected) {
            LOGGER.debug("拒绝 {} 的保证金设置 {}：{} {}", player.getGameProfile().name(), order, rejected.reason(),
                    rejected.args());
            return rejected(rejected.reason().name().toLowerCase(Locale.ROOT), rejected.args().toArray(String[]::new));
        }
        LOGGER.info("玩家保证金设置 {} {} {} 杠杆 {}", player.getGameProfile().name(), order.action(), spec.symbol(),
                order.leverage());
        return switch (order.action()) {
            case SET_CROSS -> new OrderResultPayload(true, "set_cross", List.of(spec.symbol()));
            case SET_ISOLATED -> new OrderResultPayload(true, "set_isolated", List.of(spec.symbol()));
            default -> new OrderResultPayload(true, "set_leverage", List.of(spec.symbol(), String.valueOf(order.leverage())));
        };
    }

    private static TradeResult submit(UUID id, ContractSpec spec, OrderPayload order) {
        MarketSnapshot snapshot = ServerMarket.snapshot(order.symbol());
        Side side = order.action() == OrderPayload.Action.OPEN_SHORT ? Side.SHORT : Side.LONG;
        if (order.execution() == OrderPayload.Execution.LIMIT) {
            return book().orders().placeLimit(id, spec, snapshot, side, order.lots(), order.leverage(), order.price());
        }
        return switch (order.action()) {
            case OPEN_LONG, OPEN_SHORT ->
                    book().open(id, spec, snapshot, side, order.lots(), order.leverage(), ServerTrading::markPrice);
            case CLOSE -> book().close(id, spec, snapshot, order.lots());
            default -> throw new IllegalStateException("不是成交动作：" + order.action());
        };
    }

    private static OrderResultPayload filled(PlayerTrade trade, ContractSpec spec) {
        String code = trade.kind() == TradeKind.CLOSE ? "close" : trade.side() == Side.LONG ? "open_long" : "open_short";
        return new OrderResultPayload(true, code,
                List.of(trade.symbol(), formatQuantity(trade.lots(), spec.quantityDecimals()),
                        formatPrice(trade.price()), trade.fee().toDisplayString(),
                        trade.realizedPnl().toDisplayString()));
    }

    private static String formatPrice(double price) {
        return String.format(Locale.ROOT, "%,.2f", price);
    }

    /** 处理玩家撤单：只能撤自己的挂单；市场暂停时也可以撤（只是释放资金）。 */
    public static void handleCancel(ServerPlayer player, CancelOrderPayload payload) {
        UUID id = player.getUUID();
        Optional<Order> order = book().orders().open(id).stream().filter(o -> o.id() == payload.orderId()).findFirst();
        Optional<OrderEvent> event = order.flatMap(o -> book().orders().cancel(id, o.id(), marketSeconds(o.symbol())));
        if (event.isEmpty()) {
            ModPayloads.sendTo(player, rejected("no_such_order"));
            return;
        }
        LOGGER.info("玩家撤单 #{} {} 释放 {}", event.get().orderId(), player.getGameProfile().name(), event.get().amount());
        ModPayloads.sendTo(player, describe(event.get()));
        markDirty();
        sendState(player);
        ModPayloads.sendTo(player, new AccountPayload(assets(id)));
    }

    /** 此刻的市场时间（市场秒）：优先取这个合约的，合约已不在配置里就取任意合约的。 */
    private static long marketSeconds(String symbol) {
        if (ServerMarket.symbols().contains(symbol)) {
            return ServerMarket.snapshot(symbol).time().totalSeconds();
        }
        return ServerMarket.snapshots().stream().mapToLong(s -> s.time().totalSeconds()).findFirst().orElse(0);
    }

    /** 委托成交 / 撤销事件对应的玩家提示。 */
    private static OrderResultPayload describe(OrderEvent event) {
        int decimals = ServerMarket.symbols().contains(event.symbol())
                ? ServerMarket.spec(event.symbol()).quantityDecimals() : 0;
        List<String> args = List.of(event.symbol(), formatQuantity(event.lots(), decimals), formatPrice(event.price()),
                event.amount().toDisplayString());
        if (event.kind() == OrderEvent.Kind.FILLED) {
            return new OrderResultPayload(true, "limit_filled", args);
        }
        return new OrderResultPayload(event.cancelReason() == CancelReason.USER,
                event.cancelReason() == CancelReason.USER ? "order_cancelled" : "order_cancelled_rejected", args);
    }

    private static OrderResultPayload rejected(String code, String... args) {
        return new OrderResultPayload(false, code, List.of(args));
    }

    /** 以标的资源个数表示的数量，按合约的数量精度显示，例如 {@code 2.000}。 */
    private static String formatQuantity(long lots, int decimals) {
        return Quantities.format(lots, decimals);
    }

    /**
     * 每个行情步：先用新价格触发穿越了委托价的挂单（离线玩家的同样触发，成交 / 撤销留在存档和历史委托里），
     * 在线的玩家收到提示和最新的交易状态；然后持有仓位的在线玩家的未实现盈亏随标记价格变，把最新资产发给他们。
     */
    public static void onMarketStep(MinecraftServer server, List<MarketSnapshot> snapshots) {
        if (book == null) {
            return;
        }
        List<OrderEvent> events = new ArrayList<>();
        for (MarketSnapshot snapshot : snapshots) {
            if (ServerMarket.symbols().contains(snapshot.symbol())) {
                events.addAll(book.orders().onSnapshot(ServerMarket.spec(snapshot.symbol()), snapshot));
            }
        }
        Set<UUID> touched = new LinkedHashSet<>();
        for (OrderEvent event : events) {
            LOGGER.info("委托 #{} {}：{} {} {} 数量 {} 价格 {} 金额 {} 原因 {}", event.orderId(), event.kind(),
                    event.player(), event.side(), event.symbol(), event.lots(), event.price(), event.amount(),
                    event.cancelReason());
            touched.add(event.player());
            ServerPlayer owner = server.getPlayerList().getPlayer(event.player());
            if (owner != null) {
                ModPayloads.sendTo(owner, describe(event));
            }
        }
        if (!events.isEmpty()) {
            markDirty();
        }
        // 同一个行情步内的顺序：① 委托触发（上面）→ ② 资金费结算 → ③ 强平检查（#12）。后加的阶段按这个顺序接在后面
        touched.addAll(settleFunding(server, snapshots));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (touched.contains(player.getUUID())) {
                sendState(player);
            }
            if (touched.contains(player.getUUID()) || !book.positions(player.getUUID()).isEmpty()) {
                ModPayloads.sendTo(player, new AccountPayload(assets(player.getUUID())));
            }
        }
    }

    /**
     * 行情步的第二阶段：资金费结算。快照恰在结算点（倒计时 00:00:00）的合约，其仓位按结算时的费率与标记价格和流动性池收付
     * （规则见 {@link TradingBook#settleFunding}）。离线玩家同样结算，记录留在存档里；在线玩家实时收到提示，随后的状态同步带上新记录。
     * 市场暂停和停服期间没有行情步，也就没有结算，重启后按市场时间继续倒数，不补结算。
     * <p>
     * 逐仓保证金被扣到 0 的仓位留着不动，由随后的强平检查接手。
     *
     * @return 这一步结算过的玩家（需要重发交易状态和资产）
     */
    public static Set<UUID> settleFunding(MinecraftServer server, List<MarketSnapshot> snapshots) {
        Set<UUID> touched = new LinkedHashSet<>();
        for (MarketSnapshot snapshot : snapshots) {
            for (FundingRecord record : book().settleFunding(snapshot)) {
                LOGGER.info("资金费 #{} {} {} {} 费率 {} 标记价格 {} 名义价值 {} 金额 {} 未收付 {} 市场秒 {}", record.id(),
                        record.player(), record.side(), record.symbol(), record.rate(), record.markPrice(),
                        record.notional(), record.amount(), record.shortfall(), record.marketSeconds());
                touched.add(record.player());
                ServerPlayer owner = server.getPlayerList().getPlayer(record.player());
                if (owner != null) {
                    ModPayloads.sendTo(owner, describe(record));
                }
            }
        }
        if (!touched.isEmpty()) {
            markDirty();
        }
        return touched;
    }

    /** 资金费结算对应的玩家提示。 */
    private static OrderResultPayload describe(FundingRecord record) {
        Money moved = record.paid() ? Money.ZERO.minus(record.amount()) : record.amount();
        String rate = String.format(Locale.ROOT, "%.4f%%", record.rate() * 100);
        if (record.shortfall().isPositive()) {
            return new OrderResultPayload(false, "funding_partial", List.of(record.symbol(),
                    (record.paid() ? "-" : "+") + moved.toDisplayString(), record.shortfall().toDisplayString(), rate));
        }
        return new OrderResultPayload(true, record.paid() ? "funding_paid" : "funding_received",
                List.of(record.symbol(), moved.toDisplayString(), rate));
    }

    /** 把这名玩家的交易状态（各合约的下单参数、全部仓位、最近的成交、委托、资金费记录）发给他。 */
    public static void sendState(ServerPlayer player) {
        List<PlayerTrade> trades = book().trades(player.getUUID());
        List<PlayerTrade> recent = trades.subList(Math.max(0, trades.size() - SYNCED_TRADES), trades.size());
        List<TradingParams> contracts = ServerMarket.symbols().stream().map(s -> ServerMarket.spec(s).trading()).toList();
        List<Order> orders = new ArrayList<>(book().orders().open(player.getUUID()));
        orders.addAll(book().orders().history(player.getUUID()));
        orders.sort(Comparator.comparingLong(Order::id));
        List<String> cross = ServerMarket.symbols().stream()
                .filter(symbol -> book().marginMode(player.getUUID(), symbol) == MarginMode.CROSS).toList();
        List<FundingRecord> funding = book().fundingRecords(player.getUUID());
        List<FundingRecord> recentFunding = funding.subList(Math.max(0, funding.size() - SYNCED_TRADES), funding.size());
        ModPayloads.sendTo(player,
                new TradingStatePayload(contracts, book().positions(player.getUUID()), recent, orders, cross, recentFunding));
    }

    private static void markDirty() {
        if (data != null) {
            data.setDirty();
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        data = event.getServer().getDataStorage().computeIfAbsent(TradingSavedData.TYPE);
        book = data.bind(ServerAccounts.book());
        // 成交的源头：开平仓以及以后的强平、限价成交都经过这里，全服「最新成交」广播不挂在某个下单入口上
        MinecraftServer server = event.getServer();
        book.addTradeListener(trade -> ServerLatestTrades.onPlayerTrade(server, trade));
        LOGGER.info("交易账：流动性池 {} CRP，最近一笔玩家成交编号 {}", book.pool().balance().toDisplayString(),
                book.state().lastTradeId());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        data = null;
        book = null;
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (book != null && event.getEntity() instanceof ServerPlayer player) {
            sendState(player);
        }
    }
}
