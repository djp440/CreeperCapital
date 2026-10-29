package io.github.djp440.creepercapital.server;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.djp440.creepercapital.core.account.Assets;
import io.github.djp440.creepercapital.core.account.Money;
import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Quantities;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TradeKind;
import io.github.djp440.creepercapital.core.trading.TradeResult;
import io.github.djp440.creepercapital.core.trading.TradingBook;
import io.github.djp440.creepercapital.network.AccountPayload;
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
 * 服务端的交易：玩家的市价委托、仓位、成交历史与流动性池，随世界存档保存（{@link TradingSavedData}）。
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
        TradeResult result = switch (order.action()) {
            case OPEN_LONG -> book().open(id, spec, ServerMarket.snapshot(order.symbol()), Side.LONG, order.lots(),
                    order.leverage());
            case OPEN_SHORT -> book().open(id, spec, ServerMarket.snapshot(order.symbol()), Side.SHORT, order.lots(),
                    order.leverage());
            case CLOSE -> book().close(id, spec, ServerMarket.snapshot(order.symbol()), order.lots());
        };
        if (result instanceof TradeResult.Rejected rejected) {
            LOGGER.debug("拒绝 {} 的委托 {}：{} {}", player.getGameProfile().name(), order, rejected.reason(), rejected.args());
            return rejected(rejected.reason().name().toLowerCase(Locale.ROOT), rejected.args().toArray(String[]::new));
        }
        PlayerTrade trade = ((TradeResult.Filled) result).trade();
        LOGGER.info("玩家成交 #{} {} {} {} {} 数量 {} 价格 {} 手续费 {} 已实现盈亏 {}", trade.id(), player.getGameProfile().name(),
                trade.kind(), trade.side(), trade.symbol(), formatQuantity(trade.lots(), spec.quantityDecimals()),
                trade.price(), trade.fee(), trade.realizedPnl());
        return filled(trade, spec);
    }

    private static OrderResultPayload filled(PlayerTrade trade, ContractSpec spec) {
        String code = trade.kind() == TradeKind.CLOSE ? "close" : trade.side() == Side.LONG ? "open_long" : "open_short";
        return new OrderResultPayload(true, code,
                List.of(trade.symbol(), formatQuantity(trade.lots(), spec.quantityDecimals()),
                        String.format(Locale.ROOT, "%,.2f", trade.price()), trade.fee().toDisplayString(),
                        trade.realizedPnl().toDisplayString()));
    }

    private static OrderResultPayload rejected(String code, String... args) {
        return new OrderResultPayload(false, code, List.of(args));
    }

    /** 以标的资源个数表示的数量，按合约的数量精度显示，例如 {@code 2.000}。 */
    private static String formatQuantity(long lots, int decimals) {
        return Quantities.format(lots, decimals);
    }

    /** 每个行情步：持有仓位的在线玩家的未实现盈亏随标记价格变，把最新资产发给他们。 */
    static void onMarketStep(MinecraftServer server) {
        if (book == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!book.positions(player.getUUID()).isEmpty()) {
                ModPayloads.sendTo(player, new AccountPayload(assets(player.getUUID())));
            }
        }
    }

    /** 把这名玩家的交易状态（各合约的下单参数、全部仓位、最近的成交）发给他。 */
    public static void sendState(ServerPlayer player) {
        List<PlayerTrade> trades = book().trades(player.getUUID());
        List<PlayerTrade> recent = trades.subList(Math.max(0, trades.size() - SYNCED_TRADES), trades.size());
        List<TradingParams> contracts = ServerMarket.symbols().stream().map(s -> ServerMarket.spec(s).trading()).toList();
        ModPayloads.sendTo(player, new TradingStatePayload(contracts, book().positions(player.getUUID()), recent));
    }

    private static void markDirty() {
        if (data != null) {
            data.setDirty();
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        data = event.getServer().getDataStorage().computeIfAbsent(TradingSavedData.TYPE);
        book = data.bind(ServerAccounts.book());
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
