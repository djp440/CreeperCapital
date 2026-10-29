package io.github.djp440.creepercapital.client.sound;

/** 音效的音色类别：成功、失败、警示三类要听得出区别，点击类交互用中性。 */
public enum Tone {
    /** 普通点击、切换等界面操作。 */
    NEUTRAL,
    /** 成交、挂单、撤单、止盈触发等顺利的结果。 */
    SUCCESS,
    /** 下单被拒绝。 */
    FAILURE,
    /** 止损触发、强平、自动减仓等需要玩家注意的事件。 */
    WARNING
}
