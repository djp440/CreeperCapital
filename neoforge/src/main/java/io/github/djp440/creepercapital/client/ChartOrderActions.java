package io.github.djp440.creepercapital.client;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.network.CancelOrderPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 从图表上发出的委托操作（拖拽设 / 改止盈止损、点「×」撤销当前委托）还在等服务端回报的状态，以及图表上显示的操作结果提示。
 * 只走现有通道（{@link OrderPayload} 的 {@code PROTECT}、{@link CancelOrderPayload}），回报照常经 {@link ClientTrading} 到达，
 * 这里只跟踪图表自己发出的那几笔。
 * <p>
 * 撤销：点「×」立即发撤单（不二次确认，与表格里的撤单按钮一致），等回报期间那张委托的 tag 置灰、「×」点不动；
 * 撤销成功后服务端同步的当前委托里没有它了，线与 tag 随之消失；失败（委托已成交 / 已失效）或超时则恢复并提示。
 * <p>
 * 改单：现有通道没有「改单」，按<b>先挂新、成功后再撤旧</b>处理——新单被拒（价格已失效、委托数已满、市场暂停……）时旧单原样保留，
 * 不会两头落空；撤旧失败（旧单恰好已触发）时新单仍在，提示玩家核对。两步之间新旧两张都挂着，它们都是只减仓，
 * 先触发的那张平掉仓位后另一张自动失效，不会多平。
 */
final class ChartOrderActions {
    /** 等回报的上限（真实时间）；超时提示玩家去表格里核对。 */
    static final long TIMEOUT_MILLIS = 5_000;
    /** 提示显示多久。 */
    static final long NOTICE_MILLIS = 3_500;

    /**
     * 已提交、还在等回报的止盈 / 止损（图上画成灰色虚线）。
     *
     * @param symbol    交易代码
     * @param type      止盈或止损
     * @param price     触发价
     * @param replacing 改单时被替换的旧委托编号；新设为 0
     * @param sentAt    发出时刻（真实毫秒）
     */
    record Pending(String symbol, OrderType type, double price, long replacing, long sentAt) {
    }

    /** 一张正在撤的委托：发出时刻，以及是不是改单时撤旧单（撤旧单不置灰显示，而是直接不画）。 */
    private record Cancel(long sentAt, boolean replaced) {
    }

    /** 图表上显示的一条操作结果。 */
    record Notice(Component text, boolean error, long shownAt) {
    }

    private static @Nullable Pending pending;
    /** 正在撤的委托（按发出先后）：点「×」撤的，和改单成功后撤的旧单。 */
    private static final Map<Long, Cancel> cancels = new LinkedHashMap<>();
    private static @Nullable Notice notice;

    private ChartOrderActions() {
    }

    /** 提交一笔止盈 / 止损（{@code replacing} 非 0 时是改单：新单挂上之后再撤掉它）。 */
    static void submit(OrderPayload payload, OrderType type, double price, long replacing) {
        pending = new Pending(payload.symbol(), type, price, replacing, System.currentTimeMillis());
        ClientPacketDistributor.sendToServer(payload);
    }

    static @Nullable Pending pending() {
        return pending;
    }

    /** 点「×」撤销一张当前委托；正在撤的不重复发。 */
    static void cancel(long orderId) {
        if (cancels.containsKey(orderId)) {
            return;
        }
        cancels.put(orderId, new Cancel(System.currentTimeMillis(), false));
        ClientPacketDistributor.sendToServer(new CancelOrderPayload(orderId));
    }

    /** 这张委托正被「×」撤销、在等回报：tag 置灰。 */
    static boolean cancelling(long orderId) {
        Cancel cancel = cancels.get(orderId);
        return cancel != null && !cancel.replaced();
    }

    /** 这张委托正在被改单替换（新单在等回报，或已挂上、正在撤旧单）：图上不再画它。 */
    static boolean replacing(long orderId) {
        Cancel cancel = cancels.get(orderId);
        return (cancel != null && cancel.replaced()) || (pending != null && pending.replacing() == orderId);
    }

    static void showNotice(Component text, boolean error) {
        notice = new Notice(text, error, System.currentTimeMillis());
    }

    /** 此刻要显示的提示；过期了为 null。顺带处理等回报超时。 */
    static @Nullable Notice notice(long now) {
        if (pending != null && now - pending.sentAt() > TIMEOUT_MILLIS) {
            pending = null;
            showNotice(Component.translatable("screen.creepercapital.trading.chart.no_response"), true);
        }
        Iterator<Cancel> waiting = cancels.values().iterator();
        while (waiting.hasNext()) {
            Cancel cancel = waiting.next();
            if (now - cancel.sentAt() > TIMEOUT_MILLIS) {
                waiting.remove();
                if (!cancel.replaced()) {
                    showNotice(Component.translatable("screen.creepercapital.trading.chart.no_response"), true);
                }
            }
        }
        if (notice != null && now - notice.shownAt() > NOTICE_MILLIS) {
            notice = null;
        }
        return notice;
    }

    /**
     * 收到一条下单 / 撤单回报（由 {@link ClientTrading} 转来）。等着的止盈止损：设置成功则清掉等待（改单时接着撤旧单），
     * 被拒则提示原因（改单时旧单不动）。成交、触发、强平之类与本次操作无关的推送不影响等待。
     */
    static void onResult(boolean success, String code, Component text) {
        if (pending != null) {
            if (success && code.equals("protection_placed")) {
                long old = pending.replacing();
                pending = null;
                if (old != 0) {
                    cancels.put(old, new Cancel(System.currentTimeMillis(), true));
                    ClientPacketDistributor.sendToServer(new CancelOrderPayload(old));
                    showNotice(Component.translatable("screen.creepercapital.trading.chart.modified"), false);
                } else {
                    showNotice(text, false);
                }
                return;
            }
            if (!success && isOrderRejection(code)) {
                pending = null;
                showNotice(text, true);
                return;
            }
        }
        if (!cancels.isEmpty() && code.equals("no_such_order")) {
            // 撤单失败（委托恰好已成交 / 已失效）：回报不带编号，按发出顺序认领最早的那一笔
            Iterator<Cancel> oldest = cancels.values().iterator();
            Cancel failed = oldest.next();
            oldest.remove();
            showNotice(failed.replaced() ? Component.translatable("screen.creepercapital.trading.chart.replace_cancel_failed")
                    : Component.translatable("screen.creepercapital.trading.chart.cancel_failed", text), true);
        }
    }

    /** 是下单被拒（而不是撤单 / 委托失效之类的推送）。 */
    private static boolean isOrderRejection(String code) {
        return !code.startsWith("order_cancelled") && !code.equals("no_such_order") && !code.endsWith("_filled")
                && !code.startsWith("liquidat");
    }

    /** 收到新的交易状态：已经不在当前委托里的（撤销成功）不再跟踪，线与 tag 随之消失。 */
    static void onState(List<Order> orders) {
        Set<Long> open = orders.stream().filter(Order::isOpen).map(Order::id).collect(Collectors.toSet());
        cancels.keySet().retainAll(open);
    }

    /** 开发用（自动截图）：把这张委托显示成「撤销中」（不发撤单）。 */
    static void devMarkCancelling(long orderId) {
        cancels.put(orderId, new Cancel(Long.MAX_VALUE / 2, false));
    }

    static void clear() {
        pending = null;
        cancels.clear();
        notice = null;
    }
}
