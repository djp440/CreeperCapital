package io.github.djp440.creepercapital.core.market;

import io.github.djp440.creepercapital.core.kline.Candle;

/**
 * 一个合约需要持久化的全部状态。每个合约有自己的随机数流，所以状态之间互不依赖。
 *
 * @param symbol      合约的交易代码
 * @param index       指数价格模型的状态
 * @param randomState 这个合约的行情随机数发生器的状态
 * @param pricing     溢价与资金费率、标记价格的采样状态
 * @param lastVolume  最近一个行情步的成交量（快照里要带）
 * @param liveMinute  正在走的 1m K 线，可为 null（新合约）；已收盘的 K 线在世界目录下的文件里
 */
public record ContractState(String symbol, IndexState index, long randomState, PricingState pricing,
        double lastVolume, Candle liveMinute) {
}
