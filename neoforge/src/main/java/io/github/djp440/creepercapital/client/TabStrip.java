package io.github.djp440.creepercapital.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 全界面统一的页签条样式（周期、盘口栏、表格区、下单面板都用它）：当前页黄字加黄色下划线，
 * 鼠标悬停的页文字变亮，其余是次要文字灰。
 */
final class TabStrip {
    private TabStrip() {
    }

    /**
     * 从 {@code left} 起横向排一排页签。
     *
     * @param padding 每个页签文字左右各留的空白
     * @return 各页签的点击区域 {左, 上, 右, 下}
     */
    static int[][] extract(GuiGraphicsExtractor graphics, Font font, int left, int top, int height, Component[] labels, int selected,
            int padding, int mouseX, int mouseY) {
        int[][] boxes = new int[labels.length][];
        int x = left;
        for (int i = 0; i < labels.length; i++) {
            int width = font.width(labels[i]) + padding * 2;
            boxes[i] = new int[] {x, top, x + width, top + height};
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < top + height;
            int color = i == selected ? BinancePalette.BRAND_YELLOW : hovered ? BinancePalette.TEXT_PRIMARY : BinancePalette.TEXT_SECONDARY;
            graphics.text(font, labels[i], x + padding, GuiText.centeredY(font, top, top + height), color, false);
            if (i == selected) {
                graphics.horizontalLine(x + padding, x + width - padding - 1, top + height - 1, BinancePalette.BRAND_YELLOW);
            }
            x += width;
        }
        return boxes;
    }

    /** 这排页签总共占多宽。 */
    static int width(Font font, Component[] labels, int padding) {
        int total = 0;
        for (Component label : labels) {
            total += font.width(label) + padding * 2;
        }
        return total;
    }

    /** 点中第几个页签；没点中返回 -1。 */
    static int indexAt(int[][] boxes, double x, double y) {
        for (int i = 0; i < boxes.length; i++) {
            int[] box = boxes[i];
            if (x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) {
                return i;
            }
        }
        return -1;
    }
}
