package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.market.MarginTiers;
import io.github.djp440.creepercapital.core.market.MarketSnapshot;
import io.github.djp440.creepercapital.core.market.TradingParams;
import io.github.djp440.creepercapital.core.trading.MarginMath;
import io.github.djp440.creepercapital.core.trading.MarginMode;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderStatus;
import io.github.djp440.creepercapital.core.trading.PlayerTrade;
import io.github.djp440.creepercapital.core.trading.Position;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.network.CancelOrderPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.network.TradingStatePayload;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 客户端缓存的本人交易状态（各合约的下单参数、仓位、成交历史、委托）和最近一次下单的结果，只由服务端同步过来的数据更新；离开服务器时清空。
 * 另外记着玩家在下单面板上给每个合约选的杠杆（只是界面选择，下单时由服务端校验）。
 */
public final class ClientTrading {
    /** 杠杆选择器的档位（超过合约最高杠杆的档位不可选，最高杠杆本身总可选）。 */
    private static final int[] LEVERAGE_STEPS = {1, 2, 3, 5, 10, 20, 25, 50, 75, 100, 125};
    private static final int DEFAULT_LEVERAGE = 10;

    /** 最近一次下单的结果：成交或拒单的文案。 */
    public record Message(boolean success, Component text) {
    }

    private static final Map<String, TradingParams> PARAMS = new HashMap<>();
    private static final Map<String, Integer> SELECTED_LEVERAGE = new HashMap<>();
    private static List<Position> positions = List.of();
    private static List<PlayerTrade> trades = List.of();
    /** 玩家设为全仓的合约（没有仓位时，保证金模式只在这里）。 */
    private static Set<String> crossSymbols = Set.of();
    private static List<Order> orders = List.of();
    /** 下单面板当前选的是限价（否则市价）；只是界面选择。 */
    private static boolean limitMode;
    /** 下单面板当前展开的是止盈止损设置页（否则是预估页）、止盈止损的触发价来源、只减仓开关；都只是界面选择。 */
    private static boolean protectionPage;
    private static TriggerSource triggerSource = TriggerSource.LAST;
    private static boolean reduceOnly;
    private static @Nullable Message lastResult;

    private ClientTrading() {
    }

    static void accept(TradingStatePayload payload) {
        PARAMS.clear();
        payload.contracts().forEach(params -> PARAMS.put(params.symbol(), params));
        positions = List.copyOf(payload.positions());
        trades = List.copyOf(payload.trades());
        crossSymbols = Set.copyOf(payload.crossSymbols());
        orders = List.copyOf(payload.orders());
    }

    static void accept(OrderResultPayload payload) {
        lastResult = new Message(payload.success(),
                Component.translatable("message.creepercapital.order." + payload.code(), payload.args().toArray()));
    }

    static void clear() {
        PARAMS.clear();
        SELECTED_LEVERAGE.clear();
        positions = List.of();
        trades = List.of();
        crossSymbols = Set.of();
        orders = List.of();
        limitMode = false;
        protectionPage = false;
        triggerSource = TriggerSource.LAST;
        reduceOnly = false;
        lastResult = null;
    }

    public static Optional<TradingParams> params(String symbol) {
        return Optional.ofNullable(PARAMS.get(symbol));
    }

    /** 全部仓位。 */
    public static List<Position> positions() {
        return positions;
    }

    public static Optional<Position> position(String symbol) {
        return positions.stream().filter(position -> position.symbol().equals(symbol)).findFirst();
    }

    /** 最近的成交，最新的在前。 */
    public static List<PlayerTrade> tradesNewestFirst() {
        List<PlayerTrade> newestFirst = new ArrayList<>(trades);
        java.util.Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** 挂着的委托（当前委托），最新的在前。 */
    public static List<Order> openOrdersNewestFirst() {
        return newestFirst(OrderStatus.OPEN, true);
    }

    /** 已成交和已撤销的委托（历史委托），最新的在前。 */
    public static List<Order> orderHistoryNewestFirst() {
        return newestFirst(OrderStatus.OPEN, false);
    }

    private static List<Order> newestFirst(OrderStatus status, boolean matching) {
        List<Order> selected = new ArrayList<>();
        for (Order order : orders) {
            if ((order.status() == status) == matching) {
                selected.add(order);
            }
        }
        java.util.Collections.reverse(selected);
        return selected;
    }

    public static boolean limitMode() {
        return limitMode;
    }

    public static void setLimitMode(boolean limit) {
        limitMode = limit;
    }

    public static boolean protectionPage() {
        return protectionPage;
    }

    public static void setProtectionPage(boolean protection) {
        protectionPage = protection;
    }

    public static TriggerSource triggerSource() {
        return triggerSource;
    }

    /** 在最新价格与标记价格之间切换止盈止损的触发价来源。 */
    public static void toggleTriggerSource() {
        triggerSource = triggerSource == TriggerSource.LAST ? TriggerSource.MARK : TriggerSource.LAST;
    }

    public static void setTriggerSource(TriggerSource source) {
        triggerSource = source;
    }

    public static boolean reduceOnly() {
        return reduceOnly;
    }

    public static void setReduceOnly(boolean value) {
        reduceOnly = value;
    }

    public static @Nullable Message lastResult() {
        return lastResult;
    }

    /** 面板自己发现的错误（例如数量格式不对），不用发给服务端。 */
    static void showLocalError(Component text) {
        lastResult = new Message(false, text);
    }

    /** 开发用（自动截图）：清掉面板上的下单结果。 */
    public static void clearResult() {
        lastResult = null;
    }

    /** 这个合约现在下单用的杠杆：已有仓位就是仓位的杠杆（杠杆不能和仓位不同），否则是玩家选的，默认 10 倍。 */
    public static int leverage(String symbol) {
        Optional<Position> position = position(symbol);
        if (position.isPresent()) {
            return position.get().leverage();
        }
        int max = maxLeverage(symbol);
        return Math.min(SELECTED_LEVERAGE.getOrDefault(symbol, DEFAULT_LEVERAGE), max);
    }

    /** 这个合约的保证金模式：有仓位以仓位的为准，否则是玩家设置的（缺省逐仓）。 */
    public static MarginMode marginMode(String symbol) {
        Optional<Position> position = position(symbol);
        if (position.isPresent()) {
            return position.get().mode();
        }
        return crossSymbols.contains(symbol) ? MarginMode.CROSS : MarginMode.ISOLATED;
    }

    /**
     * 请求切换保证金模式（逐仓 ↔ 全仓）；有仓位或委托时服务端会拒绝并给出提示。
     */
    public static void toggleMarginMode(String symbol) {
        ClientPacketDistributor.sendToServer(new OrderPayload(
                marginMode(symbol) == MarginMode.CROSS ? OrderPayload.Action.SET_ISOLATED : OrderPayload.Action.SET_CROSS,
                symbol, 0, leverage(symbol)));
    }

    /** 杠杆调高一档：没有仓位时只改面板上的选择，有仓位时请求服务端调整仓位的杠杆。 */
    public static void raiseLeverage(String symbol) {
        int current = leverage(symbol);
        int max = maxLeverage(symbol);
        int target = max;
        for (int step : LEVERAGE_STEPS) {
            if (step > current && step <= max) {
                target = step;
                break;
            }
        }
        changeLeverage(symbol, target);
    }

    /** 杠杆调低一档：没有仓位时只改面板上的选择，有仓位时请求服务端调整仓位的杠杆。 */
    public static void lowerLeverage(String symbol) {
        int current = leverage(symbol);
        int lower = 1;
        for (int step : LEVERAGE_STEPS) {
            if (step < current) {
                lower = step;
            }
        }
        changeLeverage(symbol, lower);
    }

    private static void changeLeverage(String symbol, int target) {
        if (position(symbol).isPresent()) {
            ClientPacketDistributor.sendToServer(new OrderPayload(OrderPayload.Action.SET_LEVERAGE, symbol, 0, target));
        } else {
            SELECTED_LEVERAGE.put(symbol, target);
        }
    }

    /**
     * 本人全部仓位按各合约当前标记价格算的风险指标（维持保证金、保证金率、预估强平价），以交易代码为键。
     * 与服务端强平用的是同一份 {@link MarginMath}，只是输入来自同步过来的仓位、钱包余额和分档。
     */
    public static Map<String, MarginMath.PositionRisk> risks() {
        return MarginMath.assess(positions, ClientAccount.assets().walletBalance(),
                symbol -> ClientMarket.snapshot(symbol).map(MarketSnapshot::markPrice).orElse(Double.NaN),
                symbol -> params(symbol).map(TradingParams::marginTiers).orElseGet(() -> MarginTiers.standard(20)));
    }

    /** 直接设定杠杆（开发用：自动截图）。 */
    public static void selectLeverage(String symbol, int leverage) {
        SELECTED_LEVERAGE.put(symbol, Math.max(1, Math.min(leverage, maxLeverage(symbol))));
    }

    private static int maxLeverage(String symbol) {
        return params(symbol).map(TradingParams::maxLeverage).orElse(DEFAULT_LEVERAGE);
    }

    /** 发出一笔市价委托；结果由服务端回报。 */
    public static void send(OrderPayload.Action action, String symbol, long lots) {
        ClientPacketDistributor.sendToServer(new OrderPayload(action, symbol, lots, leverage(symbol)));
    }

    /** 发出一笔限价开仓委托；结果由服务端回报。 */
    public static void sendLimit(OrderPayload.Action action, String symbol, long lots, double price) {
        ClientPacketDistributor.sendToServer(new OrderPayload(action, symbol, lots, leverage(symbol),
                OrderPayload.Execution.LIMIT, price));
    }

    /**
     * 发出一笔委托（市价或限价），可附带止盈止损；触发价来源与只减仓取下单面板上的选择。结果由服务端回报。
     *
     * @param price      限价委托的委托价；市价为 0
     * @param takeProfit 止盈价，0 为不设
     * @param stopLoss   止损价，0 为不设
     */
    public static void sendOrder(OrderPayload.Action action, String symbol, long lots, OrderPayload.Execution execution,
            double price, double takeProfit, double stopLoss) {
        ClientPacketDistributor.sendToServer(new OrderPayload(action, symbol, lots, leverage(symbol), execution, price,
                takeProfit, stopLoss, triggerSource, reduceOnly));
    }

    /** 撤销一张挂单；结果由服务端回报。 */
    public static void cancel(long orderId) {
        ClientPacketDistributor.sendToServer(new CancelOrderPayload(orderId));
    }
}
