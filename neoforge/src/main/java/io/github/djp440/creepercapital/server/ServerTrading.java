package io.github.djp440.creepercapital.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.CancelReason;
import io.github.djp440.creepercapital.core.trading.LiquidationEvent;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.MarginResult;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderEvent;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
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
        TradeResult result = submit(id, spec, order);
        // 玩家平仓可能让附带的止盈止损失效，记录下来（离线汇总在 #19 接）
        logEvents(book().orders().takeEvents());
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
        if (result instanceof TradeResult.ProtectionPlaced protection) {
            List<Order> created = protection.orders();
            created.forEach(o -> LOGGER.info("{} #{} {} {} 触发价 {}（{}）数量 {}", o.type(), o.id(),
                    player.getGameProfile().name(), o.symbol(), o.price(), o.triggerSource(),
                    formatQuantity(o.lots(), spec.quantityDecimals())));
            return new OrderResultPayload(true, "protection_placed", List.of(order.symbol(),
                    order.takeProfit() != 0 ? formatPrice(order.takeProfit()) : "--",
                    order.stopLoss() != 0 ? formatPrice(order.stopLoss()) : "--"));
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
        OrderPayload.Action action = order.action();
        boolean limit = order.execution() == OrderPayload.Execution.LIMIT;
        if (order.reduceOnly() && (action == OrderPayload.Action.OPEN_LONG || action == OrderPayload.Action.OPEN_SHORT)) {
            // 开多 / 开空是买入 / 卖出：带只减仓就是缩减反向的仓位，没有可缩减的仓位就拒绝（币安同样拒绝），绝不开仓
            Position position = book().position(id, spec.symbol()).orElse(null);
            if (position == null || position.side() == side) {
                return TradeResult.reject(TradeResult.Reason.NO_POSITION);
            }
            action = OrderPayload.Action.CLOSE;
        }
        return switch (action) {
            case OPEN_LONG, OPEN_SHORT -> limit
                    ? book().orders().placeLimit(id, spec, snapshot, side, order.lots(), order.leverage(), order.price(),
                            order.protection(), ServerTrading::markPrice)
                    : book().orders().openWithProtection(id, spec, snapshot, side, order.lots(), order.leverage(),
                            order.protection(), ServerTrading::markPrice);
            case CLOSE -> limit
                    ? book().orders().placeReduceOnlyLimit(id, spec, snapshot, order.lots(), order.price())
                    : order.reduceOnly() ? book().closeUpTo(id, spec, snapshot, order.lots())
                            : book().close(id, spec, snapshot, order.lots());
            case PROTECT -> book().orders().placeProtection(id, spec, snapshot, order.lots(), order.protection());
            default -> throw new IllegalStateException("不是成交动作：" + order.action());
        };
    }

    private static void logEvents(List<OrderEvent> events) {
        for (OrderEvent event : events) {
            LOGGER.info("委托 #{} {}：{} {} {} {} 数量 {} 价格 {} 金额 {} 盈亏 {} 原因 {}", event.orderId(), event.kind(),
                    event.player(), event.type(), event.side(), event.symbol(), event.lots(), event.price(), event.amount(),
                    event.realizedPnl(), event.cancelReason());
        }
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
        OrderResultPayload notice = describe(event.get());
        if (notice != null) {
            ModPayloads.sendTo(player, notice);
        }
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

    /**
     * 委托事件对应的玩家提示；触发（紧接着就是成交）和仓位平掉后的自动失效不单独提示，免得盖掉成交那一条，
     * 它们在历史委托里看得到。
     */
    private static @Nullable OrderResultPayload describe(OrderEvent event) {
        if (event.kind() == OrderEvent.Kind.TRIGGERED || event.cancelReason() == CancelReason.POSITION_CLOSED) {
            return null;
        }
        int decimals = ServerMarket.symbols().contains(event.symbol())
                ? ServerMarket.spec(event.symbol()).quantityDecimals() : 0;
        List<String> args = List.of(event.symbol(), formatQuantity(event.lots(), decimals), formatPrice(event.price()),
                event.amount().toDisplayString(), event.realizedPnl().toDisplayString());
        if (event.kind() == OrderEvent.Kind.FILLED) {
            String code = switch (event.type()) {
                case TAKE_PROFIT -> "take_profit_filled";
                case STOP_LOSS -> "stop_loss_filled";
                case LIMIT -> "limit_filled";
            };
            return new OrderResultPayload(true, code, args);
        }
        String code = switch (event.cancelReason()) {
            case USER -> "order_cancelled";
            case LIQUIDATED -> "order_cancelled_liquidated";
            default -> "order_cancelled_rejected";
        };
        return new OrderResultPayload(event.cancelReason() == CancelReason.USER, code, args);
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
    static void onMarketStep(MinecraftServer server, List<MarketSnapshot> snapshots) {
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
            touched.add(event.player());
            ServerPlayer owner = server.getPlayerList().getPlayer(event.player());
            OrderResultPayload notice = describe(event);
            if (owner != null && notice != null) {
                ModPayloads.sendTo(owner, notice);
            }
        }
        logEvents(events);
        if (!events.isEmpty()) {
            markDirty();
        }
        // 同一行情步内的顺序约定：① 委托触发（上面）→ ② 资金费结算（#13，接在这里）→ ③ 强平检查
        liquidationStage(server, snapshots, touched);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (touched.contains(player.getUUID())) {
                sendState(player);
            }
            if (touched.contains(player.getUUID()) || !book.positions(player.getUUID()).isEmpty()) {
                ModPayloads.sendTo(player, new AccountPayload(assets(player.getUUID())));
            }
        }
    }

    /** 行情步的第 ③ 阶段：用这一步的标记价格检查全部仓位（离线玩家同样检查），强平的玩家记入 {@code touched}。 */
    private static void liquidationStage(MinecraftServer server, List<MarketSnapshot> snapshots, Set<UUID> touched) {
        if (snapshots.isEmpty()) {
            return;
        }
        liquidate(server, snapshots.get(0).time().totalSeconds(), ServerTrading::markPrice, player -> true, touched);
    }

    /**
     * 强平检查并通知。{@code players} 限定检查哪些玩家的仓位：生产代码检查所有人；GameTest 共用一个活账本，
     * 用它把人为注入的极端价格限制在测试自己的玩家身上。
     */
    public static List<LiquidationEvent> liquidate(MinecraftServer server, long marketSeconds,
            ToDoubleFunction<String> markPrice, Predicate<UUID> players, Set<UUID> touched) {
        List<LiquidationEvent> events = book().checkLiquidations(players, markPrice,
                symbol -> ServerMarket.spec(symbol).marginTiers(), marketSeconds);
        for (LiquidationEvent event : events) {
            LOGGER.info("强平 {} {} {}：{} 抵押损失 {} 进基金 {} 穿仓 {}（基金垫付 {}，池子承担 {}），同时撤销挂单 {} 张", event.player(),
                    event.mode(), event.symbols(), event.positions().stream()
                            .map(p -> p.side() + " " + p.lots() + " @ " + p.price() + " 盈亏 " + p.realizedPnl() + " 清算费 " + p.fee())
                            .toList(),
                    event.collateralLost(), event.toFund(), event.shortfall(), event.fundCovered(), event.poolBorne(),
                    event.cancelledOrders().size());
            touched.add(event.player());
            ServerPlayer owner = server.getPlayerList().getPlayer(event.player());
            if (owner == null) {
                continue;
            }
            ModPayloads.sendTo(owner, new OrderResultPayload(false, "liquidated",
                    List.of(String.join(", ", event.symbols()), event.collateralLost().toDisplayString())));
            for (OrderEvent cancelled : event.cancelledOrders()) {
                OrderResultPayload notice = describe(cancelled);
                if (notice != null) {
                    ModPayloads.sendTo(owner, notice);
                }
            }
        }
        if (!events.isEmpty()) {
            markDirty();
        }
        return events;
    }

    /** 把这名玩家的交易状态（各合约的下单参数、全部仓位、最近的成交、委托）发给他。 */
    public static void sendState(ServerPlayer player) {
        List<PlayerTrade> trades = book().trades(player.getUUID());
        List<PlayerTrade> recent = trades.subList(Math.max(0, trades.size() - SYNCED_TRADES), trades.size());
        List<TradingParams> contracts = ServerMarket.symbols().stream().map(s -> ServerMarket.spec(s).trading()).toList();
        List<Order> orders = new ArrayList<>(book().orders().open(player.getUUID()));
        orders.addAll(book().orders().history(player.getUUID()));
        orders.sort(Comparator.comparingLong(Order::id));
        List<String> cross = ServerMarket.symbols().stream()
                .filter(symbol -> book().marginMode(player.getUUID(), symbol) == MarginMode.CROSS).toList();
        ModPayloads.sendTo(player,
                new TradingStatePayload(contracts, book().positions(player.getUUID()), recent, orders, cross));
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
