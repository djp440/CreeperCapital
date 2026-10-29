package io.github.djp440.creepercapital.client;

import java.util.Locale;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * 涨跌色方案：绿涨红跌（默认）或红涨绿跌（A 股习惯）。只决定「涨 / 买 / 多 / 盈利」与「跌 / 卖 / 空 / 亏损」用哪种颜色，
 * 品牌黄、中性灰等其他配色不受影响。界面上所有带涨跌语义的颜色都经 {@link TradingPalette#up()} / {@link TradingPalette#down()} 取色。
 * <p>
 * 翻译键按常量名拼接：界面按钮 {@code screen.creepercapital.trading.color_scheme.<小写名>}，
 * 配置界面 {@code creepercapital.configuration.colorScheme.<小写名>}（语言文件检查按同样规则展开）。
 */
public enum ColorScheme implements TranslatableEnum {
    GREEN_UP(false),
    RED_UP(true);

    private final boolean redUp;

    ColorScheme(boolean redUp) {
        this.redUp = redUp;
    }

    int up() {
        return redUp ? TradingPalette.RED : TradingPalette.GREEN;
    }

    int down() {
        return redUp ? TradingPalette.GREEN : TradingPalette.RED;
    }

    ColorScheme toggled() {
        return this == GREEN_UP ? RED_UP : GREEN_UP;
    }

    /** 交易界面上切换按钮的文字（「绿涨红跌」/「红涨绿跌」）。 */
    Component label() {
        return Component.translatable("screen.creepercapital.trading.color_scheme." + name().toLowerCase(Locale.ROOT));
    }

    /** 模组配置界面里这一项的显示名称。 */
    @Override
    public Component getTranslatedName() {
        return Component.translatable("creepercapital.configuration.colorScheme." + name().toLowerCase(Locale.ROOT));
    }
}
