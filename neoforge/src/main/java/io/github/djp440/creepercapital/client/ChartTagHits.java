package io.github.djp440.creepercapital.client;

import java.util.List;

/**
 * 图表 tag 的命中测试（纯函数，不碰 Minecraft）：一个 tag 左边是文字区（可拖动的 tag 在这里按住拖拽），
 * 右端是等宽排开的按钮（确认 ✓、取消 ✕、撤销「×」）；文字区与按钮之间隔 {@link #DEAD_ZONE} 像素的缝，点在缝上什么都不触发，
 * 拖拽和按钮就不会互相误触。画 tag 时用同一组函数算按钮位置，画出来的就是点得到的。
 */
final class ChartTagHits {
    /** 每个按钮的宽度（像素）。 */
    static final int BUTTON_WIDTH = 11;
    /** 文字区与按钮之间不触发任何操作的缝（像素）。 */
    static final int DEAD_ZONE = 3;

    /**
     * @param left      左边缘（含）
     * @param top       上边缘（含）
     * @param right     右边缘（不含）
     * @param bottom    下边缘（不含）
     * @param buttons   右端按钮的个数
     * @param draggable 文字区能否拖动
     */
    record Tag(int left, int top, int right, int bottom, int buttons, boolean draggable) {
    }

    enum Zone {
        NONE,
        /** 可拖动 tag 的文字区。 */
        DRAG,
        /** 某个按钮。 */
        BUTTON
    }

    /**
     * @param tag    命中的 tag 序号；没命中为 -1
     * @param zone   命中的区域
     * @param button 命中的按钮序号（从左数）；不是按钮为 -1
     */
    record Hit(int tag, Zone zone, int button) {
        static final Hit NONE = new Hit(-1, Zone.NONE, -1);
    }

    private ChartTagHits() {
    }

    /** 第 {@code index} 个按钮（从左数）的左边缘。 */
    static int buttonLeft(Tag tag, int index) {
        return tag.right() - (tag.buttons() - index) * BUTTON_WIDTH;
    }

    /** 文字区的右边缘（不含）：有按钮时在第一个按钮左边再让出一段缝。 */
    static int bodyRight(Tag tag) {
        return tag.buttons() == 0 ? tag.right() : buttonLeft(tag, 0) - DEAD_ZONE;
    }

    /** 带 {@code buttons} 个按钮、文字区宽 {@code bodyWidth} 的 tag 的总宽度。 */
    static int width(int bodyWidth, int buttons) {
        return bodyWidth + (buttons == 0 ? 0 : DEAD_ZONE + buttons * BUTTON_WIDTH);
    }

    /** 点在哪个 tag 的哪一块；tag 叠在一起时后画的（列表里靠后的）优先。 */
    static Hit hit(List<Tag> tags, double x, double y) {
        for (int i = tags.size() - 1; i >= 0; i--) {
            Tag tag = tags.get(i);
            if (x < tag.left() || x >= tag.right() || y < tag.top() || y >= tag.bottom()) {
                continue;
            }
            for (int b = 0; b < tag.buttons(); b++) {
                int left = buttonLeft(tag, b);
                if (x >= left && x < left + BUTTON_WIDTH) {
                    return new Hit(i, Zone.BUTTON, b);
                }
            }
            if (tag.draggable() && x < bodyRight(tag)) {
                return new Hit(i, Zone.DRAG, -1);
            }
            return new Hit(i, Zone.NONE, -1);
        }
        return Hit.NONE;
    }
}
