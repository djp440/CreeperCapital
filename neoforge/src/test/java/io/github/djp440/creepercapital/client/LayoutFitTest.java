package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.client.LayoutFit.Columns;
import io.github.djp440.creepercapital.client.LayoutFit.Slot;
import io.github.djp440.creepercapital.client.LayoutFit.TabStyle;

/**
 * 表格列、行情栏数据、页签条在宽度不够时的降级规则。
 */
class LayoutFitTest {
    private static final int REQUIRED = LayoutFit.REQUIRED;

    // ===== 表格列 =====

    @Test
    void columnsShareTheLeftoverWidthEvenly() {
        Columns columns = LayoutFit.columns(0, 100, 4, new int[] {20, 30, 10}, new int[] {1, 1, 1});

        // 20+30+10 加两个间距是 68，多出 32：每列分 10，余下 2 给最后一列
        assertArrayEquals(new int[] {30, 40, 22}, columns.width());
        assertArrayEquals(new int[] {0, 34, 78}, columns.x());
    }

    @Test
    void leastImportantColumnIsHiddenFirst() {
        Columns columns = LayoutFit.columns(10, 70, 4, new int[] {20, 30, 10}, new int[] {3, 1, 2});

        // 放不下 68：先藏优先级最低的第二列，剩 34，多出的 26 两列平分
        assertArrayEquals(new int[] {33, 0, 23}, columns.width());
        assertEquals(10, columns.x()[0]);
        assertEquals(47, columns.x()[2]);
        assertEquals(List.of(true, false, true), List.of(columns.visible(0), columns.visible(1), columns.visible(2)));
    }

    @Test
    void requiredColumnsShrinkInsteadOfDisappearing() {
        Columns columns = LayoutFit.columns(0, 30, 4, new int[] {20, 30}, new int[] {REQUIRED, REQUIRED});

        // 去掉间距只剩 26，按 20:30 缩成 10 和 15，余下 1 给最后一列
        assertArrayEquals(new int[] {10, 16}, columns.width());
        assertArrayEquals(new int[] {0, 14}, columns.x());
    }

    // ===== 行情栏 =====

    @Test
    void tickerStatsStayOnOneRowWhenTheyFit() {
        List<Slot> slots = LayoutFit.ticker(50, 10, 200, 10, new int[] {40, 40, 30}, new int[] {1, 1, 1}, 2);

        assertEquals(List.of(new Slot(0, 50), new Slot(0, 100), new Slot(0, 150)), slots);
    }

    @Test
    void tickerWrapsToTheNextRowFromTheLeftEdge() {
        List<Slot> slots = LayoutFit.ticker(50, 10, 200, 10, new int[] {40, 40, 60}, new int[] {1, 1, 1}, 2);

        assertEquals(List.of(new Slot(0, 50), new Slot(0, 100), new Slot(1, 10)), slots);
    }

    @Test
    void tickerDropsTheLeastImportantStatsWhenRowsRunOut() {
        List<Slot> slots = LayoutFit.ticker(50, 10, 200, 10, new int[] {40, 40, 60}, new int[] {3, 1, 2}, 1);

        assertEquals(List.of(new Slot(0, 50), Slot.DROPPED, new Slot(0, 100)), slots);
    }

    @Test
    void tickerPutsBackANarrowStatThatFitsOnceAWideOneIsDropped() {
        // 宽 120 的第二项放不下，丢掉它以后空出的位置放得下优先级更低、只有 30 宽的第三项
        List<Slot> slots = LayoutFit.ticker(50, 10, 200, 10, new int[] {40, 120, 30}, new int[] {3, 2, 1}, 1);

        assertEquals(List.of(new Slot(0, 50), Slot.DROPPED, new Slot(0, 100)), slots);
    }

    @Test
    void tickerRowCountIsTheHighestUsedRowPlusOne() {
        assertEquals(2, LayoutFit.rowsUsed(List.of(new Slot(0, 50), Slot.DROPPED, new Slot(1, 10))));
        assertEquals(1, LayoutFit.rowsUsed(List.of(Slot.DROPPED)));
    }

    // ===== 页签条 =====

    @Test
    void tabsKeepFullLabelsAndRoomyPaddingWhenTheyFit() {
        assertEquals(new TabStyle(false, 6), LayoutFit.tabs(100, new int[] {30, 30}, new int[] {10, 10}));
    }

    @Test
    void tabsTightenPaddingBeforeSwitchingToShortLabels() {
        assertEquals(new TabStyle(false, 4), LayoutFit.tabs(76, new int[] {30, 30}, new int[] {10, 10}));
        assertEquals(new TabStyle(true, 6), LayoutFit.tabs(50, new int[] {30, 30}, new int[] {10, 10}));
    }

    @Test
    void tabsFallBackToTheTightestStyle() {
        assertEquals(new TabStyle(true, 2), LayoutFit.tabs(10, new int[] {30, 30}, new int[] {10, 10}));
    }
}
