package io.github.djp440.creepercapital.client.sound;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 服务端 {@code OrderResultPayload} 的结果码 → 音效事件。服务端本来就会把成交、止盈止损触发、强平、自动减仓、拒单等通知发给在线玩家，
 * 客户端收到时按结果码出声，不依赖交易界面是否打开，也不需要改协议。
 * <p>
 * 每个结果码必须登记在下面三张表之一（单测对照语言文件检查，新增结果码会提醒你决定音效）：
 * 映射表（有专属事件）、静音表（不出声）、拒单表（统一播放拒单音）。表里没有的未知结果码按成功标志兜底：失败播拒单音，成功静音。
 */
public final class ResultCues {
    /** 有专属音效事件的结果码。 */
    public static final Map<String, Cue> MAPPED = Map.ofEntries(
            Map.entry("open_long", Cue.ORDER_OPENED),
            Map.entry("open_short", Cue.ORDER_OPENED),
            Map.entry("close", Cue.ORDER_CLOSED),
            Map.entry("limit_placed", Cue.ORDER_PLACED),
            Map.entry("protection_placed", Cue.ORDER_PLACED),
            Map.entry("order_cancelled", Cue.ORDER_CANCELLED),
            Map.entry("order_cancelled_rejected", Cue.ORDER_VOIDED),
            Map.entry("limit_filled", Cue.LIMIT_FILLED),
            Map.entry("take_profit_filled", Cue.TAKE_PROFIT),
            Map.entry("stop_loss_filled", Cue.STOP_LOSS),
            Map.entry("liquidated", Cue.LIQUIDATED),
            Map.entry("auto_deleveraged", Cue.AUTO_DELEVERAGED));

    /**
     * 不出声的结果码：切换保证金模式 / 杠杆（点击音已经响过）、资金费（每 8 个市场小时一次，不打扰）、
     * 强平时顺带撤销的委托（强平音已经响过）。
     */
    public static final Set<String> SILENT = Set.of("set_cross", "set_isolated", "set_leverage",
            "funding_paid", "funding_received", "funding_partial", "order_cancelled_liquidated");

    /** 下单被拒绝的结果码（含面板自己发现输入有误的那几条），统一播放拒单音。 */
    public static final Set<String> REJECTIONS = Set.of("invalid_quantity", "invalid_quantity_input", "invalid_leverage",
            "insufficient_balance", "opposite_position", "leverage_mismatch", "no_position", "close_exceeds_position",
            "invalid_price", "unknown_contract", "market_paused", "position_limit", "margin_mode_locked",
            "leverage_above_tier", "leverage_unsafe", "invalid_price_input", "too_many_orders", "no_such_order",
            "invalid_trigger_input", "need_trigger_input", "invalid_trigger_price", "open_interest_limit");

    private ResultCues() {
    }

    /**
     * 一条结果该播放哪个事件的音效；不出声时为空。
     *
     * @param success 结果是否成功
     * @param code    结果码
     */
    public static Optional<Cue> forResult(boolean success, String code) {
        Cue mapped = MAPPED.get(code);
        if (mapped != null) {
            return Optional.of(mapped);
        }
        if (SILENT.contains(code)) {
            return Optional.empty();
        }
        return success && !REJECTIONS.contains(code) ? Optional.empty() : Optional.of(Cue.REJECTED);
    }
}
