package io.github.djp440.creepercapital.core.market;

/**
 * 订阅跳跃事件，在行情步内同步回调；实现里不要做耗时操作，也不要再推进市场。
 */
@FunctionalInterface
public interface JumpListener {
    void jumped(JumpEvent event);
}
