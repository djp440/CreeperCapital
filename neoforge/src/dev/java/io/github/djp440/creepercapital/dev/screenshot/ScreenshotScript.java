package io.github.djp440.creepercapital.dev.screenshot;

import java.util.List;

import io.github.djp440.creepercapital.dev.screenshot.ScreenshotStep.View;

/**
 * 自动截图验收脚本：每张界面类的单，往 {@link #STEPS} 里追加步骤（编号、说明、视图）即可。
 */
final class ScreenshotScript {
    static final List<ScreenshotStep> STEPS = List.of(
            new ScreenshotStep("01", "交易界面：整体",
                    "币安深色主题；顶部标题栏下方是行情栏，界面其余部分为深色背景，无错位和乱码。",
                    View.TRADING),
            new ScreenshotStep("02", "交易界面：行情栏五项数值",
                    "行情栏左侧为 DIAMONDCRP / 永续，其后依次为最新价格（随行情步变色，涨绿跌红）、标记价格、指数价格、"
                            + "资金费率 / 倒计时（黄色，形如「0.0100% / 07:59:54」，倒计时按市场时间每步少 6 秒）和市场时间；"
                            + "三种价格彼此接近但通常不完全相同；与 01 相比各数值已刷新；整行不越出屏幕、无重叠和乱码。",
                    View.TRADING));

    private ScreenshotScript() {
    }
}
