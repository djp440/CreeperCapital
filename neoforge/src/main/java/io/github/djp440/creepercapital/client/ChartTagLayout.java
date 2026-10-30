package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 图表上 tag（持仓线、强平价、当前委托的标签，以及价格轴上的价格标记）的纵向布局（纯函数，不碰 Minecraft）。
 * <p>
 * 每个 tag 尽量居中在自己那条线上；价格超出可见区间的钳在区域上沿 / 下沿，并记下方向（画方向箭头用）。
 * 挨得太近的 tag 按上下顺序依次错开 {@code gap} 像素，顺序与价格顺序一致；挤到区域外的再整体往回推。
 * 全部 tag 叠起来比区域还高时，只保证每个都不出区域（这时难免互相压住一部分）。
 */
final class ChartTagLayout {
    /**
     * @param centerY 线所在的纵坐标（可以在区域之外）
     * @param height  tag 的高度（像素）
     */
    record Request(double centerY, int height) {
    }

    /**
     * @param top  tag 的上边缘
     * @param edge 0 = 线在区域内；-1 = 线在区域上方之外（价格高于可见区间）；1 = 线在区域下方之外
     */
    record Placement(int top, int edge) {
    }

    private ChartTagLayout() {
    }

    /** 按请求的顺序返回各 tag 的位置；区域是 {@code [areaTop, areaBottom)}。 */
    static List<Placement> layout(List<Request> requests, int areaTop, int areaBottom, int gap) {
        int count = requests.size();
        int[] tops = new int[count];
        int[] edges = new int[count];
        for (int i = 0; i < count; i++) {
            Request request = requests.get(i);
            double center = request.centerY();
            edges[i] = center < areaTop ? -1 : center >= areaBottom ? 1 : 0;
            int desired = (int) Math.round(Math.max(-1e6, Math.min(1e6, center))) - request.height() / 2;
            tops[i] = clamp(desired, areaTop, areaBottom - request.height());
        }
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            order.add(i);
        }
        // 先按位置排，同一位置按原始纵坐标排（都钳在边缘时仍保持价格顺序），再按传入顺序
        order.sort(Comparator.<Integer>comparingInt(i -> tops[i])
                .thenComparingDouble(i -> requests.get(i).centerY())
                .thenComparingInt(i -> i));
        // 自上而下：和上一个重叠就往下挪
        int previousBottom = Integer.MIN_VALUE / 2;
        for (int i : order) {
            tops[i] = Math.max(tops[i], previousBottom + gap);
            previousBottom = tops[i] + requests.get(i).height();
        }
        // 自下而上：出了下沿就往上推，并把上面的一路推开
        int nextTop = areaBottom + gap;
        for (int k = count - 1; k >= 0; k--) {
            int i = order.get(k);
            int height = requests.get(i).height();
            tops[i] = Math.min(tops[i], nextTop - gap - height);
            nextTop = tops[i];
        }
        // 放不下时最后兜底：每个都不出区域
        List<Placement> placements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            placements.add(new Placement(clamp(tops[i], areaTop, areaBottom - requests.get(i).height()), edges[i]));
        }
        return placements;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }
}
