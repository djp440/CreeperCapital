package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.trading.Protection;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端 → 服务端：玩家的委托意图（开多、开空、平仓、给仓位设止盈止损；市价或限价），以及切换保证金模式、调整杠杆。服务端校验一切，不信任客户端；
 * 平仓、设止盈止损时杠杆字段被忽略，市价单时价格字段被忽略；保证金设置（SET_*）只看合约，调杠杆时只看杠杆字段。
 * <p>
 * 止盈止损：开仓（市价、限价）时附带，随开仓一起生效；{@link Action#PROTECT} 是给已有仓位补设。
 * 只减仓：开多 / 开空带只减仓是“买入 / 卖出缩减反向仓位”，没有可缩减的仓位服务端拒绝；平仓永远是只减仓，限价平仓就是只减仓限价单。
 *
 * @param action        动作
 * @param symbol        交易代码
 * @param lots          数量，以合约的最小数量单位（手）计
 * @param leverage      杠杆倍数
 * @param execution     市价还是限价
 * @param price         限价委托的委托价
 * @param takeProfit    止盈触发价（0 = 不设）
 * @param stopLoss      止损触发价（0 = 不设）
 * @param triggerSource 止盈止损的触发价来源
 * @param reduceOnly    只减仓
 */
public record OrderPayload(Action action, String symbol, long lots, int leverage, Execution execution, double price,
        double takeProfit, double stopLoss, TriggerSource triggerSource, boolean reduceOnly)
        implements CustomPacketPayload {
    public static final Type<OrderPayload> TYPE = new Type<>(CreeperCapital.id("order"));

    public enum Action {
        OPEN_LONG,
        OPEN_SHORT,
        CLOSE,
        /** 给已有仓位设止盈止损（固定数量 lots，触发时按仓位缩减）。 */
        PROTECT,
        /** 把这个合约的保证金模式设为全仓（{@code lots} 与 {@code leverage} 被忽略）。 */
        SET_CROSS,
        /** 把这个合约的保证金模式设为逐仓（{@code lots} 与 {@code leverage} 被忽略）。 */
        SET_ISOLATED,
        /** 把已有仓位的杠杆调成 {@code leverage}（{@code lots} 被忽略）。 */
        SET_LEVERAGE;

        /** 是保证金设置（切换模式、调杠杆）而不是成交。 */
        public boolean isMarginSetting() {
            return this == SET_CROSS || this == SET_ISOLATED || this == SET_LEVERAGE;
        }
    }

    /** 委托的执行方式：市价立即成交，限价挂在服务端等价格穿越。 */
    public enum Execution {
        MARKET,
        LIMIT
    }

    /** 市价委托。 */
    public OrderPayload(Action action, String symbol, long lots, int leverage) {
        this(action, symbol, lots, leverage, Execution.MARKET, 0);
    }

    /** 不带止盈止损、不只减仓的委托。 */
    public OrderPayload(Action action, String symbol, long lots, int leverage, Execution execution, double price) {
        this(action, symbol, lots, leverage, execution, price, 0, 0, TriggerSource.LAST, false);
    }

    /** 附带的止盈止损。 */
    public Protection protection() {
        return new Protection(takeProfit, stopLoss, triggerSource);
    }

    private static final StreamCodec<ByteBuf, Action> ACTION =
            ByteBufCodecs.VAR_INT.map(ordinal -> Action.values()[Math.floorMod(ordinal, Action.values().length)], Action::ordinal);

    private static final StreamCodec<ByteBuf, Execution> EXECUTION =
            ByteBufCodecs.VAR_INT.map(ordinal -> Execution.values()[Math.floorMod(ordinal, Execution.values().length)], Execution::ordinal);

    private static final StreamCodec<ByteBuf, TriggerSource> TRIGGER_SOURCE =
            ByteBufCodecs.VAR_INT.map(ordinal -> TriggerSource.values()[Math.floorMod(ordinal, TriggerSource.values().length)], TriggerSource::ordinal);

    public static final StreamCodec<ByteBuf, OrderPayload> STREAM_CODEC = StreamCodec.composite(
            ACTION, OrderPayload::action,
            ModCodecs.SYMBOL, OrderPayload::symbol,
            ByteBufCodecs.VAR_LONG, OrderPayload::lots,
            ByteBufCodecs.VAR_INT, OrderPayload::leverage,
            EXECUTION, OrderPayload::execution,
            ByteBufCodecs.DOUBLE, OrderPayload::price,
            ByteBufCodecs.DOUBLE, OrderPayload::takeProfit,
            ByteBufCodecs.DOUBLE, OrderPayload::stopLoss,
            TRIGGER_SOURCE, OrderPayload::triggerSource,
            ByteBufCodecs.BOOL, OrderPayload::reduceOnly,
            OrderPayload::new);

    @Override
    public Type<OrderPayload> type() {
        return TYPE;
    }
}
