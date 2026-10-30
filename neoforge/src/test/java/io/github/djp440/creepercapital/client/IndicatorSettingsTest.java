package io.github.djp440.creepercapital.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.client.IndicatorSettings.MaLine;

/**
 * 指标设置（#34 / #38）：缺省值、增删与校验、显示 / 隐藏，以及保存到客户端配置的字符串往返。
 */
class IndicatorSettingsTest {

    private static List<Integer> periods(IndicatorSettings settings) {
        return settings.lines().stream().map(MaLine::period).toList();
    }

    @Test
    @DisplayName("缺省：MA(7) / MA(25) / MA(99) 都显示，成交量副图显示")
    void defaults() {
        IndicatorSettings settings = IndicatorSettings.defaults();

        assertEquals(List.of(7, 25, 99), periods(settings));
        assertTrue(settings.lines().stream().allMatch(MaLine::visible));
        assertTrue(settings.volumeVisible());
        assertEquals(TradingPalette.MA_7, settings.lines().get(0).color());
        assertEquals(TradingPalette.MA_25, settings.lines().get(1).color());
        assertEquals(TradingPalette.MA_99, settings.lines().get(2).color());
    }

    @Test
    @DisplayName("点图例切换显示 / 隐藏：只改被点的那一项；隐藏的均线不在「要计算 / 绘制」的列表里")
    void togglingVisibility() {
        IndicatorSettings settings = IndicatorSettings.defaults().toggledLine(1);

        assertFalse(settings.lines().get(1).visible());
        assertTrue(settings.lines().get(0).visible() && settings.lines().get(2).visible());
        assertEquals(List.of(7, 99), settings.visibleLines().stream().map(MaLine::period).toList());
        assertTrue(settings.volumeVisible());

        IndicatorSettings noVolume = settings.toggledVolume();
        assertFalse(noVolume.volumeVisible());
        assertEquals(settings.lines(), noVolume.lines());
        // 再点一次恢复
        assertTrue(noVolume.toggledVolume().volumeVisible());
        assertTrue(noVolume.toggledLine(1).lines().get(1).visible());
    }

    @Test
    @DisplayName("周期必须是 1~500 的整数：边界合法，0、501、空、非数字、负数、小数都不合法")
    void periodValidation() {
        assertEquals(OptionalInt.of(1), IndicatorSettings.parsePeriod("1"));
        assertEquals(OptionalInt.of(500), IndicatorSettings.parsePeriod("500"));
        assertEquals(OptionalInt.of(30), IndicatorSettings.parsePeriod(" 30 "));
        for (String bad : new String[] {"0", "501", "", " ", "abc", "-3", "7.5", "99999999999"}) {
            assertEquals(OptionalInt.empty(), IndicatorSettings.parsePeriod(bad), "「" + bad + "」");
        }
    }

    @Test
    @DisplayName("改周期、改颜色只动那一条；颜色下标越界时夹回色盘范围")
    void editingALine() {
        IndicatorSettings settings = IndicatorSettings.defaults().withPeriod(0, 10).withColor(2, 5);

        assertEquals(List.of(10, 25, 99), periods(settings));
        assertEquals(IndicatorSettings.PALETTE[5], settings.lines().get(2).color());
        assertEquals(IndicatorSettings.PALETTE.length - 1, settings.withColor(0, 999).lines().get(0).colorIndex());
        assertEquals(0, settings.withColor(0, -4).lines().get(0).colorIndex());
    }

    @Test
    @DisplayName("增删：最多 5 条，第 6 条加不进去；新增的周期、颜色不与现有的重复；可以删到 0 条")
    void addingAndRemoving() {
        IndicatorSettings settings = IndicatorSettings.defaults();
        for (int i = 0; i < 5; i++) {
            settings = settings.added();
        }

        assertEquals(IndicatorSettings.MAX_LINES, settings.lines().size());
        assertEquals(5, new java.util.HashSet<>(periods(settings)).size(), "周期互不相同");
        assertEquals(5, settings.lines().stream().map(MaLine::colorIndex).distinct().count(), "颜色互不相同");
        assertEquals(List.of(7, 25, 99, 50, 200), periods(settings));
        assertTrue(settings.lines().get(3).visible());

        IndicatorSettings removed = settings.removed(0);
        assertEquals(List.of(25, 99, 50, 200), periods(removed));
        IndicatorSettings empty = IndicatorSettings.defaults().removed(0).removed(0).removed(0);
        assertTrue(empty.lines().isEmpty());
    }

    @Test
    @DisplayName("恢复默认：回到 7 / 25 / 99、全部显示")
    void resetRestoresDefaults() {
        IndicatorSettings custom = IndicatorSettings.defaults().withPeriod(0, 5).toggledLine(2).toggledVolume().added();

        assertEquals(IndicatorSettings.defaults(), custom.reset());
    }

    @Test
    @DisplayName("配置往返：自定义周期、颜色、显示状态、成交量、零条均线，编码再解码得到完全相同的设置")
    void configRoundTrip() {
        IndicatorSettings custom = IndicatorSettings.defaults().withPeriod(0, 10).withColor(0, 6).toggledLine(1)
                .added().toggledVolume();
        assertEquals(custom, IndicatorSettings.decode(custom.encode()));
        assertEquals(IndicatorSettings.defaults(), IndicatorSettings.decode(IndicatorSettings.defaults().encode()));

        IndicatorSettings none = IndicatorSettings.defaults().removed(0).removed(0).removed(0);
        assertEquals(none, IndicatorSettings.decode(none.encode()));
    }

    @Test
    @DisplayName("读到坏配置不崩溃：整体乱码回缺省；坏的片段丢掉、好的保留；超过 5 条只取前 5 条")
    void decodeIsTolerant() {
        IndicatorSettings defaults = IndicatorSettings.defaults();
        for (String garbage : new String[] {"", "   ", "hello", "ma=;;;", "volume", null}) {
            IndicatorSettings decoded = IndicatorSettings.decode(garbage);
            assertTrue(decoded.volumeVisible());
            assertTrue(decoded.lines().isEmpty() || decoded.equals(defaults), "「" + garbage + "」");
        }
        assertEquals(defaults, IndicatorSettings.decode("hello"));
        assertEquals(defaults, IndicatorSettings.decode(null));

        // 周期 0、501、非数字的片段被丢掉
        IndicatorSettings partial = IndicatorSettings.decode("ma=7:0:1,0:1:1,501:2:1,x:3:1,25:4:0;volume=0");
        assertEquals(List.of(7, 25), periods(partial));
        assertFalse(partial.lines().get(1).visible());
        assertFalse(partial.volumeVisible());

        IndicatorSettings many = IndicatorSettings.decode("ma=1:0:1,2:1:1,3:2:1,4:3:1,5:4:1,6:5:1,7:6:1;volume=1");
        assertEquals(List.of(1, 2, 3, 4, 5), periods(many));
    }
}
