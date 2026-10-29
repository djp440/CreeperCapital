package io.github.djp440.creepercapital.client;

import java.util.List;
import java.util.Locale;

import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.Indicators;
import io.github.djp440.creepercapital.core.market.MarketTime;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * K 线图（币安配色，涨跌色跟随 {@link ColorScheme}）：上方是蜡烛图加三条均线，下方是成交量柱，右侧是价格轴，底部是市场时间轴，
 * 最新一根贴着右边，往左依次是更早的 K 线。鼠标悬停某根时图例显示它的开高低收、成交量和均线值，时间轴上标出它的时间。
 * 图例逐项排，放不下的项不画；价格轴刻度避开最新价格标签，不与成交量刻度相碰。
 * <p>
 * 只负责把已有的 K 线画出来，不持有状态；放不下的更早的 K 线不画。
 */
final class KlineChart {
    private static final int[] MA_PERIODS = {7, 25, 99};
    private static final int[] MA_COLORS = {BinancePalette.MA_7, BinancePalette.MA_25, BinancePalette.MA_99};

    /** 每根 K 线占的横向像素，实体宽 {@link #BODY}，影线在正中间 1 像素。 */
    private static final int SLOT = 7;
    private static final int BODY = 5;
    /** 价格轴最窄的宽度；实际宽度按最高价的文字宽度算。 */
    private static final int MIN_AXIS_WIDTH = 36;
    private static final int GRID_LINES = 4;
    private static final int VOLUME_GAP = 6;
    private static final int MIN_VOLUME_HEIGHT = 24;
    private static final int LEGEND_GAP = 6;
    /** 成交量柱的半透明度，叠在网格上不抢眼。 */
    private static final int VOLUME_ALPHA = 0xB3000000;

    private final Font font;

    KlineChart(Font font) {
        this.font = font;
    }

    /**
     * @param left   图表区域左边界（含图例和价格轴）
     * @param candles 从旧到新，不能为空，最后一根是正在走的
     */
    void extract(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, CandlePeriod period,
            List<Candle> candles, int mouseX, int mouseY) {
        int legendHeight = font.lineHeight + 2;
        int timeAxisTop = bottom - font.lineHeight - 2;
        int mainTop = top + legendHeight * 2 + 2;
        int available = timeAxisTop - 2 - mainTop - VOLUME_GAP;
        int volumeHeight = Math.max(MIN_VOLUME_HEIGHT, available / 5);
        int mainHeight = available - volumeHeight;
        if (mainHeight < 16) {
            return;
        }
        int volumeTop = mainTop + mainHeight + VOLUME_GAP;
        int volumeBottom = volumeTop + volumeHeight;
        double widest = 0;
        for (Candle candle : candles) {
            widest = Math.max(widest, candle.high());
        }
        int axisLeft = right - Math.max(MIN_AXIS_WIDTH, font.width(MarketFormat.price(widest)) + 6);
        int plotRight = axisLeft - 4;

        int visible = Math.min(candles.size(), (plotRight - left) / SLOT);
        int first = candles.size() - visible;
        if (visible == 0) {
            return; // 窗口窄到一根都放不下
        }
        int slotsLeft = plotRight - visible * SLOT;

        double[][] ma = new double[MA_PERIODS.length][];
        for (int k = 0; k < MA_PERIODS.length; k++) {
            ma[k] = Indicators.movingAverage(candles, MA_PERIODS[k]);
        }

        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        double maxVolume = 0;
        for (int i = first; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            high = Math.max(high, candle.high());
            low = Math.min(low, candle.low());
            maxVolume = Math.max(maxVolume, candle.volume());
            for (double[] line : ma) {
                if (!Double.isNaN(line[i])) {
                    high = Math.max(high, line[i]);
                    low = Math.min(low, line[i]);
                }
            }
        }
        double pad = high - low < 1e-9 ? Math.max(Math.abs(high) * 0.005, 0.01) : (high - low) * 0.08;
        high += pad;
        low -= pad;
        PriceScale scale = new PriceScale(mainTop, mainHeight, high, low);

        int lastPriceY = scale.y(candles.get(candles.size() - 1).close());
        extractGrid(graphics, left, plotRight, axisLeft, right, mainTop, mainHeight, volumeTop, volumeBottom, scale, lastPriceY);
        extractTimeAxis(graphics, period, candles, left, first, visible, slotsLeft, right, mainTop, volumeBottom, timeAxisTop);

        for (int j = 0; j < visible; j++) {
            Candle candle = candles.get(first + j);
            int slotLeft = slotsLeft + j * SLOT;
            int color = directionColor(candle);
            int wickX = slotLeft + SLOT / 2;
            graphics.fill(wickX, scale.y(candle.high()), wickX + 1, scale.y(candle.low()) + 1, color);
            int bodyTop = scale.y(Math.max(candle.open(), candle.close()));
            int bodyBottom = Math.max(bodyTop + 1, scale.y(Math.min(candle.open(), candle.close())) + 1);
            graphics.fill(slotLeft + (SLOT - BODY) / 2, bodyTop, slotLeft + (SLOT - BODY) / 2 + BODY, bodyBottom, color);

            if (maxVolume > 0) {
                int barHeight = Math.max(1, (int) Math.round(candle.volume() / maxVolume * (volumeHeight - 1)));
                graphics.fill(slotLeft + (SLOT - BODY) / 2, volumeBottom - barHeight,
                        slotLeft + (SLOT - BODY) / 2 + BODY, volumeBottom, (color & 0x00FFFFFF) | VOLUME_ALPHA);
            }
        }
        graphics.text(font, MarketFormat.volume(maxVolume), axisLeft + 2, volumeTop + 1, BinancePalette.TEXT_SECONDARY, false);

        for (int k = 0; k < MA_PERIODS.length; k++) {
            extractAverage(graphics, ma[k], first, visible, slotsLeft, scale, MA_COLORS[k]);
        }

        // 十字线先画，最新价格标签后画：两个价格标签重叠时，最新价格盖在上面
        int hovered = -1;
        if (mouseX >= slotsLeft && mouseX < plotRight && mouseY >= mainTop && mouseY < volumeBottom) {
            hovered = first + (mouseX - slotsLeft) / SLOT;
            int centerX = slotsLeft + (hovered - first) * SLOT + SLOT / 2;
            extractCrosshair(graphics, left, plotRight, axisLeft, right, mainTop, mainHeight, volumeBottom, centerX, mouseY, scale);
            String time = MarketFormat.marketTime(new MarketTime(candles.get(hovered).start()));
            int timeWidth = font.width(time) + 6;
            int timeLeft = Math.max(left, Math.min(centerX - timeWidth / 2, plotRight - timeWidth));
            graphics.fill(timeLeft, timeAxisTop, timeLeft + timeWidth, bottom, BinancePalette.DIVIDER);
            graphics.text(font, time, timeLeft + 3, timeAxisTop + 1, BinancePalette.TEXT_PRIMARY, false);
        }
        extractLastPrice(graphics, candles.get(candles.size() - 1), left, plotRight, axisLeft, right, scale);
        extractLegend(graphics, left, top, right, legendHeight, candles, ma, hovered < 0 ? candles.size() - 1 : hovered);
    }

    /** 网格线与价格轴刻度：刻度文字不出主图区域，离最新价格标签太近的刻度不画（免得叠在一起）。 */
    private void extractGrid(GuiGraphicsExtractor graphics, int left, int plotRight, int axisLeft, int right,
            int mainTop, int mainHeight, int volumeTop, int volumeBottom, PriceScale scale, int lastPriceY) {
        for (int line = 0; line <= GRID_LINES; line++) {
            int y = mainTop + line * (mainHeight - 1) / GRID_LINES;
            graphics.horizontalLine(left, plotRight, y, BinancePalette.GRID);
            int labelTop = Math.max(mainTop, Math.min(y - font.lineHeight / 2, mainTop + mainHeight - font.lineHeight));
            if (Math.abs(labelTop + font.lineHeight / 2 - lastPriceY) <= font.lineHeight) {
                continue;
            }
            graphics.text(font, MarketFormat.price(scale.priceAt(y)), axisLeft + 2, labelTop, BinancePalette.TEXT_SECONDARY, false);
        }
        graphics.horizontalLine(left, plotRight, volumeTop - 1, BinancePalette.GRID);
        graphics.horizontalLine(left, plotRight, volumeBottom, BinancePalette.GRID);
    }

    /** 时间轴：在固定间隔的 K 线下标注市场时间，并画一条竖向网格线。 */
    private void extractTimeAxis(GuiGraphicsExtractor graphics, CandlePeriod period, List<Candle> candles, int left,
            int first, int visible, int slotsLeft, int right, int mainTop, int volumeBottom, int timeAxisTop) {
        int labelEvery = labelEvery(period);
        int lastLabelEnd = Integer.MIN_VALUE;
        for (int j = 0; j < visible; j++) {
            long start = candles.get(first + j).start();
            if (Math.floorMod(Math.floorDiv(start, period.seconds()), labelEvery) != 0) {
                continue;
            }
            int centerX = slotsLeft + j * SLOT + SLOT / 2;
            String label = timeLabel(period, start);
            int labelWidth = font.width(label);
            int labelX = centerX - labelWidth / 2;
            if (labelX < Math.max(left, lastLabelEnd + 4) || labelX + labelWidth > right) {
                continue;
            }
            graphics.verticalLine(centerX, mainTop, volumeBottom, BinancePalette.GRID);
            graphics.text(font, label, labelX, timeAxisTop + 1, BinancePalette.TEXT_SECONDARY, false);
            lastLabelEnd = labelX + labelWidth;
        }
    }

    /** 均线：相邻两点之间按列补满竖向像素，线才是连续的。 */
    private static void extractAverage(GuiGraphicsExtractor graphics, double[] average, int first, int visible,
            int slotsLeft, PriceScale scale, int color) {
        int previousX = 0;
        int previousY = 0;
        boolean hasPrevious = false;
        for (int j = 0; j < visible; j++) {
            double value = average[first + j];
            if (Double.isNaN(value)) {
                hasPrevious = false;
                continue;
            }
            int x = slotsLeft + j * SLOT + SLOT / 2;
            int y = scale.y(value);
            if (hasPrevious) {
                for (int column = previousX; column < x; column++) {
                    int from = previousY + Math.round((float) (y - previousY) * (column - previousX) / (x - previousX));
                    int to = previousY + Math.round((float) (y - previousY) * (column + 1 - previousX) / (x - previousX));
                    graphics.fill(column, Math.min(from, to), column + 1, Math.max(from, to) + 1, color);
                }
            }
            previousX = x;
            previousY = y;
            hasPrevious = true;
        }
    }

    /** 最新价格线：虚线横穿图表，价格轴上用涨跌色底标出价格。 */
    private void extractLastPrice(GuiGraphicsExtractor graphics, Candle latest, int left, int plotRight, int axisLeft,
            int right, PriceScale scale) {
        int color = directionColor(latest);
        int y = scale.y(latest.close());
        for (int x = left; x < plotRight; x += 6) {
            graphics.horizontalLine(x, Math.min(x + 2, plotRight), y, color);
        }
        graphics.fill(axisLeft, y - font.lineHeight / 2 - 1, right, y + font.lineHeight / 2 + 1, color);
        graphics.text(font, MarketFormat.price(latest.close()), axisLeft + 2, y - font.lineHeight / 2,
                BinancePalette.TEXT_PRIMARY, false);
    }

    private void extractCrosshair(GuiGraphicsExtractor graphics, int left, int plotRight, int axisLeft, int right,
            int mainTop, int mainHeight, int volumeBottom, int centerX, int mouseY, PriceScale scale) {
        graphics.verticalLine(centerX, mainTop, volumeBottom - 1, BinancePalette.CROSSHAIR);
        if (mouseY < mainTop + mainHeight) {
            graphics.horizontalLine(left, plotRight, mouseY, BinancePalette.CROSSHAIR);
            graphics.fill(axisLeft, mouseY - font.lineHeight / 2 - 1, right, mouseY + font.lineHeight / 2 + 1,
                    BinancePalette.DIVIDER);
            graphics.text(font, MarketFormat.price(scale.priceAt(mouseY)), axisLeft + 2, mouseY - font.lineHeight / 2,
                    BinancePalette.TEXT_PRIMARY, false);
        }
    }

    /**
     * 图例两行：第一行是选中那根的开高低收和成交量（时间标在时间轴上），第二行是三条均线在那一根的值。
     * 逐项往右排，排到放不下的项就停（不越出图表右边界）。
     */
    private void extractLegend(GuiGraphicsExtractor graphics, int left, int top, int right, int lineHeight,
            List<Candle> candles, double[][] ma, int index) {
        Candle candle = candles.get(index);
        int color = directionColor(candle);
        int y = top + 1;
        int x = left;
        x = extractLegendValue(graphics, x, y, right, "O", MarketFormat.price(candle.open()), color);
        x = extractLegendValue(graphics, x, y, right, "H", MarketFormat.price(candle.high()), color);
        x = extractLegendValue(graphics, x, y, right, "L", MarketFormat.price(candle.low()), color);
        x = extractLegendValue(graphics, x, y, right, "C", MarketFormat.price(candle.close()), color);
        extractLegendValue(graphics, x, y, right, Component.translatable("screen.creepercapital.trading.kline.volume").getString(),
                MarketFormat.volume(candle.volume()), color);

        y += lineHeight;
        x = left;
        for (int k = 0; k < MA_PERIODS.length; k++) {
            double value = ma[k][index];
            String text = "MA(" + MA_PERIODS[k] + ") " + (Double.isNaN(value) ? "--" : MarketFormat.price(value));
            if (x + font.width(text) > right) {
                break;
            }
            graphics.text(font, text, x, y, MA_COLORS[k], false);
            x += font.width(text) + LEGEND_GAP;
        }
    }

    /** 图例里的一项「名称 数值」；放不下时不画，并返回超出右边界的位置，让后面的项也都不画。 */
    private int extractLegendValue(GuiGraphicsExtractor graphics, int x, int y, int right, String label, String value, int color) {
        int valueX = x + font.width(label) + 2;
        int end = valueX + font.width(value);
        if (end > right) {
            return Integer.MAX_VALUE / 2;
        }
        graphics.text(font, label, x, y, BinancePalette.TEXT_SECONDARY, false);
        graphics.text(font, value, valueX, y, color, false);
        return end + LEGEND_GAP;
    }

    /** 收盘不低于开盘为涨，否则为跌（颜色按涨跌色方案）；正在走的那根按当前价格相对开盘算。 */
    private static int directionColor(Candle candle) {
        return candle.close() >= candle.open() ? BinancePalette.up() : BinancePalette.down();
    }

    /** 时间轴上每隔多少根 K 线标一次，按绝对序号对齐，所以新 K 线出现时标注不会跟着滚动。 */
    private static int labelEvery(CandlePeriod period) {
        return switch (period) {
            case M1 -> 15;
            case M5 -> 12;
            case M15 -> 16;
            case H1 -> 12;
            case H4 -> 12;
            case D1 -> 14;
            case W1 -> 8;
        };
    }

    private static String timeLabel(CandlePeriod period, long start) {
        MarketTime time = new MarketTime(start);
        if (period.seconds() >= CandlePeriod.D1.seconds()) {
            return Component.translatable("screen.creepercapital.trading.kline.axis_day", time.day()).getString();
        }
        return Component.translatable("screen.creepercapital.trading.kline.axis_time", time.day(),
                String.format(Locale.ROOT, "%02d:%02d", time.hour(), time.minute())).getString();
    }

    /** 价格和纵坐标的换算：{@code high} 在 {@code top}，{@code low} 在 {@code top + height - 1}。 */
    private record PriceScale(int top, int height, double high, double low) {
        int y(double price) {
            return top + (int) Math.round((high - price) / (high - low) * (height - 1));
        }

        double priceAt(int y) {
            return high - (high - low) * (y - top) / (height - 1);
        }
    }
}
