package io.github.djp440.creepercapital.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端配置（{@code config/creepercapital-client.toml}）：涨跌色方案，以及交易界面拖拽出来的区域比例。
 * 涨跌色可以在模组列表的「配置」里改，也可以点交易界面行情栏右端的切换按钮；两处改的是同一个配置项，改完立即生效。
 * 区域比例由交易界面里拖拽边框写入，「恢复默认布局」把它们清零；配置里 0 表示用缺省尺寸。
 */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.EnumValue<ColorScheme> COLOR_SCHEME;
    private static final ModConfigSpec.DoubleValue LAYOUT_ORDER_PANEL;
    private static final ModConfigSpec.DoubleValue LAYOUT_COLUMN;
    private static final ModConfigSpec.DoubleValue LAYOUT_TABLE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        COLOR_SCHEME = builder
                .comment("Price change colors: GREEN_UP = green up / red down (default), RED_UP = red up / green down")
                .defineEnum("colorScheme", ColorScheme.GREEN_UP);
        LAYOUT_ORDER_PANEL = builder
                .comment("Trading screen: order panel width as a fraction of the screen width. 0 = default size. Set by dragging the borders.")
                .defineInRange("layoutOrderPanelRatio", 0.0, 0.0, 1.0);
        LAYOUT_COLUMN = builder
                .comment("Trading screen: order book / latest trades column width as a fraction of the screen width. 0 = default size.")
                .defineInRange("layoutMarketColumnRatio", 0.0, 0.0, 1.0);
        LAYOUT_TABLE = builder
                .comment("Trading screen: bottom table height as a fraction of the left area height. 0 = default size.")
                .defineInRange("layoutTableRatio", 0.0, 0.0, 1.0);
        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    /** 当前的涨跌色方案；配置还没加载时（例如启动早期）按缺省的绿涨红跌。 */
    public static ColorScheme colorScheme() {
        return SPEC.isLoaded() ? COLOR_SCHEME.get() : ColorScheme.GREEN_UP;
    }

    /** 改涨跌色方案并写回配置文件；下一帧起界面就按新方案取色。自动截图也用它切换配色。 */
    public static void setColorScheme(ColorScheme scheme) {
        if (!SPEC.isLoaded()) {
            return;
        }
        COLOR_SCHEME.set(scheme);
        SPEC.save();
    }

    /** 玩家拖拽出来的区域比例；配置还没加载时用缺省布局。 */
    static TradingLayout.Ratios layoutRatios() {
        if (!SPEC.isLoaded()) {
            return TradingLayout.Ratios.DEFAULT;
        }
        return new TradingLayout.Ratios(LAYOUT_ORDER_PANEL.get(), LAYOUT_COLUMN.get(), LAYOUT_TABLE.get());
    }

    /** 保存区域比例（松开鼠标时调用，不是拖拽的每一帧）；传 {@link TradingLayout.Ratios#DEFAULT} 即恢复默认布局。 */
    static void setLayoutRatios(TradingLayout.Ratios ratios) {
        if (!SPEC.isLoaded()) {
            return;
        }
        LAYOUT_ORDER_PANEL.set(clampRatio(ratios.orderPanel()));
        LAYOUT_COLUMN.set(clampRatio(ratios.column()));
        LAYOUT_TABLE.set(clampRatio(ratios.table()));
        SPEC.save();
    }

    /** 开发用（自动截图）：直接设置区域比例，等同于玩家拖拽后松手；全 0 即恢复默认布局。 */
    public static void setLayoutRatios(double orderPanel, double column, double table) {
        setLayoutRatios(new TradingLayout.Ratios(orderPanel, column, table));
    }

    private static double clampRatio(double ratio) {
        return Math.max(0.0, Math.min(1.0, ratio));
    }
}
