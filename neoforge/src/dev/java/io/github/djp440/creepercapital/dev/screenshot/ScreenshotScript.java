package io.github.djp440.creepercapital.dev.screenshot;

import java.util.List;

import io.github.djp440.creepercapital.dev.screenshot.ScreenshotStep.View;

/**
 * 自动截图验收脚本：每张界面类的单，往 {@link #STEPS} 里追加步骤（编号、说明、视图）即可。
 */
final class ScreenshotScript {
    static final List<ScreenshotStep> STEPS = List.of(
            new ScreenshotStep("01", "交易界面：顶部行情栏",
                    "币安深色主题；顶部行情栏左侧为 DIAMONDCRP / 永续，右侧为指数价格，价格随行情步变色（涨绿跌红），无错位和乱码。",
                    View.TRADING));

    private ScreenshotScript() {
    }
}
