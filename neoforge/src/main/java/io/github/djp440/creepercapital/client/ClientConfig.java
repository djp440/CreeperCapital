package io.github.djp440.creepercapital.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端配置（{@code config/creepercapital-client.toml}）：涨跌色方案、图表类型、图表指标设置、交易音效（总开关与音量），以及交易界面拖拽出来的区域比例。
 * 涨跌色可以在模组列表的「配置」里改，也可以点交易界面行情栏右端的切换按钮；两处改的是同一个配置项，改完立即生效。
 * 区域比例由交易界面里拖拽边框写入，「恢复默认布局」把它们清零；配置里 0 表示用缺省尺寸。
 */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.EnumValue<ColorScheme> COLOR_SCHEME;
    private static final ModConfigSpec.EnumValue<ChartType> CHART_TYPE;
    private static final ModConfigSpec.ConfigValue<String> INDICATORS;
    private static final ModConfigSpec.BooleanValue SOUND_ENABLED;
    private static final ModConfigSpec.DoubleValue SOUND_VOLUME;
    private static final ModConfigSpec.DoubleValue LAYOUT_ORDER_PANEL;
    private static final ModConfigSpec.DoubleValue LAYOUT_COLUMN;
    private static final ModConfigSpec.DoubleValue LAYOUT_TABLE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        COLOR_SCHEME = builder
                .comment("Price change colors: GREEN_UP = green up / red down (default), RED_UP = red up / green down")
                .defineEnum("colorScheme", ColorScheme.GREEN_UP);
        CHART_TYPE = builder
                .comment("Chart type: CANDLES = candlesticks (default), LINE = close-price line with area fill")
                .defineEnum("chartType", ChartType.CANDLES);
        INDICATORS = builder
                .comment("Chart indicators, edited via the gear next to the chart legend: ma=<period>:<color index>:<visible 0|1>,... (up to 5 lines, period 1-500);volume=<0|1>")
                .define("indicators", IndicatorSettings.defaults().encode());
        SOUND_ENABLED = builder
                .comment("Trading sound effects (vanilla sounds): master switch. true = on (default)")
                .define("soundEnabled", true);
        SOUND_VOLUME = builder
                .comment("Trading sound effects: volume, 0 = silent, 1 = full (default). Scales on top of Minecraft's own volume sliders.")
                .defineInRange("soundVolume", 1.0, 0.0, 1.0);
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

    /** 当前的图表类型；配置还没加载时按缺省的 K 线。 */
    public static ChartType chartType() {
        return SPEC.isLoaded() ? CHART_TYPE.get() : ChartType.CANDLES;
    }

    /** 改图表类型并写回配置文件；下一帧起图表就按新类型画，已缓存的 K 线直接复用，不重新请求。自动截图也用它切换类型。 */
    public static void setChartType(ChartType type) {
        if (!SPEC.isLoaded()) {
            return;
        }
        CHART_TYPE.set(type);
        SPEC.save();
    }

    private static String indicatorsRaw;
    private static IndicatorSettings indicatorsDecoded = IndicatorSettings.defaults();

    /** 当前的指标设置（均线周期 / 颜色 / 显示状态、成交量副图显示）；配置还没加载时用缺省。每帧都会取，所以按配置字符串缓存解码结果。 */
    static IndicatorSettings indicators() {
        if (!SPEC.isLoaded()) {
            return IndicatorSettings.defaults();
        }
        String raw = INDICATORS.get();
        if (!raw.equals(indicatorsRaw)) {
            indicatorsDecoded = IndicatorSettings.decode(raw);
            indicatorsRaw = raw;
        }
        return indicatorsDecoded;
    }

    /** 保存指标设置并写回配置文件；图表下一帧起按新设置重算。 */
    static void setIndicators(IndicatorSettings settings) {
        if (!SPEC.isLoaded()) {
            return;
        }
        INDICATORS.set(settings.encode());
        SPEC.save();
    }

    /** 开发用（自动截图）：用配置字符串（{@link IndicatorSettings#encode()} 的格式）设置指标，等同于玩家在浮层里改完。 */
    public static void setIndicatorsRaw(String encoded) {
        setIndicators(IndicatorSettings.decode(encoded));
    }

    /** 交易音效总开关；配置还没加载时按缺省的开启。 */
    public static boolean soundEnabled() {
        return !SPEC.isLoaded() || SOUND_ENABLED.get();
    }

    /** 交易音效音量（0 到 1）；配置还没加载时按缺省的 1。 */
    public static double soundVolume() {
        return SPEC.isLoaded() ? SOUND_VOLUME.get() : 1.0;
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
