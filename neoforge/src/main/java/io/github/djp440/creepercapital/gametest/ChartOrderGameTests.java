package io.github.djp440.creepercapital.gametest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.core.market.ContractSpec;
import io.github.djp440.creepercapital.core.trading.Order;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.Protection;
import io.github.djp440.creepercapital.core.trading.Side;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.network.CancelOrderPayload;
import io.github.djp440.creepercapital.network.OrderPayload;
import io.github.djp440.creepercapital.network.OrderResultPayload;
import io.github.djp440.creepercapital.server.ServerMarket;
import io.github.djp440.creepercapital.server.ServerTrading;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * 图表上的委托操作发出的载荷（#36 拖拽设 / 改止盈止损、#37 tag 上「×」撤单），走的都是现有通道：服务端照常接受或拒绝。
 * 拖拽 → 价格的换算、止盈 / 止损的区分在 neoforge 的 JUnit（{@code ProtectionDragTest}）；这里验证这些价格组成的载荷到了服务端的结果，
 * 以及客户端判定合法性用的 {@link Protection#validTrigger} 与服务端的实际裁决一致。
 */
final class ChartOrderGameTests {
    private static final String DIAMOND = "DIAMONDCRP";

    private ChartOrderGameTests() {
    }

    static void register(BiConsumer<String, Consumer<GameTestHelper>> function) {
        function.accept("chart_drag_protection_payloads_are_accepted_or_rejected_like_the_client_predicts",
                ChartOrderGameTests::dragPayloadsAcceptedOrRejected);
        function.accept("chart_drag_modify_places_the_new_order_before_cancelling_the_old_one",
                ChartOrderGameTests::modifyPlacesNewThenCancelsOld);
        function.accept("chart_cancel_button_cancels_limit_and_protection_orders",
                ChartOrderGameTests::cancelButtonCancelsOrders);
    }

    private static ContractSpec spec() {
        return ServerMarket.spec(DIAMOND);
    }

    private static long lots() {
        return BigDecimal.TEN.pow(spec().quantityDecimals()).longValueExact();
    }

    private static double last() {
        return ServerMarket.snapshot(DIAMOND).lastPrice();
    }

    /** 按图上显示精度（2 位小数）取整的价格，和拖拽换算出来的一样。 */
    private static double rounded(double price) {
        return BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static OrderResultPayload result(ServerPlayer player) {
        return TradingGameTests.last(MockClient.received(player, OrderResultPayload.class));
    }

    private static void openLong(ServerPlayer player) {
        ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(),
                Math.min(10, spec().maxLeverage())));
    }

    /**
     * 多仓上拖出的各种价格：图表提交的是「整个仓位、只填一项触发价」的 PROTECT 载荷；服务端接受与否与客户端的
     * {@link Protection#validTrigger}（拖拽时的合法性判断）一致——参考价之上的止盈、之下的止损接受，反过来拒绝并给出参考价。
     */
    private static void dragPayloadsAcceptedOrRejected(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "ChartTrader1", "100000");
        try {
            UUID id = player.getUUID();
            openLong(player);
            long positionLots = ServerTrading.book().position(id, DIAMOND).orElseThrow().lots();
            double reference = last();
            double[] factors = {1.03, 0.97, 1.001, 0.999};
            int accepted = 0;
            for (OrderType type : List.of(OrderType.TAKE_PROFIT, OrderType.STOP_LOSS)) {
                for (double factor : factors) {
                    double price = rounded(reference * factor);
                    boolean predicted = Protection.validTrigger(Side.LONG, type, price, reference);
                    int before = ServerTrading.book().orders().open(id).size();

                    ServerTrading.handleOrder(player, OrderPayload.protect(DIAMOND, positionLots, type == OrderType.TAKE_PROFIT,
                            price, TriggerSource.LAST));

                    OrderResultPayload result = result(player);
                    String label = type + " @ " + price + "（参考价 " + reference + "）";
                    helper.assertValueEqual(result.success(), predicted, "服务端裁决与客户端预判一致：" + label);
                    if (predicted) {
                        accepted++;
                        helper.assertValueEqual(result.code(), "protection_placed", "接受：" + label);
                        Order created = TradingGameTests.last(ServerTrading.book().orders().open(id));
                        helper.assertValueEqual(created.type(), type, "生成的委托类型");
                        helper.assertValueEqual(created.price(), price, "触发价就是拖到的价格");
                        helper.assertValueEqual(created.lots(), positionLots, "数量默认整个仓位");
                        helper.assertValueEqual(created.triggerSource(), TriggerSource.LAST, "触发价来源");
                    } else {
                        helper.assertValueEqual(result.code(), "invalid_trigger_price", "拒绝：" + label);
                        helper.assertValueEqual(ServerTrading.book().orders().open(id).size(), before, "拒绝不生成委托");
                    }
                }
            }
            helper.assertValueEqual(accepted, 4, "止盈在上、止损在下的 4 个价格被接受");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /**
     * 拖动已有的止盈改价：现有通道没有改单，图表按「先挂新、成功后撤旧」发两步。
     * 新价合法：新单挂上、旧单撤掉，最后只剩新的一张；新价不合法（拖过了参考价）：新单被拒，旧单原样保留，不会两头落空。
     */
    private static void modifyPlacesNewThenCancelsOld(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "ChartTrader2", "100000");
        try {
            UUID id = player.getUUID();
            openLong(player);
            long positionLots = ServerTrading.book().position(id, DIAMOND).orElseThrow().lots();
            double reference = last();
            ServerTrading.handleOrder(player, OrderPayload.protect(DIAMOND, positionLots, true, rounded(reference * 1.05),
                    TriggerSource.LAST));
            Order old = ServerTrading.book().orders().open(id).get(0);

            // 不合法的新价：止盈拖到了参考价之下
            ServerTrading.handleOrder(player, OrderPayload.protect(DIAMOND, old.lots(), true, rounded(reference * 0.95),
                    old.triggerSource()));
            helper.assertValueEqual(result(player).code(), "invalid_trigger_price", "新单被拒");
            helper.assertValueEqual(ServerTrading.book().orders().open(id).stream().map(Order::id).toList(), List.of(old.id()),
                    "新单被拒时不撤旧单，旧单原样保留");

            // 合法的新价：先挂新，成功后再撤旧
            double moved = rounded(reference * 1.08);
            ServerTrading.handleOrder(player, OrderPayload.protect(DIAMOND, old.lots(), true, moved, old.triggerSource()));
            helper.assertValueEqual(result(player).code(), "protection_placed", "新单挂上");
            helper.assertValueEqual(ServerTrading.book().orders().open(id).size(), 2, "撤旧之前新旧两张都在");
            ServerTrading.handleCancel(player, new CancelOrderPayload(old.id()));
            helper.assertValueEqual(result(player).code(), "order_cancelled", "旧单撤掉");
            List<Order> open = ServerTrading.book().orders().open(id);
            helper.assertValueEqual(open.size(), 1, "最后只剩一张止盈");
            helper.assertValueEqual(open.get(0).price(), moved, "是改过价的那张");
            helper.assertValueEqual(open.get(0).lots(), old.lots(), "数量沿用旧单");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }

    /**
     * tag 上的「×」发的是现有的 {@link CancelOrderPayload}：挂单和止盈止损都能撤（order_cancelled，线随同步消失）；
     * 已经不在的委托（重复点、刚好成交）回报 no_such_order，客户端据此提示「撤销失败」。
     */
    private static void cancelButtonCancelsOrders(GameTestHelper helper) {
        ServerPlayer player = TradingGameTests.trader(helper, "ChartTrader3", "100000");
        try {
            UUID id = player.getUUID();
            openLong(player);
            long positionLots = ServerTrading.book().position(id, DIAMOND).orElseThrow().lots();
            double reference = last();
            ServerTrading.handleOrder(player, OrderPayload.protect(DIAMOND, positionLots, true, rounded(reference * 1.05),
                    TriggerSource.LAST));
            ServerTrading.handleOrder(player, new OrderPayload(OrderPayload.Action.OPEN_LONG, DIAMOND, lots(),
                    Math.min(10, spec().maxLeverage()), OrderPayload.Execution.LIMIT, rounded(reference * 0.9)));
            List<Order> open = ServerTrading.book().orders().open(id);
            helper.assertValueEqual(open.size(), 2, "一张止盈、一张限价挂单");

            for (Order order : open) {
                ServerTrading.handleCancel(player, new CancelOrderPayload(order.id()));
                helper.assertValueEqual(result(player).code(), "order_cancelled", "撤销 " + order.type());
            }
            helper.assertValueEqual(ServerTrading.book().orders().open(id).size(), 0, "都撤掉了");

            ServerTrading.handleCancel(player, new CancelOrderPayload(open.get(0).id()));
            OrderResultPayload again = result(player);
            helper.assertValueEqual(again.success(), false, "再撤一次失败");
            helper.assertValueEqual(again.code(), "no_such_order", "已经不在的委托");
        } finally {
            TradingGameTests.finish(player);
        }
        helper.succeed();
    }
}
