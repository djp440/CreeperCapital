package io.github.djp440.creepercapital.core.market;

/**
 * 一次跳跃：行情中突发的、不连续的跳涨或跳跌。
 * <p>
 * 这是新闻文字的挂接点：以后想为跳跃配上「末影龙苏醒，钻石需求暴涨」之类的文字，
 * 只需用 {@link Market#addJumpListener} 订阅事件，按合约、方向和幅度生成文案，核心不关心文字怎么来、怎么展示。
 *
 * @param symbol      发生跳跃的合约
 * @param step        跳跃生效的行情步序号（该步结束时的价格已包含跳跃）
 * @param changeRatio 跳跃造成的价格变化比例，+0.1 即涨 10%，-0.2 即跌 20%（不含同一步里的日常波动）
 * @param cause       跳跃的来源
 */
public record JumpEvent(String symbol, long step, double changeRatio, Cause cause) {

    /** 跳跃的来源。 */
    public enum Cause {
        /** 行情模型随机产生。 */
        RANDOM,
        /** OP 用开发命令手动触发。 */
        MANUAL
    }

    /** 涨为 true，跌为 false。 */
    public boolean isUp() {
        return changeRatio > 0;
    }
}
