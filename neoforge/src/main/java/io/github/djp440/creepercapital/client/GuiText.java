package io.github.djp440.creepercapital.client;

import net.minecraft.client.gui.Font;

/**
 * 界面文字的小工具：放不下时截断加省略号，保证文字不越出所在的列或卡片。
 */
final class GuiText {
    private static final String ELLIPSIS = "…";

    private GuiText() {
    }

    /** 宽度不超过 {@code width} 的文字：放得下原样返回，放不下截断并加省略号，连省略号都放不下返回空串。 */
    static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        int room = width - font.width(ELLIPSIS);
        if (room <= 0) {
            return "";
        }
        return font.plainSubstrByWidth(text, room) + ELLIPSIS;
    }

    /** 文字在 {@code [top, bottom)} 里垂直居中时的纵坐标（MC 字形顶部留 1 像素空白，往下补 1）。 */
    static int centeredY(Font font, int top, int bottom) {
        return top + (bottom - top - font.lineHeight) / 2 + 1;
    }
}
