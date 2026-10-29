package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 宽度不够时的降级规则（纯计算，不依赖 Minecraft）：表格隐藏次要列、行情栏折行或丢掉次要数据、页签条收紧间距或换短名称。
 * 调用方先用字体量好各项需要的宽度，再按这里算出的位置去画。
 */
final class LayoutFit {
    /** 优先级：必须显示的列，宽度不够时只会缩窄（文字截断），不会被隐藏。 */
    static final int REQUIRED = Integer.MAX_VALUE;

    private LayoutFit() {
    }

    /**
     * 表格各列的位置。
     *
     * @param x     每列左边界（隐藏的列无意义）
     * @param width 每列宽度，隐藏的列为 0
     */
    record Columns(int[] x, int[] width) {
        boolean visible(int column) {
            return width[column] > 0;
        }
    }

    /**
     * 把若干列排进 {@code [left, right)}：放得下时多出的宽度各列平分（余数给最后一列）；放不下时按优先级从低到高隐藏列
     * （同优先级先藏靠右的）；只剩必须显示的列仍放不下时按需要的宽度等比缩窄。
     *
     * @param natural  每列需要的宽度（表头与各行内容中最宽的）
     * @param priority 每列的优先级，越大越重要，{@link #REQUIRED} 表示必须显示
     */
    static Columns columns(int left, int right, int gap, int[] natural, int[] priority) {
        int count = natural.length;
        boolean[] shown = new boolean[count];
        java.util.Arrays.fill(shown, true);
        while (used(natural, shown, gap) > right - left) {
            int victim = -1;
            for (int i = count - 1; i >= 0; i--) {
                if (shown[i] && priority[i] != REQUIRED && (victim < 0 || priority[i] < priority[victim])) {
                    victim = i;
                }
            }
            if (victim < 0) {
                break;
            }
            shown[victim] = false;
        }

        int visibleCount = 0;
        int naturalSum = 0;
        int last = -1;
        for (int i = 0; i < count; i++) {
            if (shown[i]) {
                visibleCount++;
                naturalSum += natural[i];
                last = i;
            }
        }
        int[] width = new int[count];
        int[] x = new int[count];
        if (visibleCount == 0) {
            return new Columns(x, width);
        }
        int available = right - left - gap * (visibleCount - 1);
        int assigned = 0;
        if (available >= naturalSum) {
            int share = (available - naturalSum) / visibleCount;
            for (int i = 0; i < count; i++) {
                if (shown[i]) {
                    width[i] = natural[i] + share;
                    assigned += width[i];
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                if (shown[i]) {
                    width[i] = Math.max(1, (int) ((long) natural[i] * Math.max(0, available) / Math.max(1, naturalSum)));
                    assigned += width[i];
                }
            }
        }
        width[last] += Math.max(0, available - assigned);
        int cursor = left;
        for (int i = 0; i < count; i++) {
            if (shown[i]) {
                x[i] = cursor;
                cursor += width[i] + gap;
            }
        }
        return new Columns(x, width);
    }

    private static int used(int[] natural, boolean[] shown, int gap) {
        int sum = 0;
        int visible = 0;
        for (int i = 0; i < natural.length; i++) {
            if (shown[i]) {
                sum += natural[i];
                visible++;
            }
        }
        return sum + gap * Math.max(0, visible - 1);
    }

    /** 行情栏一项数据的位置：第几行（从 0 起）、左边界；丢掉的项是 {@link #DROPPED}。 */
    record Slot(int row, int x) {
        static final Slot DROPPED = new Slot(-1, 0);

        boolean dropped() {
            return row < 0;
        }
    }

    /**
     * 行情栏排版：按显示顺序排，第一行从 {@code firstRowStart}（交易代码之后）开始，放不下就折到下一行、从 {@code rowStart} 开始；
     * 超过 {@code maxRows} 行时按优先级从低到高丢掉数据（同优先级先丢靠后的）再重排；
     * 排得下以后，再按优先级从高到低把丢掉的项逐个放回去试，放得下就留着（丢掉一项宽的以后，较窄的次要项往往又放得下）。
     *
     * @param widths   每项的宽度
     * @param priority 每项的优先级，越大越重要
     * @return 与 {@code widths} 一一对应的位置
     */
    static List<Slot> ticker(int firstRowStart, int rowStart, int right, int gap, int[] widths, int[] priority, int maxRows) {
        boolean[] kept = new boolean[widths.length];
        java.util.Arrays.fill(kept, true);
        while (rowsNeeded(firstRowStart, rowStart, right, gap, widths, kept) > maxRows) {
            int victim = -1;
            for (int i = widths.length - 1; i >= 0; i--) {
                if (kept[i] && (victim < 0 || priority[i] < priority[victim])) {
                    victim = i;
                }
            }
            if (victim < 0) {
                break;
            }
            kept[victim] = false;
        }
        boolean[] tried = kept.clone();
        while (true) {
            int candidate = -1;
            for (int i = 0; i < widths.length; i++) {
                if (!tried[i] && (candidate < 0 || priority[i] > priority[candidate])) {
                    candidate = i;
                }
            }
            if (candidate < 0) {
                break;
            }
            tried[candidate] = true;
            kept[candidate] = true;
            if (rowsNeeded(firstRowStart, rowStart, right, gap, widths, kept) > maxRows) {
                kept[candidate] = false;
            }
        }
        return arrange(firstRowStart, rowStart, right, gap, widths, kept);
    }

    private static int rowsNeeded(int firstRowStart, int rowStart, int right, int gap, int[] widths, boolean[] kept) {
        return rowsUsed(arrange(firstRowStart, rowStart, right, gap, widths, kept));
    }

    /** 按显示顺序排保留的项，放不下就折行。 */
    private static List<Slot> arrange(int firstRowStart, int rowStart, int right, int gap, int[] widths, boolean[] kept) {
        List<Slot> slots = new ArrayList<>(widths.length);
        int row = 0;
        int x = firstRowStart;
        int inRow = 0;
        for (int i = 0; i < widths.length; i++) {
            if (!kept[i]) {
                slots.add(Slot.DROPPED);
                continue;
            }
            if (x + widths[i] > right && (row == 0 || inRow > 0)) {
                row++;
                x = rowStart;
                inRow = 0;
            }
            slots.add(new Slot(row, x));
            x += widths[i] + gap;
            inRow++;
        }
        return slots;
    }

    /** 排版用掉的行数（至少一行，交易代码总占第一行）。 */
    static int rowsUsed(List<Slot> slots) {
        int rows = 1;
        for (Slot slot : slots) {
            rows = Math.max(rows, slot.row() + 1);
        }
        return rows;
    }

    /**
     * 页签条的样式。
     *
     * @param shortLabels 是否用短名称
     * @param padding     每个页签文字左右各留的空白
     */
    record TabStyle(boolean shortLabels, int padding) {
    }

    private static final TabStyle[] TAB_STYLES = {
            new TabStyle(false, 6), new TabStyle(false, 4), new TabStyle(true, 6), new TabStyle(true, 4), new TabStyle(true, 2)};

    /** 依次尝试：全名宽松、全名紧凑、短名宽松、短名紧凑、短名最紧，取第一个放得下的；都放不下取最紧的（超出部分由调用方裁掉）。 */
    static TabStyle tabs(int available, int[] fullWidths, int[] shortWidths) {
        for (TabStyle style : TAB_STYLES) {
            int[] widths = style.shortLabels() ? shortWidths : fullWidths;
            int total = 0;
            for (int width : widths) {
                total += width + style.padding() * 2;
            }
            if (total <= available) {
                return style;
            }
        }
        return TAB_STYLES[TAB_STYLES.length - 1];
    }
}
