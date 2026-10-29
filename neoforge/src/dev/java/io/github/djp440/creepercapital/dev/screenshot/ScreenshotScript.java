package io.github.djp440.creepercapital.dev.screenshot;

import java.util.List;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.client.ClientKlines;
import io.github.djp440.creepercapital.client.ClientMarket;
import io.github.djp440.creepercapital.client.ClientTrading;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.dev.screenshot.ScreenshotStep.View;
import io.github.djp440.creepercapital.network.OrderPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

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
                    select(CandlePeriod.D1)),
            new ScreenshotStep("10", "资产页（dev 充值后，3 级验收）",
                    "界面最底部是表格区：左上一个黄字加下划线的「资产」页签，下方一行四项数据（名称在上、数值在下）："
                            + "钱包余额 12,345.678901 CRP、未实现盈亏 0.00 CRP、保证金余额 12,345.678901 CRP、可用余额 12,345.678901 CRP"
                            + "（还没有仓位，所以后三项中除未实现盈亏外都等于钱包余额）；刚执行过 /creeper dev deposit @p 12345.678901，"
                            + "余额已立即刷新而不是显示 0.00；K 线图被压缩到表格区之上，图表底部不与表格区重叠；无错位和乱码。",
                    View.TRADING,
                    deposit("12345.678901")),
            new ScreenshotStep("11", "下单面板（GUNPOWDERCRP，3 级验收）",
                    "图表右侧是宽约 168 的下单面板：顶部「市价」「限价」页签（市价为黄字加下划线）和「逐仓」标签；杠杆一行有 − / + 按钮，当前 20x；"
                            + "数量一栏单位为 GUNPOWDERCRP（数量精度 0 位，整数个），输入框里已填 2；"
                            + "下方预估：可用余额 12,345.678901 CRP、预估成交价（约等于最新价 + 半个点差，开多按买价）、"
                            + "预估保证金 = 名义价值 / 20、预估手续费 = 名义价值 × 0.05%；"
                            + "底部是绿色「开多」、红色「开空」两个按钮和一个「平仓」按钮（没有仓位时不会成交）；"
                            + "面板不越出屏幕，图表区被压窄但不与面板重叠，无错位和乱码。",
                    View.ORDER_PANEL,
                    leverage("GUNPOWDERCRP", 20)),
            new ScreenshotStep("12", "仓位页（3 级验收）",
                    "表格区的「仓位」页签为黄字加下划线；表头：合约、数量、开仓均价、标记价格、预估强平价、保证金率、保证金、未实现盈亏（收益率）、操作；"
                            + "两行仓位：GUNPOWDERCRP 逐仓 20x 多 50 个（绿字）和 DIAMONDCRP 逐仓 10x 空 1.500 个（红字）；"
                            + "开仓均价与标记价格相差约半个点差，未实现盈亏随标记价格变化且带正负号与收益率百分比（盈绿亏红）；"
                            + "预估强平价（黄字）多头低于、空头高于标记价格，保证金率是个很小的百分比（刚开仓时远离 100%）；"
                            + "每行末尾有「市价平仓」按钮；列不重叠、无乱码。",
                    View.POSITIONS,
                    openPositions()),
            new ScreenshotStep("13", "成交历史页（3 级验收）",
                    "表格区的「成交历史」页签为黄字加下划线；表头：时间、合约、方向、成交价、数量、手续费、已实现盈亏；"
                            + "最新的在最上面：先是 GUNPOWDERCRP 平多（平仓，已实现盈亏非 \"--\"，带正负号和涨跌色），"
                            + "下面是开多 GUNPOWDERCRP、开空 DIAMONDCRP 的开仓记录（已实现盈亏显示 \"--\"）；"
                            + "每笔手续费 = 名义价值 × 0.05%，开多成交价高于最新价、开空成交价低于最新价；列不重叠、无乱码。",
                    View.TRADES,
                    closeGunpowder()),
            new ScreenshotStep("14", "盘口：DIAMONDCRP（3 级验收）",
                    "图表与下单面板之间是宽约 144 的右侧栏，顶部页签条只有黄字加下划线的「盘口」；表头「价格(CRP) 数量 累计」；"
                            + "上半部分是卖盘（红字）：离最新价格最近的卖一在最下面，往上价格逐档升高；下半部分是买盘（绿字）：买一在最上面，往下价格逐档降低；"
                            + "中间一行是最新价格（涨绿跌红或持平白字），卖一 = 最新价 + 0.25（半个点差），买一 = 最新价 − 0.25；"
                            + "每档数量相同（约 500 CRP 名义价值折成的数量，钻石价格 1000 上下时约 0.46~0.5 个），累计一列逐档递增，背景有按累计数量画的半透明深度条（卖盘红、买盘绿）；"
                            + "价格逐档拉开的幅度很小（钻石 10,000 CRP 的滑点只有约 0.05%，相邻档价差约 0.05）；"
                            + "栏内文字不重叠、不越出栏边框，图表被压窄但不与右侧栏重叠。",
                    View.TRADING,
                    selectContract("DIAMONDCRP")),
            new ScreenshotStep("15", "盘口：GUNPOWDERCRP（3 级验收）",
                    "同 14，但是火药自己的盘口：价格在 100 上下，卖一 = 最新价 + 0.025，买一 = 最新价 − 0.025；"
                            + "每档数量相同（约 500 CRP 折成的整数个，数量精度 0 位，价格 100 上下时约 4~5 个），累计逐档递增；"
                            + "火药滑点更大（10,000 CRP 约 0.3%），所以相邻档价差（约 0.03）明显大于钻石盘口里的，"
                            + "盘口价格范围比钻石更宽；与 14 相比切换合约后盘口立即换成火药的深度，无错位和乱码。",
                    View.TRADING,
                    selectContract("GUNPOWDERCRP")),
            new ScreenshotStep("16", "限价下单（GUNPOWDERCRP，3 级验收）",
                    "下单面板顶部的「限价」页签为黄字加下划线（「市价」为灰字）；杠杆 20x；杠杆行下面多了一个委托价输入框"
                            + "（右侧单位 CRP），里面已填一个比最新价低约 3% 的价格；其下数量框已填 2（单位 GUNPOWDER）；"
                            + "预估区只有可用余额、预估保证金（按委托价 × 数量 / 20）、预估手续费（按 maker 0.02%）三行，"
                            + "没有「预估成交价」；底部仍是绿色「开多」、红色「开空」和「平仓」按钮；"
                            + "面板内容不越出面板底部，与图表不重叠，无错位和乱码。",
                    View.LIMIT_ORDER,
                    leverage("GUNPOWDERCRP", 20)),
            new ScreenshotStep("17", "当前委托页（3 级验收）",
                    "表格区的「当前委托」页签为黄字加下划线；表头：时间、合约、方向、类型、委托价、数量、冻结、操作；"
                            + "两行挂单（最新的在上）：DIAMONDCRP 开空（红字）限价，委托价比最新价高约 10%，数量 1.000，冻结约为 名义价值 / 10 + 0.02% 手续费；"
                            + "GUNPOWDERCRP 开多（绿字）限价，委托价比最新价低约 10%，数量 10；每行末尾有「撤单」按钮；"
                            + "资产页可用余额应已减去冻结的资金（下单面板里的可用余额变小）；列不重叠、无乱码。",
                    View.OPEN_ORDERS,
                    placeOpenOrders()),
            new ScreenshotStep("18", "历史委托页（3 级验收）",
                    "表格区的「历史委托」页签为黄字加下划线；表头：时间、合约、方向、类型、委托价、成交价、数量、手续费、状态；"
                            + "两行记录（最新的在上）：GUNPOWDERCRP 开多限价 5 个，委托价高于最新价所以下单即成交（状态「已成交」绿字，"
                            + "成交价约为最新价 + 半个点差，低于委托价，手续费按 taker 0.05%）；"
                            + "GUNPOWDERCRP 开多限价 10 个，刚被撤单（状态「已撤销」灰字，成交价与手续费显示 \"--\"）；"
                            + "DIAMONDCRP 的开空挂单仍在「当前委托」页，不出现在这里；列不重叠、无乱码。",
                    View.ORDER_HISTORY,
                    fillAndCancelOrders()),
            new ScreenshotStep("19", "全仓仓位（一组全仓、一组逐仓，3 级验收）",
                    "表格区在「仓位」页；刚把 GUNPOWDERCRP 的旧仓位平掉、切为全仓，并以 10x 开多 300 个（名义价值约 3.8 万 CRP），此时 DIAMONDCRP 10x 空 1.500 个仍是逐仓："
                            + "两行合约列分别写着「GUNPOWDERCRP 全仓 10x」（绿字）和「DIAMONDCRP 逐仓 10x」（红字）；"
                            + "每行有预估强平价（黄字）和保证金率（百分比；逐仓行只算该仓位自己的保证金，约 4%；全仓行是维持保证金除以全仓钱包，约 5%）；"
                            + "逐仓空头的强平价高于标记价格约 9.5%，全仓多头的强平价约 87、远低于标记价格 126（全仓用整个余额扛亏损，所以远得多）；"
                            + "右侧下单面板（GUNPOWDERCRP，市价页签）顶部右侧的模式按钮显示「全仓」；列不重叠、无乱码。",
                    View.POSITIONS,
                    openCrossGunpowder()),
            new ScreenshotStep("20", "杠杆调整（有仓位时调低杠杆，3 级验收）",
                    "刚把 GUNPOWDERCRP 的全仓多头从 10x 调到 5x（点了杠杆 − 一次）：下单面板的杠杆显示 5x，且 − / + 按钮没有被锁定；"
                            + "下单面板结果行是「GUNPOWDERCRP 杠杆已调整为 5x」；仓位页里这一行合约列变为「GUNPOWDERCRP 全仓 5x」，"
                            + "保证金约为调整前的两倍（名义价值 / 5，约 7,575），可用余额相应减少约 3,800；"
                            + "全仓的强平价和保证金率几乎不变（全仓钱包没有变，调杠杆只是把可用余额挪进保证金）；"
                            + "DIAMONDCRP 逐仓那一行不变；列不重叠、无乱码。",
                    View.POSITIONS,
                    lowerGunpowderLeverage()));

    private ScreenshotScript() {
    }

    private static Consumer<Minecraft> select(CandlePeriod period) {
        return minecraft -> ClientKlines.select(period);
    }

    /** 以玩家自己的身份执行 dev 充值命令（截图世界里玩家是 OP），验证调账后资产页立即刷新。 */
    private static Consumer<Minecraft> deposit(String amount) {
        return minecraft -> minecraft.player.connection.sendCommand("creeper dev deposit @p " + amount);
    }

    private static Consumer<Minecraft> leverage(String symbol, int leverage) {
        return minecraft -> {
            ClientTrading.selectLeverage(symbol, leverage);
            ClientTrading.clearResult();
        };
    }

    /** 开两个仓位：GUNPOWDERCRP 20x 多 50 个，DIAMONDCRP 10x 空 1.500 个（1500 手）。 */
    private static Consumer<Minecraft> openPositions() {
        return minecraft -> {
            ClientTrading.selectLeverage("GUNPOWDERCRP", 20);
            ClientTrading.send(OrderPayload.Action.OPEN_LONG, "GUNPOWDERCRP", 50);
            ClientTrading.selectLeverage("DIAMONDCRP", 10);
            ClientTrading.send(OrderPayload.Action.OPEN_SHORT, "DIAMONDCRP", 1500);
        };
    }

    /** 全平 GUNPOWDERCRP 的仓位，让成交历史里有一笔带已实现盈亏的平仓。 */
    private static Consumer<Minecraft> closeGunpowder() {
        return minecraft -> ClientTrading.position("GUNPOWDERCRP")
                .ifPresent(position -> ClientTrading.send(OrderPayload.Action.CLOSE, "GUNPOWDERCRP", position.lots()));
    }

    /**
     * 把 GUNPOWDERCRP 设为全仓并以 10x 开多 300 个（名义价值约 3.8 万 CRP，全仓强平价才落在价格之下而不是负数）。
     * 前面的步骤在 GUNPOWDERCRP 上留下了限价成交的仓位，有仓位不能切模式，所以先全平；
     * 几个包按发送顺序在服务端处理，所以是平仓、切模式、开仓的顺序。
     */
    private static Consumer<Minecraft> openCrossGunpowder() {
        return minecraft -> {
            ClientTrading.setLimitMode(false);
            ClientTrading.position("GUNPOWDERCRP")
                    .ifPresent(position -> ClientTrading.send(OrderPayload.Action.CLOSE, "GUNPOWDERCRP", position.lots()));
            // 旧仓位在客户端还没刷新，ClientTrading.leverage() 会读到它的 20x，所以这里直接按 10x 组包
            ClientPacketDistributor.sendToServer(new OrderPayload(OrderPayload.Action.SET_CROSS, "GUNPOWDERCRP", 0, 10));
            ClientPacketDistributor.sendToServer(new OrderPayload(OrderPayload.Action.OPEN_LONG, "GUNPOWDERCRP", 300, 10));
            ClientTrading.selectLeverage("GUNPOWDERCRP", 10);
        };
    }

    /** 有仓位时点一次杠杆 −：10x 调到 5x。 */
    private static Consumer<Minecraft> lowerGunpowderLeverage() {
        return minecraft -> ClientTrading.lowerLeverage("GUNPOWDERCRP");
    }

    /** 挂两张限价单：DIAMONDCRP 开空（高于市价约 10%）1.000 个，GUNPOWDERCRP 开多（低于市价约 10%）10 个。 */
    private static Consumer<Minecraft> placeOpenOrders() {
        return minecraft -> {
            ClientTrading.selectLeverage("GUNPOWDERCRP", 20);
            ClientTrading.selectLeverage("DIAMONDCRP", 10);
            ClientMarket.snapshot("DIAMONDCRP").ifPresent(snapshot -> ClientTrading.sendLimit(
                    OrderPayload.Action.OPEN_SHORT, "DIAMONDCRP", 1000, snapshot.lastPrice() * 1.1));
            ClientMarket.snapshot("GUNPOWDERCRP").ifPresent(snapshot -> ClientTrading.sendLimit(
                    OrderPayload.Action.OPEN_LONG, "GUNPOWDERCRP", 10, snapshot.lastPrice() * 0.9));
        };
    }

    /** 撤掉 GUNPOWDERCRP 那张挂单，再下一张高于市价的限价买单让它立即成交，历史委托里就有已撤销和已成交各一条。 */
    private static Consumer<Minecraft> fillAndCancelOrders() {
        return minecraft -> {
            ClientTrading.openOrdersNewestFirst().stream().filter(order -> order.symbol().equals("GUNPOWDERCRP"))
                    .findFirst().ifPresent(order -> ClientTrading.cancel(order.id()));
            ClientMarket.snapshot("GUNPOWDERCRP").ifPresent(snapshot -> ClientTrading.sendLimit(
                    OrderPayload.Action.OPEN_LONG, "GUNPOWDERCRP", 5, snapshot.lastPrice() * 1.5));
        };
    }

    private static Consumer<Minecraft> selectContract(String symbol) {
        return minecraft -> ClientMarket.select(symbol);
    }
}
