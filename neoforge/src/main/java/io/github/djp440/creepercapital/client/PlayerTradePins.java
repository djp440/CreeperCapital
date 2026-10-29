package io.github.djp440.creepercapital.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 最新成交页顶部的玩家成交置顶区（纯逻辑，不依赖 Minecraft）：每笔玩家成交收到后置顶 {@link #HOLD_MILLIS}，
 * 最多同时置顶 {@link #MAX} 笔，最新的在前；到期后回到普通列表里按时间排的位置（多半已被新成交推到下面）。
 * <p>
 * 模拟成交每个行情步都来好几笔，玩家成交原本两三秒就被挤出可见区域；置顶只延长它在顶部停留的时间，
 * 不改变「全服可见、只保留最近若干笔」：置顶的仍是那份最近成交缓存里的成交。
 *
 * @param <T> 成交记录的类型
 */
final class PlayerTradePins<T> {
    /** 置顶时长（真实时间），够玩家看清自己或别人刚成交的那一笔。 */
    static final long HOLD_MILLIS = 30_000;
    static final int MAX = 3;

    private record Pin<T>(T trade, long receivedAt) {
    }

    /** 最新的在前。 */
    private final ArrayDeque<Pin<T>> pins = new ArrayDeque<>();

    /** 收到一笔玩家成交。 */
    void add(T trade, long now) {
        pins.addFirst(new Pin<>(trade, now));
        while (pins.size() > MAX) {
            pins.removeLast();
        }
    }

    /** 此刻仍在置顶的成交，最新的在前；顺带丢掉已到期的。 */
    List<T> active(long now) {
        Iterator<Pin<T>> iterator = pins.iterator();
        List<T> active = new ArrayList<>(pins.size());
        while (iterator.hasNext()) {
            Pin<T> pin = iterator.next();
            if (now - pin.receivedAt() >= HOLD_MILLIS) {
                iterator.remove();
            } else {
                active.add(pin.trade());
            }
        }
        return active;
    }

    void clear() {
        pins.clear();
    }
}
