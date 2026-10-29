package io.github.djp440.creepercapital.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketTest {

    @Test
    @DisplayName("新市场停在第 0 个行情步，DIAMONDCRP 指数价格为初始价 1000")
    void freshMarketStartsAtStepZeroWithInitialIndexPrice() {
        Market market = new Market(42L);

        MarketSnapshot snapshot = market.snapshot();

        assertEquals(0L, snapshot.step());
        assertEquals("DIAMONDCRP", snapshot.symbol());
        assertEquals(1000.0, snapshot.indexPrice());
    }

    @Test
    @DisplayName("每 20 个服务器 tick 推进一个行情步：前 19 个 tick 不推进，第 20 个推进到第 1 步")
    void advancesOneMarketStepEveryTwentyTicks() {
        Market market = new Market(42L);

        for (int tick = 1; tick <= 19; tick++) {
            assertTrue(market.tick().isEmpty(), "第 " + tick + " 个 tick 不应推进行情步");
        }
        Optional<MarketSnapshot> stepped = market.tick();

        assertEquals(1L, stepped.orElseThrow().step());
        assertEquals(1L, market.snapshot().step());
    }

    @Test
    @DisplayName("指数价格每个行情步都在随机游走，同一种子走出同一条路径")
    void indexPriceWalksEveryStepAndIsReproducibleBySeed() {
        List<Double> path = indexPricePath(new Market(7L), 100);

        assertEquals(path, indexPricePath(new Market(7L), 100));
        assertNotEquals(path, indexPricePath(new Market(8L), 100));
        assertEquals(100, new HashSet<>(path).size(), "每个行情步的指数价格都应变化");
    }

    @Test
    @DisplayName("一个市场日（14400 个行情步）内指数价格始终为正")
    void indexPriceStaysPositiveForAMarketDay() {
        List<Double> path = indexPricePath(new Market(42L), 14_400);

        assertTrue(path.stream().allMatch(price -> price > 0), "指数价格不应小于等于 0");
    }

    private static List<Double> indexPricePath(Market market, int steps) {
        List<Double> prices = new ArrayList<>();
        while (prices.size() < steps) {
            market.tick().ifPresent(snapshot -> prices.add(snapshot.indexPrice()));
        }
        return prices;
    }
}
