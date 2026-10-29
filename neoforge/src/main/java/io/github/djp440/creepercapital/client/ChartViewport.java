package io.github.djp440.creepercapital.client;

import java.util.List;

import io.github.djp440.creepercapital.core.kline.Candle;

/**
 * 图表的水平视口（纯函数，不碰 Minecraft）：只有两个量——每根 K 线占多宽（缩放）、可见区间右缘离最新一根多远（平移）。
 * 对象不可变，平移、缩放都返回新视口；K 线根数和绘图区宽度每次由调用方传入，所以 K 线追加、窗口改尺寸都不需要改视口。
 * <p>
 * 坐标约定：第 {@code i} 根 K 线占「序号区间」{@code [i, i + 1)}，序号可以是小数（表示一根 K 线内部的位置）。
 * 绘图区右缘对应序号 {@code count - offset}；{@code offset = 0} 即最新一根紧贴右缘。
 * 其它要在图上定位的东西（买卖点、持仓线、tag）都通过 {@link #project} 得到的 {@link Projection} 换算坐标。
 */
record ChartViewport(double slotWidth, double offset) {
    /** 默认每根 K 线占的像素（实体 5、影线居中、两侧各留 1）。 */
    static final double DEFAULT_SLOT_WIDTH = 7;

    /** 缩放的上下限（每根 K 线占的像素）。 */
    static final double MIN_SLOT_WIDTH = 1;
    static final double MAX_SLOT_WIDTH = 28;
    /** 滚轮每格缩放的倍数。 */
    static final double ZOOM_PER_STEP = 1.2;

    static ChartViewport latest() {
        return new ChartViewport(DEFAULT_SLOT_WIDTH, 0);
    }

    /**
     * 市场时间（秒）对应的 K 线序号（小数）：落在某根 K 线内部按比例，早于第一根 / 晚于最后一根按周期长度外推。
     * 买卖点、委托这类带时间的标记先换成序号，再用 {@link Projection#x} 得到横坐标。{@code candles} 按时间升序、不能为空。
     */
    static double indexOfTime(List<Candle> candles, long periodSeconds, long time) {
        int low = 0;
        int high = candles.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (candles.get(middle).start() <= time) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low + (double) (time - candles.get(low).start()) / periodSeconds;
    }

    /** 最多能往回看多少根：最旧一根贴着左边缘为止；K 线全放得下就是 0（不能平移）。 */
    double maxOffset(int count, double plotWidth) {
        return Math.max(0, count - plotWidth / slotWidth);
    }

    /** 把平移量夹回 {@code [0, maxOffset]}（K 线根数变少、窗口变宽后可能越界）。 */
    ChartViewport clamped(int count, double plotWidth) {
        double limited = Math.max(0, Math.min(offset, maxOffset(count, plotWidth)));
        return limited == offset ? this : new ChartViewport(slotWidth, limited);
    }

    /** 鼠标向右拖 {@code dx} 像素（负为向左）：回看更早的 K 线；到头就停在边缘。 */
    ChartViewport panned(double dx, int count, double plotWidth) {
        return new ChartViewport(slotWidth, offset + dx / slotWidth).clamped(count, plotWidth);
    }

    /**
     * 拖 {@code dx} 像素时因为到头而没生效的部分（像素，符号同 {@code dx}）；完全生效返回 0。
     * 正值：已经没有更早的 K 线了；负值：已经是最新一根了。
     */
    double overscroll(double dx, int count, double plotWidth) {
        double applied = (panned(dx, count, plotWidth).offset - offset) * slotWidth;
        return dx - applied;
    }

    /**
     * 滚轮缩放 {@code steps} 格（正为放大）：以横坐标 {@code anchorX} 处的那一点为锚点，缩放前后它仍在鼠标下；
     * 缩放结果超出上下限就停在上下限，贴着边界时锚点可能被平移边界顶开。
     */
    ChartViewport zoomed(double steps, double anchorX, double plotLeft, double plotRight, int count) {
        double target = Math.max(MIN_SLOT_WIDTH, Math.min(MAX_SLOT_WIDTH, slotWidth * Math.pow(ZOOM_PER_STEP, steps)));
        if (target == slotWidth) {
            return this;
        }
        double anchorIndex = project(plotLeft, plotRight, count).indexAt(anchorX);
        double rightIndex = anchorIndex + (plotRight - anchorX) / target;
        return new ChartViewport(target, count - rightIndex).clamped(count, plotRight - plotLeft);
    }

    /** 在绘图区 {@code [plotLeft, plotRight]}（像素）、共 {@code count} 根 K 线时，这个视口的坐标换算。 */
    Projection project(double plotLeft, double plotRight, int count) {
        return new Projection(plotLeft, plotRight, count, slotWidth, offset);
    }

    /** 视口落在具体绘图区上的坐标换算：K 线序号 ↔ 横坐标，以及可见的 K 线范围。 */
    record Projection(double plotLeft, double plotRight, int count, double slotWidth, double offset) {
        /** 绘图区右缘对应的 K 线序号（小数）。 */
        double rightIndex() {
            return count - offset;
        }

        /** 绘图区左缘对应的 K 线序号（小数）。 */
        double leftIndex() {
            return rightIndex() - (plotRight - plotLeft) / slotWidth;
        }

        /** 序号 {@code index}（小数）的横坐标；整数序号是那根 K 线的左边缘。 */
        double x(double index) {
            return plotRight - (rightIndex() - index) * slotWidth;
        }

        /** 第 {@code candle} 根 K 线中心的横坐标。 */
        double centerX(int candle) {
            return x(candle + 0.5);
        }

        /** 横坐标 {@code x} 对应的 K 线序号（小数）。 */
        double indexAt(double x) {
            return rightIndex() - (plotRight - x) / slotWidth;
        }

        /** 横坐标 {@code x} 落在哪根 K 线上；在绘图区之外或那里没有 K 线返回 -1。 */
        int candleAt(double x) {
            if (x < plotLeft || x >= plotRight) {
                return -1;
            }
            int candle = (int) Math.floor(indexAt(x));
            return candle >= 0 && candle < count ? candle : -1;
        }

        /** 第一根至少露出一部分的 K 线。 */
        int firstVisible() {
            return Math.max(0, (int) Math.floor(leftIndex()));
        }

        /** 最后一根至少露出一部分的 K 线。 */
        int lastVisible() {
            return Math.min(count - 1, (int) Math.ceil(rightIndex()) - 1);
        }
    }
}
