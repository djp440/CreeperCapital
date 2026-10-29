package io.github.djp440.creepercapital.dev.screenshot;

import java.util.List;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.client.ClientKlines;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.dev.screenshot.ScreenshotStep.View;
import net.minecraft.client.Minecraft;

/**
 * 自动截图验收脚本：每张界面类的单，往 {@link #STEPS} 里追加步骤（编号、说明、视图）即可。
 */
final class ScreenshotScript {
    /** 快进 5 个市场日（72000 个行情步），让 1m 到 1d 各周期都有足够的 K 线，MA(99) 也能画出来。 */
    private static final int FAST_FORWARD_STEPS = 72_000;

    static final List<ScreenshotStep> STEPS = List.of(
            new ScreenshotStep("01", "交易界面：整体",
                    "币安深色主题；顶部标题栏下方是行情栏，行情栏下是周期切换栏（1m 5m 15m 1h 4h 1d 1w，1m 为黄字加下划线），"
                            + "再往下是 K 线图，刚进世界时只有寥寥几根 1m K 线；界面无错位和乱码。",
                    View.TRADING),
            new ScreenshotStep("02", "交易界面：行情栏九项数值",
                    "行情栏左侧为 DIAMONDCRP / 永续，其后依次为最新价格（随行情步变色，涨绿跌红）、标记价格、指数价格、"
                            + "资金费率 / 倒计时（黄色，形如「0.0100% / 07:59:54」，倒计时按市场时间每步少 6 秒）、市场时间，"
                            + "以及 24h 涨跌（涨绿跌红，形如「+0.00 +0.00%」）、24h 最高、24h 最低、24h 成交量；"
                            + "屏幕放不下时折成两行；三种价格彼此接近但通常不完全相同；与 01 相比各数值已刷新；"
                            + "整栏不越出屏幕、无重叠和乱码。",
                    View.TRADING),
            new ScreenshotStep("03", "K 线图：1m 周期（行情快进 5 个市场日后）",
                    "1m 为当前周期（黄字加下划线）；图表区铺满红绿蜡烛（涨绿跌红，实体加影线），上方图例两行："
                            + "第一行是鼠标所在那根（鼠标在图外时是最新一根；截图时鼠标在窗口中央，图上会有十字线和价格标签）的市场时间与 O/H/L/C/成交量，第二行是 MA(7) 黄、MA(25) 粉、MA(99) 蓝三条均线的值；"
                            + "三条均线在蜡烛之上连续画出，MA(7) 最贴近价格；下方是与蜡烛一一对应的成交量柱（涨绿跌红、半透明），"
                            + "右侧价格轴有 5 条网格价位，最新价格用虚线和涨跌色标签标出，底部时间轴标有「第 N 日 HH:mm」；"
                            + "行情栏的 24h 涨跌、最高、最低与图表最新价格大致吻合（最高不低于、最低不高于最新价格）。",
                    View.TRADING,
                    fastForward(FAST_FORWARD_STEPS)),
            new ScreenshotStep("04", "K 线图：15m 周期",
                    "15m 为当前周期（黄字加下划线）；蜡烛比 1m 周期疏，时间轴标注间隔对应 4 个市场小时；"
                            + "均线与成交量柱按 15m 的 K 线重新计算；最新价格虚线与价格轴标签仍与行情栏最新价格一致。",
                    View.TRADING,
                    select(CandlePeriod.M15)),
            new ScreenshotStep("05", "K 线图：1h 周期",
                    "1h 为当前周期（黄字加下划线）；屏幕上可见几十根 1h 蜡烛（取决于窗口宽度），MA(7)、MA(25)、MA(99) 三条均线均可见（共 120 余根，MA(99) 只覆盖最近一段）；"
                            + "时间轴标注为「第 N 日 00:00 / 12:00」；成交量柱高度明显大于 1m 周期（一根 1h 累计 600 个行情步的成交量）。",
                    View.TRADING,
                    select(CandlePeriod.H1)),
            new ScreenshotStep("06", "K 线图：1d 周期",
                    "1d 为当前周期（黄字加下划线）；只有 6 根左右的日线蜡烛靠右排列（第 1 日到第 6 日，最右一根是当天未收盘的），"
                            + "不足 7 根所以没有均线；时间轴标注为「第 N 日」；"
                            + "成交量柱中前 5 根高度相近（各是一整天的成交量），最右一根很矮（当天才刚开始）；无错位和乱码。",
                    View.TRADING,
                    select(CandlePeriod.D1)));

    private ScreenshotScript() {
    }

    /** 以玩家身份执行开发命令，把行情快进若干步，K 线随之生成。 */
    private static Consumer<Minecraft> fastForward(int steps) {
        return minecraft -> minecraft.player.connection.sendCommand("creeper dev market advance " + steps);
    }

    private static Consumer<Minecraft> select(CandlePeriod period) {
        return minecraft -> ClientKlines.select(period);
    }
}
