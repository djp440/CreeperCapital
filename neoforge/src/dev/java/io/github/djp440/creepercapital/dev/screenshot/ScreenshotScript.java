package io.github.djp440.creepercapital.dev.screenshot;

import java.util.List;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.client.ClientKlines;
import io.github.djp440.creepercapital.client.ClientMarket;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.dev.screenshot.ScreenshotStep.View;
import net.minecraft.client.Minecraft;

/**
 * 自动截图验收脚本：每张界面类的单，往 {@link #STEPS} 里追加步骤（编号、说明、视图）即可。
 */
final class ScreenshotScript {
    static final List<ScreenshotStep> STEPS = List.of(
            new ScreenshotStep("01", "交易界面：整体",
                    "币安深色主题；顶部标题栏下方是行情栏，行情栏下是周期切换栏（1m 5m 15m 1h 4h 1d 1w，1m 为黄字加下划线），"
                            + "再往下是 K 线图，新世界自带 30 个市场日的预生成历史，所以 1m 图一进来就铺满蜡烛；界面无错位和乱码。",
                    View.TRADING),
            new ScreenshotStep("02", "交易界面：行情栏九项数值",
                    "行情栏左侧为 DIAMONDCRP（右边带下拉小三角）/ 永续，其后依次为最新价格（随行情步变色，涨绿跌红）、标记价格、指数价格、"
                            + "资金费率 / 倒计时（黄色，形如「0.0100% / 07:59:54」，倒计时按市场时间每步少 6 秒）、市场时间，"
                            + "以及 24h 涨跌（涨绿跌红，有预生成历史，所以是非零的涨跌额与涨跌幅）、24h 最高、24h 最低、24h 成交量；"
                            + "屏幕放不下时折成两行；三种价格彼此接近但通常不完全相同；与 01 相比各数值已刷新；"
                            + "整栏不越出屏幕、无重叠和乱码。",
                    View.TRADING),
            new ScreenshotStep("03", "K 线图：1m 周期（预生成历史之后）",
                    "1m 为当前周期（黄字加下划线）；图表区铺满红绿蜡烛（涨绿跌红，实体加影线），上方图例两行："
                            + "第一行是鼠标所在那根（鼠标在图外时是最新一根；截图时鼠标在窗口中央，图上会有十字线和价格标签）的市场时间与 O/H/L/C/成交量，第二行是 MA(7) 黄、MA(25) 粉、MA(99) 蓝三条均线的值；"
                            + "三条均线在蜡烛之上连续画出，MA(7) 最贴近价格；下方是与蜡烛一一对应的成交量柱（涨绿跌红、半透明），"
                            + "右侧价格轴有 5 条网格价位，最新价格用虚线和涨跌色标签标出，底部时间轴标有「第 N 日 HH:mm」；"
                            + "行情栏的 24h 涨跌、最高、最低与图表最新价格大致吻合（最高不低于、最低不高于最新价格）。",
                    View.TRADING),
            new ScreenshotStep("04", "K 线图：15m 周期",
                    "15m 为当前周期（黄字加下划线）；蜡烛比 1m 周期疏，时间轴标注间隔对应 4 个市场小时；"
                            + "均线与成交量柱按 15m 的 K 线重新计算；最新价格虚线与价格轴标签仍与行情栏最新价格一致。",
                    View.TRADING,
                    select(CandlePeriod.M15)),
            new ScreenshotStep("05", "K 线图：DIAMONDCRP 1h（预生成历史，3 级验收）",
                    "1h 为当前周期（黄字加下划线），图表是 DIAMONDCRP（价格轴在 1000 上下）；屏幕上可见几十根 1h 蜡烛（取决于窗口宽度），"
                            + "MA(7)、MA(25)、MA(99) 三条均线均可见；时间轴标注为「第 N 日 00:00 / 12:00」，日期在第 29~31 日附近（预生成 30 个市场日之后）；"
                            + "走势应像真实行情：有趋势段与震荡段，偶有急涨急跌的跳跃，没有锯齿状白噪声，也没有一条死直线；"
                            + "成交量柱高度明显大于 1m 周期（一根 1h 累计 600 个行情步的成交量）。",
                    View.TRADING,
                    select(CandlePeriod.H1)),
            new ScreenshotStep("06", "K 线图：DIAMONDCRP 1d（预生成历史，3 级验收）",
                    "1d 为当前周期（黄字加下划线），图表是 DIAMONDCRP；30 根预生成的日线（第 1 日到第 30 日），最右侧另有第 31 日一根极矮的当日线（开市后数十秒，成交量几乎为零），"
                            + "MA(7) 与 MA(25) 可见，不足 99 根所以没有 MA(99)；时间轴标注为「第 N 日」；"
                            + "30 天里价格有明显的起伏（不是一条横线），涨跌日大致均衡；成交量柱高度参差（波动大的日子更高），"
                            + "无错位和乱码。",
                    View.TRADING,
                    select(CandlePeriod.D1)),
            new ScreenshotStep("07", "合约下拉列表（DIAMONDCRP 选中，1h 周期）",
                    "行情栏最左侧的交易代码 DIAMONDCRP 右边有一个向下的小三角，且整块交易代码区域底色变亮（下拉已展开）；"
                            + "其下挂出一个带边框的列表，两项：DIAMONDCRP（黄字，表示当前合约）和 GUNPOWDERCRP（灰字）；"
                            + "列表盖在行情栏第二行最左边（遮住「24h 最高」）之上，文字清晰、无错位；"
                            + "图表是 DIAMONDCRP 的 1h K 线，价格轴在 1000 附近。",
                    View.CONTRACT_MENU,
                    select(CandlePeriod.H1)),
            new ScreenshotStep("08", "切换到 GUNPOWDERCRP（1h 周期）",
                    "行情栏左侧变为 GUNPOWDERCRP / 永续，三种价格、资金费率、24h 涨跌/最高/最低/成交量都是火药自己的数值"
                            + "（价格在 100 附近，明显不同于 DIAMONDCRP 的 1000 附近）；下拉列表已收起；"
                            + "K 线图是火药自己的 1h K 线，价格轴在 100 附近，波动明显比 DIAMONDCRP 大（图上 K 线的高低差更大）；"
                            + "无错位和乱码。",
                    View.TRADING,
                    selectContract("GUNPOWDERCRP")),
            new ScreenshotStep("09", "K 线图：GUNPOWDERCRP 1d（预生成历史，3 级验收）",
                    "1d 为当前周期，图表是 GUNPOWDERCRP（价格轴在 100 上下）；30 根预生成的日线加最右侧第 31 日一根极矮的当日线，MA(7) 与 MA(25) 可见；"
                            + "30 天里价格有明显的起伏，单日涨跌幅明显比 DIAMONDCRP 大（波动率倾向更高），"
                            + "偶有大阳线或大阴线（跳跃）；无错位和乱码。",
                    View.TRADING,
                    select(CandlePeriod.D1)));

    private ScreenshotScript() {
    }

    private static Consumer<Minecraft> select(CandlePeriod period) {
        return minecraft -> ClientKlines.select(period);
    }

    private static Consumer<Minecraft> selectContract(String symbol) {
        return minecraft -> ClientMarket.select(symbol);
    }
}
