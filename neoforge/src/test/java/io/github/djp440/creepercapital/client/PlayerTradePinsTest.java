package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 最新成交页顶部的玩家成交置顶区：收到后置顶一段时间，最多几笔，最新的在前。
 */
class PlayerTradePinsTest {
    @Test
    void newestPlayerTradesArePinnedUpToTheLimit() {
        PlayerTradePins<String> pins = new PlayerTradePins<>();
        pins.add("a", 0);
        pins.add("b", 1_000);
        pins.add("c", 2_000);
        pins.add("d", 3_000);

        assertEquals(List.of("d", "c", "b"), pins.active(4_000));
    }

    @Test
    void pinsExpireAfterTheHoldTime() {
        PlayerTradePins<String> pins = new PlayerTradePins<>();
        pins.add("a", 5_000);
        pins.add("b", 6_000);

        // 置顶 30 秒：a 在 35 000 毫秒到期，b 在 36 000 毫秒到期
        assertEquals(List.of("b", "a"), pins.active(34_999));
        assertEquals(List.of("b"), pins.active(35_000));
        assertEquals(List.of(), pins.active(36_000));
    }
}
