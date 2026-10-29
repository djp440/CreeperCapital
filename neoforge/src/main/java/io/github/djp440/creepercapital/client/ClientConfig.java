package io.github.djp440.creepercapital.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端配置（{@code config/creepercapital-client.toml}）：目前只有涨跌色方案。
 * 可以在模组列表的「配置」里改，也可以点交易界面行情栏右端的切换按钮；两处改的是同一个配置项，改完立即生效。
 */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.EnumValue<ColorScheme> COLOR_SCHEME;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        COLOR_SCHEME = builder
                .comment("Price change colors: GREEN_UP = green up / red down (default), RED_UP = red up / green down")
                .defineEnum("colorScheme", ColorScheme.GREEN_UP);
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
}
