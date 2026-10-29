package io.github.djp440.creepercapital.dev.screenshot;

import java.util.List;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.client.ClientKlines;
import io.github.djp440.creepercapital.client.ClientMarket;
import io.github.djp440.creepercapital.client.ClientTrading;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.dev.screenshot.ScreenshotStep.View;
import io.github.djp440.creepercapital.core.trading.OrderType;
import io.github.djp440.creepercapital.core.trading.TriggerSource;
import io.github.djp440.creepercapital.network.OrderPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;

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
            new ScreenshotStep("19", "最新成交：GUNPOWDERCRP（含玩家成交，3 级验收）",
                    "右侧栏顶部页签条是「盘口」「最新成交」，「最新成交」为黄字加下划线；表头「价格(CRP) 数量 时间」；"
                            + "列表最新的在最上面，每行：价格（买入绿、卖出红，落在最新价格 ± 半个点差 0.025 之内）、数量（整数个，火药精度 0 位）、"
                            + "市场时间（HH:mm:ss，每个行情步 6 秒，同一步内的几笔时间相同）；大量灰白数量的普通行是模拟成交，一个行情步拆成 2 到 8 笔；"
                            + "其中有两笔玩家成交（刚下的：开多 30 个、平仓 10 个，自动截图的玩家）整行带黄色半透明底色和左侧黄色色条，"
                            + "并多占一行用黄字标出「玩家 <玩家名>」，开多那笔价格为绿（买入）、平仓那笔价格为红（卖出）；"
                            + "文字不重叠、不越出栏边框，列表放不下的旧成交被截掉，不盖住栏底边框。",
                    View.LATEST_TRADES,
                    placePlayerTrades()),
            new ScreenshotStep("20", "全仓仓位（一组全仓、一组逐仓，3 级验收）",
                    "表格区在「仓位」页；刚把 GUNPOWDERCRP 的旧仓位平掉、切为全仓，并以 10x 开多 300 个（名义价值约 3.8 万 CRP），此时 DIAMONDCRP 10x 空 1.500 个仍是逐仓："
                            + "两行合约列分别写着「GUNPOWDERCRP 全仓 10x」（绿字）和「DIAMONDCRP 逐仓 10x」（红字）；"
                            + "每行有预估强平价（黄字）和保证金率（百分比；逐仓行只算该仓位自己的保证金，约 4%；全仓行是维持保证金除以全仓钱包，约 5%）；"
                            + "逐仓空头的强平价高于标记价格约 9.5%，全仓多头的强平价约 87、远低于标记价格 126（全仓用整个余额扛亏损，所以远得多）；"
                            + "右侧下单面板（GUNPOWDERCRP，市价页签）顶部右侧的模式按钮显示「全仓」；列不重叠、无乱码。",
                    View.POSITIONS,
                    openCrossGunpowder()),
            new ScreenshotStep("21", "杠杆调整（有仓位时调低杠杆，3 级验收）",
                    "刚把 GUNPOWDERCRP 的全仓多头从 10x 调到 5x（点了杠杆 − 一次）：下单面板的杠杆显示 5x，且 − / + 按钮没有被锁定；"
                            + "下单面板结果行是「GUNPOWDERCRP 杠杆已调整为 5x」；仓位页里这一行合约列变为「GUNPOWDERCRP 全仓 5x」，"
                            + "保证金约为调整前的两倍（名义价值 / 5，约 7,575），可用余额相应减少约 3,800；"
                            + "全仓的强平价和保证金率几乎不变（全仓钱包没有变，调杠杆只是把可用余额挪进保证金）；"
                            + "DIAMONDCRP 逐仓那一行不变；列不重叠、无乱码。",
                    View.POSITIONS,
                    lowerGunpowderLeverage()),
            new ScreenshotStep("22", "资金费记录页（快进经过两个结算点，3 级验收）",
                    "表格区的「资金费记录」页签为黄字加下划线；表头：时间、合约、方向、资金费率、标记价格、名义价值、资金费(CRP)、未收付；"
                            + "刚用 /creeper dev market funding 2 快进了两个结算点（行情栏倒计时走到 00:00:00 的那一步各结算一次），"
                            + "所以有四行记录（最新的在上，每个结算点两行：GUNPOWDERCRP 全仓多头和 DIAMONDCRP 逐仓空头），"
                            + "两个结算点的市场时间相差 8 小时（形如「第 N 日 08:00:00」「第 N 日 16:00:00」，时间栏是 HH:mm:ss 格式的市场时间）；"
                            + "金额收付由费率符号与方向决定：费率为正时多头付（红字，带负号）、空头收（绿字，带正号），费率为负时相反（两行可能同为付出，取决于两个合约各自的费率符号），"
                            + "金额 = 名义价值 × 资金费率（例如名义价值 40,000 CRP、费率 0.2700% 时约 108 CRP）；下单面板结果行是「资金费已支付 / 已收到 … CRP」；"
                            + "方向列多绿空红；未收付列都是灰色的 \"--\"（仓位保证金 / 余额足够）；列不重叠、无乱码。",
                    View.FUNDING,
                    settleFundingTwice()),
            new ScreenshotStep("23", "止盈止损设置（GUNPOWDERCRP，3 级验收）",
                    "下单面板里，数量框下面的小页签条是「预估 | 止盈止损」，「止盈止损」为黄字加下划线（输入框里已填价格，所以页签文字后面带黄色星号）；页内左右并排两个输入框：止盈价（约最新价 105%，单位 CRP）、止损价（约最新价 97%）；"
                            + "其下是触发价来源开关「触发：标记价格」和「只减仓」复选框（未勾选）；"
                            + "页签条右侧是缩短的仓位提示「多 300 · 5x」（绿字，前面步骤已把 GUNPOWDERCRP 全仓多头调成 5x）；下单面板在市价模式（顶部右侧是「全仓」按钮），杠杆 5x 与仓位一致；"
                            + "底部由于 GUNPOWDERCRP 已有多仓，按钮行是绿色「开多」、红色「开空」，其下一行分成「平仓」与「设止盈止损」两个按钮（完整显示，不被截断）；"
                            + "整个面板不越出面板底部，不与图表、盘口重叠，无错位和乱码。",
                    View.PROTECTION,
                    markTrigger()),
            new ScreenshotStep("24", "当前委托页：止盈止损单与限价平仓（3 级验收）",
                    "「当前委托」页签为黄字加下划线；最新的五行（更早的 DIAMONDCRP 开空限价单被表格高度截在第六行之外）依次是 GUNPOWDERCRP 的止盈、止损单（类型列分别是「止盈·标记价」「止损·标记价」，"
                            + "方向列是「平多」，委托/触发价列是触发价，约为最新价的 110% 与 90%，数量 300），DIAMONDCRP 的止盈、止损单（类型「止盈·最新价」「止损·最新价」，"
                            + "方向「平空」，数量 1.500），以及一张 GUNPOWDERCRP「限价只减仓」平多单（委托价约最新价 120%，数量 300）；平仓类委托没有冻结资金，冻结列显示 \"--\"；"
                            + "每行有「撤单」按钮；列不重叠、无乱码。",
                    View.OPEN_ORDERS,
                    placeProtectionOrders()),
            new ScreenshotStep("25", "历史委托页：撤销的止损单（3 级验收）",
                    "「历史委托」页签为黄字加下划线；最新一条是 DIAMONDCRP 的「止损·最新价」单，方向「平空」，状态「已撤销」灰字，成交价与手续费显示 \"--\"；"
                            + "委托/触发价列是触发价而不是成交价；列不重叠、无乱码。",
                    View.ORDER_HISTORY,
                    cancelDiamondStopLoss()));

    private ScreenshotScript() {
    }

    /** 快进经过两个资金费结算点（服务端精确停在结算点那一步，触发结算）；仓位沿用前面步骤留下的全仓多头与逐仓空头。 */
    private static Consumer<Minecraft> settleFundingTwice() {
        return minecraft -> minecraft.player.connection.sendCommand("creeper dev market funding 2");
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
     * 前面的步骤（限价成交、最新成交的开多 / 部分平仓）在 GUNPOWDERCRP 上留下了仓位，有仓位不能切模式，所以先全平；
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

    /** 触发价来源开关切到标记价格，让截图里能看到开关的文字。 */
    private static Consumer<Minecraft> markTrigger() {
        return minecraft -> ClientTrading.setTriggerSource(TriggerSource.MARK);
    }

    /**
     * 给两个仓位设止盈止损：GUNPOWDERCRP 多仓（标记价格触发，约 110% / 90%），DIAMONDCRP 空仓（最新价格触发，约 90% / 110%），
     * 再给 GUNPOWDERCRP 挂一张高于市价 20% 的限价只减仓平多单。
     */
    private static Consumer<Minecraft> placeProtectionOrders() {
        return minecraft -> {
            ClientMarket.snapshot("GUNPOWDERCRP").ifPresent(snapshot -> {
                ClientTrading.setTriggerSource(TriggerSource.MARK);
                ClientTrading.position("GUNPOWDERCRP").ifPresent(position -> {
                    ClientTrading.sendOrder(OrderPayload.Action.PROTECT, "GUNPOWDERCRP", position.lots(),
                            OrderPayload.Execution.MARKET, 0, snapshot.lastPrice() * 1.1, snapshot.lastPrice() * 0.9);
                    ClientTrading.sendOrder(OrderPayload.Action.CLOSE, "GUNPOWDERCRP", position.lots(),
                            OrderPayload.Execution.LIMIT, snapshot.lastPrice() * 1.2, 0, 0);
                });
            });
            ClientMarket.snapshot("DIAMONDCRP").ifPresent(snapshot -> {
                ClientTrading.setTriggerSource(TriggerSource.LAST);
                ClientTrading.position("DIAMONDCRP").ifPresent(position -> ClientTrading.sendOrder(
                        OrderPayload.Action.PROTECT, "DIAMONDCRP", position.lots(), OrderPayload.Execution.MARKET, 0,
                        snapshot.lastPrice() * 0.9, snapshot.lastPrice() * 1.1));
            });
        };
    }

    /** 撤掉 DIAMONDCRP 的止损单，历史委托里就有一条已撤销的止损。 */
    private static Consumer<Minecraft> cancelDiamondStopLoss() {
        return minecraft -> ClientTrading.openOrdersNewestFirst().stream()
                .filter(order -> order.symbol().equals("DIAMONDCRP") && order.type() == OrderType.STOP_LOSS)
                .findFirst().ifPresent(order -> ClientTrading.cancel(order.id()));
    }

    /** 下单推迟到打开视图后第几个 tick：截图在第 40 个 tick，留 4 个 tick 让成交到达，同时避免模拟成交很快把玩家成交挤出可视范围。 */
    private static final int PLAYER_TRADES_DELAY_TICKS = 36;
    private static int playerTradesCountdown = -1;
    private static boolean countdownListening;

    /**
     * 选中 GUNPOWDERCRP，视图打开之后再开多 30 个、平掉 10 个，让「最新成交」顶部有两笔刚到的高亮玩家成交。
     * 模拟成交每个行情步（1 秒）来 2 到 8 笔，下单太早的话玩家成交会被挤到可视范围之外。
     */
    private static Consumer<Minecraft> placePlayerTrades() {
        return minecraft -> {
            ClientMarket.select("GUNPOWDERCRP");
            ClientTrading.selectLeverage("GUNPOWDERCRP", 20);
            playerTradesCountdown = PLAYER_TRADES_DELAY_TICKS;
            if (!countdownListening) {
                countdownListening = true;
                NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
                    if (playerTradesCountdown > 0 && --playerTradesCountdown == 0) {
                        ClientTrading.send(OrderPayload.Action.OPEN_LONG, "GUNPOWDERCRP", 30);
                        ClientTrading.send(OrderPayload.Action.CLOSE, "GUNPOWDERCRP", 10);
                    }
                });
            }
        };
    }

    private static Consumer<Minecraft> selectContract(String symbol) {
        return minecraft -> ClientMarket.select(symbol);
    }
}
