package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import io.github.djp440.creepercapital.client.sound.Cue;
import io.github.djp440.creepercapital.client.sound.CuePlayer;
import io.github.djp440.creepercapital.client.IndicatorSettings.MaLine;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 图表内的指标设置浮层（不是单独的界面）：点图例旁的齿轮在图表左上角展开，盖在图例和主图的左上方。
 * 标题行右端是「恢复默认」和关闭；每条均线一行——显示复选框、颜色块（点开色盘选色）、周期输入框、删除；其后一行是成交量副图的显示复选框，
 * 底部是「添加均线」。显示复选框与图例点击是同一个开关（图例放不下的项也能在这里切换）。
 * 周期输入合法（{@link IndicatorSettings#parsePeriod}）时立即生效并写回客户端配置，不合法时浮层里提示、均线保持原周期，
 * 输入框失焦后文字回到当前生效的周期。浮层自己持有输入框，由交易界面登记为子部件接收键盘输入。
 */
final class IndicatorPanel {
    private static final int WIDTH = 176;
    private static final int PAD = 5;
    private static final int TITLE_HEIGHT = 15;
    private static final int CHECKBOX = 9;
    private static final int ROW_HEIGHT = 15;
    private static final int SWATCH = 10;
    private static final int PALETTE_STEP = 14;
    private static final int FIELD_WIDTH = 40;
    private static final int FIELD_HEIGHT = 13;
    private static final int BUTTON_HEIGHT = 14;
    private static final int FOOTER_GAP = 4;
    private static final int BACKGROUND = TradingPalette.HOVER;
    private static final int BORDER = TradingPalette.BUTTON_HOVER;

    /** 浮层里一块可点击的区域与点中后做的事。 */
    private record Zone(int left, int top, int right, int bottom, Runnable action) {
        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    private final Font font;
    private final EditBox[] boxes = new EditBox[IndicatorSettings.MAX_LINES];
    /** 每个输入框这一帧画出来的框（含边距）{左, 上, 右, 下}；点击落在这里就算点中输入框。 */
    private final int[][] fields = new int[IndicatorSettings.MAX_LINES][];
    private final List<Zone> zones = new ArrayList<>();
    private boolean open;
    /** 正展开色盘的那一行；-1 表示没有。 */
    private int paletteRow = -1;
    /** 周期输入不合法的那一行；-1 表示没有。 */
    private int errorRow = -1;
    private boolean syncing;
    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;

    IndicatorPanel(Font font) {
        this.font = font;
        for (int i = 0; i < boxes.length; i++) {
            int row = i;
            EditBox box = new EditBox(font, 0, 0, FIELD_WIDTH - 8, font.lineHeight,
                    Component.translatable("screen.creepercapital.trading.indicator.period_field"));
            box.setBordered(false);
            box.setMaxLength(3);
            box.setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9'));
            box.setResponder(text -> onPeriodTyped(row, text));
            box.visible = false;
            boxes[i] = box;
        }
    }

    List<EditBox> inputs() {
        return List.of(boxes);
    }

    boolean isOpen() {
        return open;
    }

    void toggle() {
        if (open) {
            close();
        } else {
            open = true;
        }
    }

    void close() {
        open = false;
        paletteRow = -1;
        errorRow = -1;
        unfocusInputs();
    }

    /**
     * 开发用（自动截图）：展开浮层，并展开第 {@code paletteRow} 行的色盘（-1 不展开）；
     * {@code typedText} 非空时让第 {@code typedRow} 行的输入框获得焦点并填入这段文字（演示不合法输入的提示）。
     */
    void devOpen(int paletteRow, int typedRow, String typedText) {
        open = true;
        this.paletteRow = paletteRow;
        if (typedText != null && typedRow >= 0 && typedRow < boxes.length) {
            boxes[typedRow].visible = true;
            boxes[typedRow].setFocused(true);
            boxes[typedRow].setValue(typedText);
        }
    }

    boolean contains(double x, double y) {
        return open && x >= panelLeft && x < panelRight && y >= panelTop && y < panelBottom;
    }

    boolean anyInputFocused() {
        for (EditBox box : boxes) {
            if (box.isFocused()) {
                return true;
            }
        }
        return false;
    }

    void unfocusInputs() {
        for (EditBox box : boxes) {
            box.setFocused(false);
        }
    }

    /** 左键点在浮层里：做对应的事。返回是否点在浮层内（点在浮层内的一律由它吃掉）。 */
    boolean mouseClicked(double x, double y, int button) {
        if (!contains(x, y)) {
            return false;
        }
        unfocusInputs();
        if (button == 0) {
            for (Zone zone : List.copyOf(zones)) {
                if (zone.contains(x, y)) {
                    CuePlayer.play(Cue.BUTTON_CLICK);
                    zone.action().run();
                    break;
                }
            }
        }
        return true;
    }

    /**
     * 点在某个周期输入框画出来的区域里（含边距）就由这里处理：把点击坐标夹回文字区域再交给输入框定位光标。
     *
     * @return 被点中的输入框；没点在任何输入框上返回 {@code null}
     */
    EditBox mouseClickedInput(MouseButtonEvent event, boolean doubleClick) {
        if (!open || event.button() != 0) {
            return null;
        }
        int count = ClientConfig.indicators().lines().size();
        for (int i = 0; i < count; i++) {
            int[] field = fields[i];
            if (field == null || event.x() < field[0] || event.x() >= field[2] || event.y() < field[1] || event.y() >= field[3]) {
                continue;
            }
            EditBox box = boxes[i];
            double x = Math.max(box.getX(), Math.min(event.x(), box.getX() + box.getWidth() - 1));
            double y = Math.max(box.getY(), Math.min(event.y(), box.getY() + box.getHeight() - 1));
            unfocusInputs();
            return box.mouseClicked(new MouseButtonEvent(x, y, event.buttonInfo()), doubleClick) ? box : null;
        }
        return null;
    }

    private void onPeriodTyped(int row, String text) {
        if (syncing) {
            return;
        }
        OptionalInt period = IndicatorSettings.parsePeriod(text);
        IndicatorSettings settings = ClientConfig.indicators();
        if (period.isEmpty() || row >= settings.lines().size()) {
            errorRow = row;
            return;
        }
        errorRow = -1;
        if (settings.lines().get(row).period() != period.getAsInt()) {
            ClientConfig.setIndicators(settings.withPeriod(row, period.getAsInt()));
        }
    }

    /** 浮层的像素高度（按当前行数、色盘和提示是否展开）。 */
    private int height(IndicatorSettings settings) {
        int height = TITLE_HEIGHT + (settings.lines().size() + 1) * ROW_HEIGHT + FOOTER_GAP + BUTTON_HEIGHT + PAD;
        // 色盘和错误提示不会同时出现（点颜色块会让输入框失焦、提示消失），共用一份空间
        int extra = 0;
        if (paletteRow >= 0 && paletteRow < settings.lines().size()) {
            extra = PALETTE_STEP + 1;
        }
        if (errorRow >= 0) {
            extra = Math.max(extra, font.lineHeight + 3);
        }
        return height + extra;
    }

    /** 画浮层：左上角在 {@code (left, top)}，宽度不超过 {@code right - left}。画完后记下各处可点击的区域。 */
    void extract(GuiGraphicsExtractor graphics, int left, int top, int right, int mouseX, int mouseY, float partialTick) {
        zones.clear();
        for (int i = 0; i < boxes.length; i++) {
            fields[i] = null;
            boxes[i].visible = false;
        }
        if (!open) {
            return;
        }
        IndicatorSettings settings = ClientConfig.indicators();
        int lines = settings.lines().size();
        if (paletteRow >= lines) {
            paletteRow = -1;
        }
        // 没有输入框在编辑时，提示消失（输入框里的文字稍后回到当前生效的周期）
        if (!anyInputFocused()) {
            errorRow = -1;
        }
        int width = Math.min(WIDTH, right - left);
        int height = height(settings);
        panelLeft = left;
        panelTop = top;
        panelRight = left + width;
        panelBottom = top + height;
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, BACKGROUND);
        graphics.outline(panelLeft, panelTop, width, height, BORDER);

        // 标题行：齿轮 + 标题 + 关闭
        KlineChart.extractGear(graphics, panelLeft + PAD, panelTop + (TITLE_HEIGHT - 9) / 2, TradingPalette.BRAND_YELLOW);
        Component title = Component.translatable("screen.creepercapital.trading.indicator.title");
        graphics.text(font, title, panelLeft + PAD + 13, GuiText.centeredY(font, panelTop, panelTop + TITLE_HEIGHT),
                TradingPalette.TEXT_PRIMARY, false);
        int closeLeft = panelRight - PAD - 11;
        int[] close = {closeLeft, panelTop + 2, closeLeft + 11, panelTop + TITLE_HEIGHT - 2};
        textButton(graphics, close, "×", TradingPalette.TEXT_SECONDARY, mouseX, mouseY);
        zones.add(new Zone(close[0], close[1], close[2], close[3], this::close));
        Component reset = Component.translatable("screen.creepercapital.trading.indicator.reset");
        int resetWidth = font.width(reset) + 10;
        int[] resetBox = {close[0] - 4 - resetWidth, panelTop + 2, close[0] - 4, panelTop + TITLE_HEIGHT - 1};
        filledButton(graphics, resetBox, reset, true, mouseX, mouseY);
        zones.add(new Zone(resetBox[0], resetBox[1], resetBox[2], resetBox[3], () -> {
            ClientConfig.setIndicators(ClientConfig.indicators().reset());
            paletteRow = -1;
            errorRow = -1;
        }));

        int y = panelTop + TITLE_HEIGHT;
        for (int i = 0; i < lines; i++) {
            y = extractRow(graphics, settings, i, y, mouseX, mouseY, partialTick);
        }
        y = extractVolumeRow(graphics, settings, y, mouseX, mouseY);
        if (errorRow >= 0) {
            String message = Component.translatable("screen.creepercapital.trading.indicator.invalid_period",
                    IndicatorSettings.MIN_PERIOD, IndicatorSettings.MAX_PERIOD).getString();
            graphics.text(font, GuiText.fit(font, message, width - PAD * 2), panelLeft + PAD, y + 1, TradingPalette.ERROR, false);
            y += font.lineHeight + 3;
        }
        y += FOOTER_GAP;
        extractFooter(graphics, settings, y, mouseX, mouseY);
    }

    private int extractRow(GuiGraphicsExtractor graphics, IndicatorSettings settings, int row, int rowTop, int mouseX,
            int mouseY, float partialTick) {
        MaLine line = settings.lines().get(row);
        int y = rowTop;
        int x = panelLeft + PAD;
        // 显示复选框
        extractCheckbox(graphics, x, y, line.visible(), mouseX, mouseY,
                () -> ClientConfig.setIndicators(ClientConfig.indicators().toggledLine(row)));
        x += CHECKBOX + 5;
        // 颜色块：点开 / 收起色盘
        int swatchTop = y + (ROW_HEIGHT - SWATCH) / 2;
        graphics.fill(x, swatchTop, x + SWATCH, swatchTop + SWATCH, line.color());
        graphics.outline(x - 1, swatchTop - 1, SWATCH + 2, SWATCH + 2, paletteRow == row ? TradingPalette.TEXT_PRIMARY : BORDER);
        zones.add(new Zone(x - 1, swatchTop - 1, x + SWATCH + 1, swatchTop + SWATCH + 1,
                () -> paletteRow = paletteRow == row ? -1 : row));
        x += SWATCH + 6;
        graphics.text(font, "MA", x, GuiText.centeredY(font, y, y + ROW_HEIGHT),
                line.visible() ? TradingPalette.TEXT_PRIMARY : TradingPalette.DISABLED, false);
        x += font.width("MA") + 5;

        // 周期输入框
        int fieldTop = y + (ROW_HEIGHT - FIELD_HEIGHT) / 2;
        fields[row] = new int[] {x, fieldTop, x + FIELD_WIDTH, fieldTop + FIELD_HEIGHT};
        EditBox box = boxes[row];
        graphics.fill(x, fieldTop, x + FIELD_WIDTH, fieldTop + FIELD_HEIGHT, TradingPalette.BACKGROUND);
        graphics.outline(x, fieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                errorRow == row ? TradingPalette.ERROR : box.isFocused() ? TradingPalette.BRAND_YELLOW : TradingPalette.DIVIDER);
        box.visible = true;
        box.setX(x + 4);
        box.setY(GuiText.centeredY(font, fieldTop, fieldTop + FIELD_HEIGHT));
        box.setWidth(FIELD_WIDTH - 8);
        String shown = String.valueOf(line.period());
        if (!box.isFocused() && !box.getValue().equals(shown)) {
            syncing = true;
            box.setValue(shown);
            syncing = false;
        }
        box.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, Component.translatable("screen.creepercapital.trading.indicator.period_unit"), x + FIELD_WIDTH + 4,
                GuiText.centeredY(font, y, y + ROW_HEIGHT), TradingPalette.TEXT_SECONDARY, false);

        // 删除
        int deleteLeft = panelRight - PAD - 11;
        int[] delete = {deleteLeft, y + 2, deleteLeft + 11, y + ROW_HEIGHT - 1};
        textButton(graphics, delete, "×", TradingPalette.ERROR, mouseX, mouseY);
        zones.add(new Zone(delete[0], delete[1], delete[2], delete[3], () -> {
            ClientConfig.setIndicators(ClientConfig.indicators().removed(row));
            paletteRow = -1;
            errorRow = -1;
        }));
        y += ROW_HEIGHT;

        // 色盘：从预设里选
        if (paletteRow == row) {
            int px = panelLeft + PAD;
            for (int c = 0; c < IndicatorSettings.PALETTE.length; c++) {
                int color = c;
                int left = px + c * PALETTE_STEP;
                graphics.fill(left, y + 2, left + SWATCH, y + 2 + SWATCH, IndicatorSettings.PALETTE[c]);
                if (c == line.colorIndex()) {
                    graphics.outline(left - 1, y + 1, SWATCH + 2, SWATCH + 2, TradingPalette.TEXT_PRIMARY);
                }
                zones.add(new Zone(left - 1, y + 1, left + SWATCH + 1, y + SWATCH + 3, () -> {
                    ClientConfig.setIndicators(ClientConfig.indicators().withColor(row, color));
                    paletteRow = -1;
                }));
            }
            y += PALETTE_STEP + 1;
        }
        return y;
    }

    private void extractFooter(GuiGraphicsExtractor graphics, IndicatorSettings settings, int y, int mouseX, int mouseY) {
        boolean canAdd = settings.lines().size() < IndicatorSettings.MAX_LINES;
        Component add = Component.translatable(canAdd ? "screen.creepercapital.trading.indicator.add"
                : "screen.creepercapital.trading.indicator.add_full", IndicatorSettings.MAX_LINES);
        int addWidth = font.width(add) + 10;
        int[] addBox = {panelLeft + PAD, y, panelLeft + PAD + addWidth, y + BUTTON_HEIGHT};
        filledButton(graphics, addBox, add, canAdd, mouseX, mouseY);
        if (canAdd) {
            zones.add(new Zone(addBox[0], addBox[1], addBox[2], addBox[3], () -> {
                ClientConfig.setIndicators(ClientConfig.indicators().added());
                errorRow = -1;
            }));
        }
    }

    /** 成交量副图的显示复选框一行。 */
    private int extractVolumeRow(GuiGraphicsExtractor graphics, IndicatorSettings settings, int y, int mouseX, int mouseY) {
        int x = panelLeft + PAD;
        extractCheckbox(graphics, x, y, settings.volumeVisible(), mouseX, mouseY,
                () -> ClientConfig.setIndicators(ClientConfig.indicators().toggledVolume()));
        graphics.text(font, Component.translatable("screen.creepercapital.trading.kline.volume"), x + CHECKBOX + 5,
                GuiText.centeredY(font, y, y + ROW_HEIGHT),
                settings.volumeVisible() ? TradingPalette.TEXT_PRIMARY : TradingPalette.DISABLED, false);
        return y + ROW_HEIGHT;
    }

    /** 9x9 的复选框，选中时里面填黄色；点击区域比框大一圈。 */
    private void extractCheckbox(GuiGraphicsExtractor graphics, int x, int rowTop, boolean checked, int mouseX, int mouseY,
            Runnable toggle) {
        int top = rowTop + (ROW_HEIGHT - CHECKBOX) / 2;
        boolean hovered = mouseX >= x - 1 && mouseX < x + CHECKBOX + 1 && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;
        graphics.outline(x, top, CHECKBOX, CHECKBOX, checked ? TradingPalette.BRAND_YELLOW
                : hovered ? TradingPalette.TEXT_PRIMARY : TradingPalette.TEXT_SECONDARY);
        if (checked) {
            graphics.fill(x + 2, top + 2, x + CHECKBOX - 2, top + CHECKBOX - 2, TradingPalette.BRAND_YELLOW);
        }
        zones.add(new Zone(x - 1, rowTop, x + CHECKBOX + 1, rowTop + ROW_HEIGHT, toggle));
    }

    private void filledButton(GuiGraphicsExtractor graphics, int[] box, Component label, boolean enabled, int mouseX, int mouseY) {
        boolean hovered = enabled && mouseX >= box[0] && mouseX < box[2] && mouseY >= box[1] && mouseY < box[3];
        graphics.fill(box[0], box[1], box[2], box[3], hovered ? TradingPalette.BUTTON_HOVER : TradingPalette.BUTTON);
        graphics.centeredText(font, label, (box[0] + box[2]) / 2, GuiText.centeredY(font, box[1], box[3]),
                enabled ? TradingPalette.TEXT_PRIMARY : TradingPalette.DISABLED);
        if (hovered) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void textButton(GuiGraphicsExtractor graphics, int[] box, String text, int color, int mouseX, int mouseY) {
        boolean hovered = mouseX >= box[0] && mouseX < box[2] && mouseY >= box[1] && mouseY < box[3];
        if (hovered) {
            graphics.fill(box[0], box[1], box[2], box[3], TradingPalette.BUTTON);
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        graphics.centeredText(font, text, (box[0] + box[2]) / 2, GuiText.centeredY(font, box[1], box[3]),
                hovered ? TradingPalette.TEXT_PRIMARY : color);
    }
}
