package io.github.djp440.creepercapital.client;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * 图表指标设置（纯数据 + 纯函数，不碰 Minecraft）：若干条均线（周期、颜色、是否显示）和成交量副图是否显示。
 * 不可变，每次修改返回新对象；客户端配置里保存的是 {@link #encode()} 的字符串。
 * <p>
 * 限制：最多 {@link #MAX_LINES} 条均线，周期为 {@link #MIN_PERIOD}～{@link #MAX_PERIOD} 的整数，颜色从 {@link #PALETTE} 预设色盘里选。
 */
record IndicatorSettings(List<MaLine> lines, boolean volumeVisible) {
    static final int MAX_LINES = 5;
    static final int MIN_PERIOD = 1;
    static final int MAX_PERIOD = 500;
    /** 预设色盘（ARGB）：前三个是缺省三条均线的颜色，不与涨跌色（绿 / 红）混淆。 */
    static final int[] PALETTE = {TradingPalette.MA_7, TradingPalette.MA_25, TradingPalette.MA_99,
            0xFF5AD7F2, 0xFFFF8C42, 0xFFA78BFA, 0xFFEAECEF, 0xFFFF6FA3};
    /** 缺省的三条均线周期。 */
    private static final int[] DEFAULT_PERIODS = {7, 25, 99};
    /** 新增均线时依次尝试的周期，取第一个还没用过的。 */
    private static final int[] SUGGESTED_PERIODS = {7, 25, 99, 50, 200, 10, 30, 60, 120, 5, 20, 100, 250, 3, 15};

    /** 一条均线：{@code colorIndex} 是 {@link #PALETTE} 的下标。 */
    record MaLine(int period, int colorIndex, boolean visible) {
        int color() {
            return PALETTE[colorIndex];
        }
    }

    IndicatorSettings {
        lines = List.copyOf(lines);
    }

    /** 缺省：MA(7) / MA(25) / MA(99) 与成交量都显示。 */
    static IndicatorSettings defaults() {
        List<MaLine> lines = new ArrayList<>();
        for (int i = 0; i < DEFAULT_PERIODS.length; i++) {
            lines.add(new MaLine(DEFAULT_PERIODS[i], i, true));
        }
        return new IndicatorSettings(lines, true);
    }

    /** 恢复默认。 */
    IndicatorSettings reset() {
        return defaults();
    }

    /** 正在显示的均线（隐藏的不计算、不绘制、不参与价格轴范围）。 */
    List<MaLine> visibleLines() {
        return lines.stream().filter(MaLine::visible).toList();
    }

    /** 周期输入框的文字是否合法：1~500 的整数（首尾空白忽略）。 */
    static OptionalInt parsePeriod(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 4 || !trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalInt.empty();
        }
        int period = Integer.parseInt(trimmed);
        return period >= MIN_PERIOD && period <= MAX_PERIOD ? OptionalInt.of(period) : OptionalInt.empty();
    }

    IndicatorSettings toggledLine(int index) {
        MaLine line = lines.get(index);
        return replaced(index, new MaLine(line.period(), line.colorIndex(), !line.visible()));
    }

    IndicatorSettings toggledVolume() {
        return new IndicatorSettings(lines, !volumeVisible);
    }

    /** 改第 {@code index} 条的周期；调用方先用 {@link #parsePeriod} 校验。 */
    IndicatorSettings withPeriod(int index, int period) {
        if (period < MIN_PERIOD || period > MAX_PERIOD) {
            throw new IllegalArgumentException("MA period must be within " + MIN_PERIOD + "~" + MAX_PERIOD + ", got " + period);
        }
        MaLine line = lines.get(index);
        return replaced(index, new MaLine(period, line.colorIndex(), line.visible()));
    }

    /** 改第 {@code index} 条的颜色；色盘下标越界时夹回范围。 */
    IndicatorSettings withColor(int index, int colorIndex) {
        MaLine line = lines.get(index);
        return replaced(index, new MaLine(line.period(), clampColor(colorIndex), line.visible()));
    }

    /** 新增一条均线（显示）：周期取建议周期里第一个没用过的，颜色取色盘里第一个没用过的；已满 {@link #MAX_LINES} 条时原样返回。 */
    IndicatorSettings added() {
        if (lines.size() >= MAX_LINES) {
            return this;
        }
        int period = SUGGESTED_PERIODS[0];
        for (int candidate : SUGGESTED_PERIODS) {
            if (lines.stream().noneMatch(line -> line.period() == candidate)) {
                period = candidate;
                break;
            }
        }
        int color = lines.size() % PALETTE.length;
        for (int i = 0; i < PALETTE.length; i++) {
            int candidate = i;
            if (lines.stream().noneMatch(line -> line.colorIndex() == candidate)) {
                color = i;
                break;
            }
        }
        List<MaLine> next = new ArrayList<>(lines);
        next.add(new MaLine(period, color, true));
        return new IndicatorSettings(next, volumeVisible);
    }

    IndicatorSettings removed(int index) {
        List<MaLine> next = new ArrayList<>(lines);
        next.remove(index);
        return new IndicatorSettings(next, volumeVisible);
    }

    private IndicatorSettings replaced(int index, MaLine line) {
        List<MaLine> next = new ArrayList<>(lines);
        next.set(index, line);
        return new IndicatorSettings(next, volumeVisible);
    }

    private static int clampColor(int colorIndex) {
        return Math.max(0, Math.min(PALETTE.length - 1, colorIndex));
    }

    // ===== 客户端配置里的字符串：ma=周期:颜色下标:是否显示,…;volume=0|1 =====

    /** 编码成配置字符串，例如 {@code ma=7:0:1,25:1:0;volume=1}。 */
    String encode() {
        StringBuilder text = new StringBuilder("ma=");
        for (int i = 0; i < lines.size(); i++) {
            MaLine line = lines.get(i);
            text.append(i == 0 ? "" : ",").append(line.period()).append(':').append(line.colorIndex()).append(':')
                    .append(line.visible() ? 1 : 0);
        }
        return text.append(";volume=").append(volumeVisible ? 1 : 0).toString();
    }

    /**
     * 解码配置字符串，容错：{@code null} / 乱码 / 没有 {@code ma=} 段时用缺省的三条均线；坏的片段（周期越界、不是数字）丢掉，
     * 好的保留，最多取前 {@link #MAX_LINES} 条；{@code volume} 缺失或不是 0 / 1 时按显示。
     */
    static IndicatorSettings decode(String text) {
        if (text == null) {
            return defaults();
        }
        List<MaLine> lines = null;
        boolean volume = true;
        for (String part : text.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("ma=")) {
                lines = decodeLines(trimmed.substring(3));
            } else if (trimmed.equals("volume=0")) {
                volume = false;
            }
        }
        return lines == null ? new IndicatorSettings(defaults().lines(), volume) : new IndicatorSettings(lines, volume);
    }

    private static List<MaLine> decodeLines(String text) {
        List<MaLine> lines = new ArrayList<>();
        for (String item : text.split(",")) {
            String[] fields = item.trim().split(":");
            if (fields.length != 3 || lines.size() >= MAX_LINES) {
                continue;
            }
            OptionalInt period = parsePeriod(fields[0]);
            OptionalInt color = parseSmallInt(fields[1]);
            if (period.isEmpty() || color.isEmpty()) {
                continue;
            }
            lines.add(new MaLine(period.getAsInt(), clampColor(color.getAsInt()), !fields[2].trim().equals("0")));
        }
        return lines;
    }

    private static OptionalInt parseSmallInt(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 3 || !trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(trimmed));
    }
}
