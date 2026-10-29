package io.github.djp440.creepercapital.client;

import java.util.List;
import java.util.Locale;

import io.github.djp440.creepercapital.core.kline.Candle;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * 图表类型：K 线（蜡烛）或线形图（按收盘价连线，带渐变面积填充）。两种类型共用同一份 K 线缓存，切换不重新请求历史；
 * 选择保存在客户端配置里。
 * <p>
 * 翻译键按常量名拼接：界面按钮 {@code screen.creepercapital.trading.chart_type.<小写名>}，
 * 配置界面 {@code creepercapital.configuration.chartType.<小写名>}。
 */
public enum ChartType implements TranslatableEnum {
    CANDLES,
    LINE;

    /** 线形图的线色（涨色 / 跌色，随客户端配置的涨跌色方案）。 */
    static int lineColor(List<Candle> candles, int first, int last) {
        return ChartTrend.risingOver(candles, first, last) ? TradingPalette.up() : TradingPalette.down();
    }

    /** 交易界面图表工具区里这个类型的按钮文字。 */
    Component label() {
        return Component.translatable("screen.creepercapital.trading.chart_type." + name().toLowerCase(Locale.ROOT));
    }

    ChartType next() {
        return this == CANDLES ? LINE : CANDLES;
    }

    /** 模组配置界面里这一项的显示名称。 */
    @Override
    public Component getTranslatedName() {
        return Component.translatable("creepercapital.configuration.chartType." + name().toLowerCase(Locale.ROOT));
    }
}
