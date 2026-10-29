package io.github.djp440.creepercapital.client.sound;

/**
 * 交易界面的音效事件（事件 → 音色类别、基础音量、音调）。具体播放哪个原版 {@code SoundEvent} 见 {@code CueSounds}，
 * 这里不引用 Minecraft 的类，方便单测。想调整某个事件的听感，改这里的音量 / 音调或 {@code CueSounds} 里绑定的音效。
 * <p>
 * 前六个是界面内的点击类事件（只在交易界面打开时响），其余由服务端推送的结果触发或由本地提示触发（见 {@link ResultCues}）。
 */
public enum Cue {
    // ===== 界面内点击 =====
    /** 普通按钮（买卖、平仓、杠杆加减、保证金模式、合约菜单……）。 */
    BUTTON_CLICK(Tone.NEUTRAL, 0.8f, 1.0f),
    /** 页签 / 切换（市价限价、预估与止盈止损、表格页签、图表类型与周期、图例）。 */
    TAB_SWITCH(Tone.NEUTRAL, 0.7f, 1.3f),
    /** 输入框里按回车确认。 */
    INPUT_CONFIRM(Tone.NEUTRAL, 0.7f, 1.5f),
    /** 图上止盈止损 tag 的「✓」确认。 */
    CHART_CONFIRM(Tone.SUCCESS, 0.7f, 1.2f),
    /** 图上止盈止损 tag 的「✕」放弃。 */
    CHART_DISMISS(Tone.NEUTRAL, 0.7f, 0.7f),
    /** 图上当前委托 tag 的「×」撤单。 */
    CHART_CANCEL_ORDER(Tone.NEUTRAL, 0.7f, 0.8f),

    // ===== 服务端结果 =====
    /** 开仓成交。 */
    ORDER_OPENED(Tone.SUCCESS, 0.7f, 1.0f),
    /** 平仓成交。 */
    ORDER_CLOSED(Tone.SUCCESS, 0.7f, 1.0f),
    /** 限价委托 / 止盈止损挂出。 */
    ORDER_PLACED(Tone.SUCCESS, 0.7f, 1.0f),
    /** 玩家撤单成功。 */
    ORDER_CANCELLED(Tone.SUCCESS, 0.7f, 1.0f),
    /** 委托被系统撤销（例如已不能开仓）。 */
    ORDER_VOIDED(Tone.WARNING, 0.7f, 1.0f),
    /** 限价委托成交。 */
    LIMIT_FILLED(Tone.SUCCESS, 0.8f, 1.2f),
    /** 止盈触发。 */
    TAKE_PROFIT(Tone.SUCCESS, 0.8f, 1.0f),
    /** 止损触发。 */
    STOP_LOSS(Tone.WARNING, 0.6f, 1.2f),
    /** 被强平。 */
    LIQUIDATED(Tone.WARNING, 0.9f, 1.0f),
    /** 被自动减仓。 */
    AUTO_DELEVERAGED(Tone.WARNING, 0.8f, 1.0f),
    /** 下单被拒绝（服务端拒单，或面板自己发现输入有误）。 */
    REJECTED(Tone.FAILURE, 0.8f, 1.0f),
    /** 上线时收到离线事件汇总。 */
    OFFLINE_SUMMARY(Tone.WARNING, 0.8f, 1.0f);

    private final Tone tone;
    private final float baseVolume;
    private final float pitch;

    Cue(Tone tone, float baseVolume, float pitch) {
        this.tone = tone;
        this.baseVolume = baseVolume;
        this.pitch = pitch;
    }

    public Tone tone() {
        return tone;
    }

    /** 音量设置为 100% 时的音量（0 到 1），不同事件有主次。 */
    public float baseVolume() {
        return baseVolume;
    }

    public float pitch() {
        return pitch;
    }
}
