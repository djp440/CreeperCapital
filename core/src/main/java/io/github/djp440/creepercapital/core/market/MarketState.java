package io.github.djp440.creepercapital.core.market;

/**
 * 市场需要持久化的全部状态：停服后据此原样恢复，从停下的地方接着走。
 *
 * @param step          已经过的行情步数，市场时间由它得出
 * @param ticksIntoStep 当前行情步内已累计的服务器 tick 数
 * @param indexPrice    指数价格（CRP）
 * @param randomState   行情随机数发生器的状态
 */
public record MarketState(long step, int ticksIntoStep, double indexPrice, long randomState) {
}
