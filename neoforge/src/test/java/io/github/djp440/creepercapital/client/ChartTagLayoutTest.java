package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图表 tag 的纵向布局：每个 tag 尽量居中在自己那条线上，彼此不重叠，价格出了可见区间就钳在边缘并标出方向。
 */
class ChartTagLayoutTest {
    private static final int TOP = 100;
    private static final int BOTTOM = 300;
    private static final int HEIGHT = 12;
    private static final int GAP = 1;

    private static List<ChartTagLayout.Placement> layout(double... centers) {
        List<ChartTagLayout.Request> requests = new java.util.ArrayList<>();
        for (double center : centers) {
            requests.add(new ChartTagLayout.Request(center, HEIGHT));
        }
        return ChartTagLayout.layout(requests, TOP, BOTTOM, GAP);
    }

    private static void assertNoOverlap(List<ChartTagLayout.Placement> placements) {
        for (int i = 0; i < placements.size(); i++) {
            ChartTagLayout.Placement a = placements.get(i);
            assertTrue(a.top() >= TOP && a.top() + HEIGHT <= BOTTOM, "tag 不出区域：" + a);
            for (int j = i + 1; j < placements.size(); j++) {
                ChartTagLayout.Placement b = placements.get(j);
                boolean apart = a.top() + HEIGHT + GAP <= b.top() || b.top() + HEIGHT + GAP <= a.top();
                assertTrue(apart, "tag 不重叠：" + a + " / " + b);
            }
        }
    }

    @Test
    @DisplayName("互不相碰的 tag 居中在各自的线上，不挪动")
    void separatedTagsStayCentered() {
        List<ChartTagLayout.Placement> placements = layout(150, 200);

        assertEquals(150 - HEIGHT / 2, placements.get(0).top());
        assertEquals(200 - HEIGHT / 2, placements.get(1).top());
        assertEquals(0, placements.get(0).edge());
        assertEquals(0, placements.get(1).edge());
    }

    @Test
    @DisplayName("多条线贴得很近：tag 依次错开、不重叠，上下顺序与价格顺序一致，整体仍围绕原位置")
    void closeLinesAreSpreadApart() {
        // 传入顺序打乱：第 0 个在中间，第 1 个最上，第 2 个最下
        List<ChartTagLayout.Placement> placements = layout(201, 200, 202);

        assertNoOverlap(placements);
        assertTrue(placements.get(1).top() < placements.get(0).top(), "价格更高（y 更小）的在上");
        assertTrue(placements.get(0).top() < placements.get(2).top(), "价格更低的在下");
        // 三个 tag 共 12*3 + 2 = 38 像素，围绕 200 附近，不会被推到老远
        assertTrue(placements.get(1).top() >= 200 - 40 && placements.get(2).top() + HEIGHT <= 202 + 40);
    }

    @Test
    @DisplayName("价格在可见区间之上 / 之下：tag 钳在上沿 / 下沿，并标出方向")
    void outOfRangeTagsAreClampedToTheEdge() {
        List<ChartTagLayout.Placement> placements = layout(-500, 9999, 200);

        assertEquals(TOP, placements.get(0).top());
        assertEquals(-1, placements.get(0).edge(), "在上方之外");
        assertEquals(BOTTOM - HEIGHT, placements.get(1).top());
        assertEquals(1, placements.get(1).edge(), "在下方之外");
        assertEquals(0, placements.get(2).edge());
    }

    @Test
    @DisplayName("多个 tag 都钳在同一边：沿边缘依次排开，不重叠、不出区域")
    void severalClampedTagsStackAlongTheEdge() {
        List<ChartTagLayout.Placement> placements = layout(9999, 8888, 7777, 295);

        assertNoOverlap(placements);
        for (int i = 0; i < 3; i++) {
            assertEquals(1, placements.get(i).edge());
        }
        assertEquals(0, placements.get(3).edge(), "本来就在区域内的线不算出界，只是被挤开");
        assertEquals(BOTTOM - HEIGHT, placements.stream().mapToInt(ChartTagLayout.Placement::top).max().orElseThrow(),
                "最下面一个贴着下沿");
    }

    @Test
    @DisplayName("tag 多到放不下：仍不出区域（只能互相压一点）")
    void tooManyTagsStayInsideTheArea() {
        double[] centers = new double[30];
        java.util.Arrays.fill(centers, 200);
        List<ChartTagLayout.Placement> placements = layout(centers);

        for (ChartTagLayout.Placement placement : placements) {
            assertTrue(placement.top() >= TOP && placement.top() + HEIGHT <= BOTTOM);
        }
    }
}
