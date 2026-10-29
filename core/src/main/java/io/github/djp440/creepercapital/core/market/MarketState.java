package io.github.djp440.creepercapital.core.market;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 市场需要持久化的全部状态：停服后据此原样恢复，从停下的地方接着走。
 *
 * @param seed          市场种子（建世界时的世界种子）；之后新加的合约据此取得自己的随机数流
 * @param step          已经过的行情步数，市场时间由它得出
 * @param ticksIntoStep 当前行情步内已累计的服务器 tick 数
 * @param paused        市场是否被 OP 暂停；重启后保持暂停，直到 OP 恢复
 * @param contracts     每个合约的状态；包括配置里暂时去掉的合约，它们的状态原样保留，重新加回配置后接着走
 */
public record MarketState(long seed, long step, int ticksIntoStep, boolean paused, List<ContractState> contracts) {

    public MarketState {
        contracts = List.copyOf(contracts);
        Set<String> symbols = new HashSet<>();
        for (ContractState contract : contracts) {
            if (!symbols.add(contract.symbol())) {
                throw new IllegalArgumentException("存档里的合约重复：" + contract.symbol());
            }
        }
    }
}
