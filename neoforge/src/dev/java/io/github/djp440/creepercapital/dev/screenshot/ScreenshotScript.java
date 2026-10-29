package io.github.djp440.creepercapital.dev.screenshot;

import java.util.List;
import java.util.function.Consumer;

import io.github.djp440.creepercapital.client.ClientConfig;
import io.github.djp440.creepercapital.client.ClientKlines;
import io.github.djp440.creepercapital.client.ClientMarket;
import io.github.djp440.creepercapital.client.ClientTrading;
import io.github.djp440.creepercapital.client.ColorScheme;
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
 * 01~27 用缺省的绿涨红跌；28~40 切到红涨绿跌把各面板再截一遍（含全仓 / 杠杆与 ADL 灯、多头强平记录、钻石盘口），41 切回绿涨红跌；
 * 42~44 是可拖拽布局（#31）：缺省布局、拖拽后的布局、恢复默认布局。
 */
final class ScreenshotScript {
    static final List<ScreenshotStep> STEPS = List.of(
            new ScreenshotStep("01", "交易界面：整体",
                    "深色主题：近黑的底色上一块块深灰卡片，卡片之间留同样宽的缝；矮屏（界面高度不足 330）没有顶部标题栏，否则顶部是黄字「苦力怕资本」标题栏；"
                            + "其下是行情栏卡片，再往下左侧是 K 线图卡片（顶部周期页签 1m 5m 15m 1h 4h 1d 1w，1m 为黄字加下划线）、"
                            + "宽屏时图表右侧并排盘口栏卡片（窄屏时盘口并进图表卡片，页签「K 线 | 盘口 | 最新成交」）、底部表格区卡片，"
                            + "最右侧是从行情栏下一直通到底部的下单面板卡片；新世界自带 30 个市场日的预生成历史，所以 1m 图一进来就铺满蜡烛；"
                            + "各卡片互不重叠、不越出窗口，无错位和乱码。",
                    View.TRADING,
                    scheme(ColorScheme.GREEN_UP)),
            new ScreenshotStep("02", "交易界面：行情栏九项数值",
                    "行情栏左侧为 DIAMONDCRP（右边带下拉小三角）/ 永续，其后依次为最新价格（随行情步变色，涨绿跌红）、"
                            + "24h 涨跌（涨绿跌红，有预生成历史，所以是非零的涨跌额与涨跌幅）、标记价格、指数价格、"
                            + "资金费率 / 倒计时（黄色，形如「0.0100% / 07:59:54」，倒计时按市场时间每步少 6 秒）、24h 最高、24h 最低、24h 成交量、市场时间；"
                            + "行情栏最右端是涨跌色切换按钮（绿色块、红色块加「绿涨红跌」）；"
                            + "一行放不下时折成两行，矮屏只排一行、放不下的次要项（成交量、最低、最高、指数价格……）依次省掉，最新价格与 24h 涨跌始终在；"
                            + "三种价格彼此接近但通常不完全相同；与 01 相比各数值已刷新；整栏不越出卡片、无重叠和乱码。",
                    View.TRADING),
            new ScreenshotStep("03", "K 线图：1m 周期（预生成历史之后）",
                    "1m 为当前周期（黄字加下划线）；图表区铺满红绿蜡烛（涨绿跌红，实体加影线），上方图例两行："
                            + "第一行是鼠标所在那根（鼠标在图外时是最新一根；鼠标在图上时有十字线、价格轴上的价格标签和时间轴上的时间标签）的 O/H/L/C/成交量，"
                            + "第二行是 MA(7) 黄、MA(25) 粉、MA(99) 蓝三条均线的值，放不下的项整项省掉、不越出图表；"
                            + "三条均线在蜡烛之上连续画出，MA(7) 最贴近价格；下方是与蜡烛一一对应的成交量柱（涨绿跌红、半透明），"
                            + "右侧价格轴有 5 条网格价位（离最新价格标签太近的刻度省掉，不与它叠在一起），最新价格用虚线和涨跌色标签标出，底部时间轴标有「第 N 日 HH:mm」；"
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
                            + "列表挂在交易代码下方，盖在行情栏第二行最左边（或矮屏时的图表卡片）之上，文字清晰、无错位；"
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
                    "左下的表格区卡片：页签条第一个是黄字加下划线的「资产」（窄屏时页签收紧或换成短名），下方四项数据（名称在上、数值在下，等宽排开，放不下时折行）："
                            + "钱包余额 12,345.678901 CRP、未实现盈亏 0.00 CRP、保证金余额 12,345.678901 CRP、可用余额 12,345.678901 CRP"
                            + "（还没有仓位，所以后三项中除未实现盈亏外都等于钱包余额）；刚执行过 /creeper dev deposit @p 12345.678901，"
                            + "余额已立即刷新而不是显示 0.00；K 线图被压缩到表格区之上，图表底部不与表格区重叠；无错位和乱码。",
                    View.TRADING,
                    deposit("12345.678901")),
            new ScreenshotStep("11", "下单面板（GUNPOWDERCRP，3 级验收）",
                    "最右侧通栏的下单面板卡片：顶部「市价」「限价」页签（市价为黄字加下划线）和「逐仓」按钮；杠杆一行有 − / + 按钮，当前 20x；"
                            + "数量框单位为 GUNPOWDER（数量精度 0 位，整数个），输入框里已填 2；"
                            + "下方预估：可用余额 12,345.68 CRP（下单面板里金额保留 2 位小数，资产页显示账本精确值）、开多均价（绿字，约最新价 + 半个点差）、开空均价（红字，约最新价 − 半个点差），"
                            + "两个均价与实际市价成交同源（沿虚拟流动性曲线逐档吃单）；预估保证金 = 开多名义价值 / 20、预估手续费 = 名义价值 × 0.05%；"
                            + "其下是绿色「开多」、红色「开空」两个按钮和一个「平仓」按钮（没有仓位时灰掉）；面板底部有空间时显示「账户」概况"
                            + "（钱包余额、未实现盈亏、保证金余额）；面板不越出窗口、不与图表重叠，无错位和乱码。",
                    View.ORDER_PANEL,
                    leverage("GUNPOWDERCRP", 20)),
            new ScreenshotStep("12", "仓位页（3 级验收）",
                    "表格区的「仓位」页签为黄字加下划线；完整的表头是：合约、模式、数量、开仓均价、标记价格、预估强平价、保证金率、保证金、未实现盈亏、收益率、ADL、操作，"
                            + "表格宽度不够时按优先级省掉次要列（先省模式，再省保证金、标记价格、ADL、收益率……），合约、数量、未实现盈亏与操作始终在；"
                            + "两行仓位：GUNPOWDERCRP 逐仓 20x 多 50 个（绿字）和 DIAMONDCRP 逐仓 10x 空 1.500 个（红字）；"
                            + "开仓均价与标记价格相差约半个点差，未实现盈亏随标记价格变化，带正负号，收益率是百分比（盈绿亏红）；"
                            + "单元格放不下的文字以「…」截断，不压到相邻列；"
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
                    "宽屏时图表与下单面板之间是盘口栏卡片，顶部页签条「盘口 | 最新成交」，「盘口」为黄字加下划线"
                            + "（窄屏时盘口并进图表卡片，页签「K 线 | 盘口 | 最新成交」中「盘口」选中）；表头「价格(CRP) 数量 累计」；"
                            + "上半部分是卖盘（红字）：离最新价格最近的卖一在最下面，往上价格逐档升高；下半部分是买盘（绿字）：买一在最上面，往下价格逐档降低；"
                            + "中间一行左边是最新价格（涨绿跌红或持平白字）、右边灰字是标记价格，卖一 = 最新价 + 0.25（半个点差），买一 = 最新价 − 0.25；"
                            + "档数随栏高增减，栏高足够时上下各最多 20 档；"
                            + "每档数量相同（约 500 CRP 名义价值折成的数量，钻石价格 1000 上下时约 0.46~0.5 个），累计一列逐档递增，背景有按累计数量画的半透明深度条（卖盘红、买盘绿）；"
                            + "价格逐档拉开的幅度很小（钻石 10,000 CRP 的滑点只有约 0.05%，相邻档价差约 0.05）；"
                            + "栏内文字不重叠、不越出栏边框，图表被压窄但不与右侧栏重叠。",
                    View.ORDER_BOOK,
                    selectContract("DIAMONDCRP")),
            new ScreenshotStep("15", "盘口：GUNPOWDERCRP（3 级验收）",
                    "同 14，但是火药自己的盘口：价格在 100 上下，卖一 = 最新价 + 0.025，买一 = 最新价 − 0.025；"
                            + "每档数量相同（约 500 CRP 折成的整数个，数量精度 0 位，价格 100 上下时约 4~5 个），累计逐档递增；"
                            + "火药滑点更大（10,000 CRP 约 0.3%），所以相邻档价差（约 0.03）明显大于钻石盘口里的，"
                            + "盘口价格范围比钻石更宽；与 14 相比切换合约后盘口立即换成火药的深度，无错位和乱码。",
                    View.ORDER_BOOK,
                    selectContract("GUNPOWDERCRP")),
            new ScreenshotStep("16", "限价下单（GUNPOWDERCRP，3 级验收）",
                    "下单面板顶部的「限价」页签为黄字加下划线（「市价」为灰字）；杠杆 20x；杠杆行下面多了一个委托价输入框"
                            + "（右侧单位 CRP），里面已填一个比最新价低约 3% 的价格；其下数量框已填 2（单位 GUNPOWDER）；"
                            + "预估区只有可用余额、预估保证金（按委托价 × 数量 / 20）、预估手续费（按 maker 0.02%）三行，"
                            + "没有开多 / 开空均价；其下仍是绿色「开多」、红色「开空」和「平仓」按钮；"
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
                    "盘口栏（窄屏时是图表卡片）的「最新成交」页签为黄字加下划线；表头「价格(CRP) 数量 时间」；"
                            + "列表最新的在最上面，每行：价格（买入绿、卖出红，落在最新价格 ± 半个点差 0.025 之内）、数量（整数个，火药精度 0 位）、"
                            + "市场时间（HH:mm:ss，每个行情步 6 秒，同一步内的几笔时间相同）；大量灰白数量的普通行是模拟成交，一个行情步拆成 2 到 8 笔；"
                            + "列表顶部是玩家成交置顶区（最近 30 秒内的玩家成交，最多 3 笔，最多占页面一半高度，下方一条分隔线）："
                            + "两笔刚下的玩家成交（开多 30 个、平仓 10 个，自动截图的玩家；可能还有前几步限价成交的那笔）整行带黄色半透明底色和左侧黄色色条，"
                            + "并多占一行用黄字标出「玩家 <玩家名>」，开多那笔价格为绿（买入）、平仓那笔价格为红（卖出）；置顶的玩家成交不在下面的列表里重复；"
                            + "文字不重叠、不越出卡片，列表放不下的旧成交被截掉。",
                    View.LATEST_TRADES,
                    placePlayerTrades()),
            new ScreenshotStep("20", "全仓仓位（一组全仓、一组逐仓，3 级验收）",
                    "表格区在「仓位」页；刚把 GUNPOWDERCRP 的旧仓位平掉、切为全仓，并以 10x 开多 300 个（名义价值约 3.8 万 CRP），此时 DIAMONDCRP 10x 空 1.500 个仍是逐仓："
                            + "两行分别是 GUNPOWDERCRP（绿字，模式列「全仓 10x」）和 DIAMONDCRP（红字，模式列「逐仓 10x」），表格较窄省掉模式列时可在下单面板的仓位提示里看到杠杆；"
                            + "每行有预估强平价（黄字）和保证金率（百分比；逐仓行只算该仓位自己的保证金，约 4%；全仓行是维持保证金除以全仓钱包，约 5%）；"
                            + "逐仓空头的强平价高于标记价格约 9.5%，全仓多头的强平价约 87、远低于标记价格 126（全仓用整个余额扛亏损，所以远得多）；"
                            + "右侧下单面板（GUNPOWDERCRP，市价页签）顶部右侧的模式按钮显示「全仓」；列不重叠、无乱码。",
                    View.POSITIONS,
                    openCrossGunpowder()),
            new ScreenshotStep("21", "杠杆调整（有仓位时调低杠杆，3 级验收）",
                    "刚把 GUNPOWDERCRP 的全仓多头从 10x 调到 5x（点了杠杆 − 一次）：下单面板的杠杆显示 5x，且 − / + 按钮没有被锁定；"
                            + "下单面板结果行是「GUNPOWDERCRP 杠杆已调整为 5x」；仓位页里这一行模式列变为「全仓 5x」（省掉模式列时看下单面板），"
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
                    cancelDiamondStopLoss()),
            new ScreenshotStep("26", "成交历史：强平记录（3 级验收）",
                    "「成交历史」页签为黄字加下划线；刚撤掉 DIAMONDCRP 的开空挂单，再用 /creeper dev market jump DIAMONDCRP 15 让价格在下一个行情步上涨，"
                            + "DIAMONDCRP 逐仓 10x 空头（1.500）被强平；最新的一行在最上面：合约 DIAMONDCRP，方向列是黄字「强平空」（强平记录用品牌黄色，与主动平仓的红绿区分），"
                            + "成交价就是跳涨后的标记价格（比之前的开空均价高出一成多），数量 1.500；"
                            + "行情跳涨超过保证金所能承受的幅度（10x 只能扛约 10%），所以这是一次穿仓：保证金不剩，手续费（清算费）显示 0.00，已实现盈亏是个很大的负数（红字，带负号）；"
                            + "右侧下单面板底部的结果行是红字「DIAMONDCRP 被强平」，完整显示、不被截断；"
                            + "下面是之前的平仓 / 开仓记录；DIAMONDCRP 的止盈单随仓位消失（可到「历史委托」页看到「已失效（仓位已平）」）；列不重叠、无乱码。",
                    View.TRADES,
                    liquidateDiamondShort()),
            new ScreenshotStep("27", "ADL 指示灯（仓位页，3 级验收）",
                    "表格区在「仓位」页，表头在「收益率」与「操作」之间有一列「ADL」；"
                            + "刚以 10x 开了 DIAMONDCRP 多头 1.500 个，并用 /creeper dev market jump 让 GUNPOWDERCRP 上涨约 8%、DIAMONDCRP 上涨约 4%，两个仓位都盈利（绿字）："
                            + "盈利率 x 杠杆得分更高的是 DIAMONDCRP 10x（约 4.0），GUNPOWDERCRP 全仓 5x 次之（约 2.0），"
                            + "所以 ADL 列里 DIAMONDCRP 一行的五根小柱全亮（黄色，柱子逐根增高）、GUNPOWDERCRP 一行亮三根（前三根黄、后两根深灰）；"
                            + "两行的灯数不同且与盈利率排序一致；这一列不与相邻的盈亏列、操作列的「市价平仓」按钮重叠或被截断，其余列无乱码。",
                    View.POSITIONS,
                    openDiamondLongAndJump()),
            new ScreenshotStep("28", "红涨绿跌：整体与仓位页（3 级验收）",
                    "刚把涨跌色切到「红涨绿跌」（与点行情栏右端的切换按钮、改模组配置里的「涨跌色」是同一个配置项）："
                            + "行情栏右端的切换按钮变为红色块在前、绿色块在后，文字「红涨绿跌」；K 线切回 1m，阳线（收盘高于开盘）变红、阴线变绿，成交量柱同色；"
                            + "最新价格标签、行情栏最新价格与 24h 涨跌按涨红跌绿着色；仓位页两行多头的合约与数量是红字（多 = 涨色），"
                            + "两行都盈利，所以未实现盈亏、收益率是红字带正号；预估强平价、ADL 灯仍是品牌黄，次要文字仍是灰色，不受配色影响；"
                            + "下单面板「开多」按钮变红、「开空」按钮变绿；无重叠、截断和乱码。",
                    View.POSITIONS,
                    both(scheme(ColorScheme.RED_UP), select(CandlePeriod.M1))),
            new ScreenshotStep("29", "红涨绿跌：盘口（GUNPOWDERCRP，3 级验收）",
                    "盘口页：卖盘（上半，卖出 = 跌色）变为绿字、深度条绿色，买盘（下半，买入 = 涨色）变为红字、深度条红色；"
                            + "中间一行的最新价格按上一步的涨跌着色（涨红跌绿，持平白字），右侧灰字是标记价格；"
                            + "窄屏时盘口并进图表卡片（页签「K 线 | 盘口 | 最新成交」，「盘口」为黄字加下划线）；无重叠、截断和乱码。",
                    View.ORDER_BOOK),
            new ScreenshotStep("30", "红涨绿跌：最新成交，玩家成交置顶（3 级验收）",
                    "打开界面前刚下了两笔 GUNPOWDERCRP 市价单（开多 30 个、平仓 10 个），之后约 2 秒里又到了十几笔模拟成交；"
                            + "「最新成交」页顶部是玩家成交置顶区（最多 3 笔，最新在上；除这两笔外可能还有 30 秒内更早的一笔玩家成交）："
                            + "两笔玩家成交（黄色半透明底色、左侧黄色色条，第二行黄字「玩家 <玩家名>」），"
                            + "开多那笔价格为红（买入 = 涨色）、平仓那笔价格为绿（卖出 = 跌色），置顶区下方一条分隔线；"
                            + "分隔线下是普通的最新成交列表（买入红、卖出绿），玩家那两笔不在下面重复出现；"
                            + "置顶区最多占页面一半高度，玩家成交在置顶区停留 30 秒后回到普通列表；无重叠、截断和乱码。",
                    View.LATEST_TRADES,
                    placePinnedPlayerTrades()),
            new ScreenshotStep("31", "红涨绿跌：下单面板市价预估（3 级验收）",
                    "下单面板（GUNPOWDERCRP，市价，数量 2）：预估区依次是可用余额、开多均价（红字，略高于最新价）、开空均价（绿字，略低于最新价）、"
                            + "保证金、手续费；两个均价与实际市价成交走同一条虚拟流动性曲线（数量很大时均价离最新价更远）；"
                            + "「开多」按钮红色、「开空」按钮绿色；面板底部的账户概况里未实现盈亏为正、红字；表格区在资产页，未实现盈亏同样红字带正号；"
                            + "无重叠、截断和乱码。",
                    View.ORDER_PANEL,
                    estimatePage()),
            new ScreenshotStep("32", "红涨绿跌：限价下单（3 级验收）",
                    "下单面板「限价」页签为黄字加下划线；委托价框已填最新价的 97%，数量 2；预估区是可用余额、保证金、手续费（按 maker）三行，"
                            + "没有开多 / 开空均价；「开多」红、「开空」绿；有仓位时数量框上方的持仓提示是红字（多）；无重叠、截断和乱码。",
                    View.LIMIT_ORDER),
            new ScreenshotStep("33", "红涨绿跌：止盈止损设置（3 级验收）",
                    "下单面板的「止盈止损」小页签为黄字加下划线，止盈价、止损价已填；页签右侧的仓位提示「多 320 · 5x」为红字（多 = 涨色）；"
                            + "底部「开多」红、「开空」绿，下面一行「平仓」「设止盈止损」两个按钮完整显示；无重叠、截断和乱码。",
                    View.PROTECTION),
            new ScreenshotStep("34", "红涨绿跌：当前委托页（3 级验收）",
                    "「当前委托」页签为黄字加下划线；GUNPOWDERCRP 的止盈、止损单与限价只减仓单方向列是「平多」，红字（跟随所平仓位的方向，多 = 涨色）；"
                            + "DIAMONDCRP 的止盈单（方向「平空」）已随前面的强平失效，不在这里；每行末尾「撤单」按钮；列不重叠、无乱码。",
                    View.OPEN_ORDERS),
            new ScreenshotStep("35", "红涨绿跌：历史委托页（3 级验收）",
                    "「历史委托」页签为黄字加下划线；方向列开多 / 平多为红字、开空 / 平空为绿字；状态列「已成交」白字、"
                            + "「已撤销」「已失效（仓位已平）」灰字；列不重叠、无乱码。",
                    View.ORDER_HISTORY),
            new ScreenshotStep("36", "红涨绿跌：成交历史页（3 级验收）",
                    "「成交历史」页签为黄字加下划线；最新两笔是步骤 30 的玩家成交：开多（红字）与平多（绿字，平多是卖出 = 跌色）；"
                            + "已实现盈亏为正的是红字带正号、为负的是绿字带负号；强平记录（DIAMONDCRP「强平空」）仍是品牌黄；列不重叠、无乱码。",
                    View.TRADES),
            new ScreenshotStep("37", "红涨绿跌：资金费记录页（3 级验收）",
                    "「资金费记录」页签为黄字加下划线；方向列多为红字、空为绿字；资金费金额收到（正号）为红字、支付（负号）为绿字；"
                            + "未收付列仍为灰色 \"--\"；列不重叠、无乱码。",
                    View.FUNDING),
            new ScreenshotStep("38", "红涨绿跌：全仓 / 杠杆与 ADL 指示灯（仓位页，3 级验收）",
                    "仍是红涨绿跌；表格区在「仓位」页：GUNPOWDERCRP 一行模式列是「全仓 5x」、DIAMONDCRP 一行是「逐仓 10x」"
                            + "（表格较窄省掉模式列时看下单面板的仓位提示），多头的合约、数量与盈利的未实现盈亏、收益率都是红字（多 = 涨色，盈利 = 涨色）；"
                            + "ADL 列的小柱仍是品牌黄（亮）与深灰（灭），不随配色变，各行灯数与盈利率 x 杠杆的排序一致；"
                            + "预估强平价仍是黄字、保证金率不受配色影响；列不重叠、ADL 列不被截断、无乱码。",
                    View.POSITIONS),
            new ScreenshotStep("39", "红涨绿跌：强平记录（多头被强平，3 级验收）",
                    "「成交历史」页签为黄字加下划线；刚用 /creeper dev market jump DIAMONDCRP -15 让价格在下一个行情步下跌，"
                            + "DIAMONDCRP 逐仓 10x 多头（1.500）越过强平价（约 -9.5%）被强平；最新一行在最上面：合约 DIAMONDCRP，方向列是黄字「强平多」"
                            + "（强平记录用品牌黄，不随涨跌色变），成交价是下跌后的标记价格，数量 1.500；穿仓所以手续费（清算费）显示 0.00，"
                            + "已实现盈亏是很大的负数，在红涨绿跌下按跌色显示为绿字、带负号；下一行是步骤 27 的开多（红字）；"
                            + "右侧下单面板底部结果行是红字「DIAMONDCRP 被强平」；列不重叠、无乱码。",
                    View.TRADES,
                    liquidateDiamondLong()),
            new ScreenshotStep("40", "红涨绿跌：盘口（DIAMONDCRP，3 级验收）",
                    "盘口页换成 DIAMONDCRP 自己的深度（价格在 1000 附近）：卖盘（上半）绿字、绿色深度条，买盘（下半）红字、红色深度条；"
                            + "中间一行最新价格按最近一个行情步的涨跌着色（涨红跌绿，持平白字，与行情栏最新价格同色），右侧灰字是标记价格；表格区回到资产页；"
                            + "每档数量相同（约 0.46~0.5 个），累计逐档递增，相邻档价差很小（约 0.05）；窄屏时盘口并进图表卡片；无重叠、截断和乱码。",
                    View.ORDER_BOOK,
                    selectContract("DIAMONDCRP")),
            new ScreenshotStep("41", "切回绿涨红跌（3 级验收）",
                    "刚把涨跌色切回「绿涨红跌」：行情栏右端的切换按钮变为绿色块在前、红色块在后，文字「绿涨红跌」；"
                            + "K 线阳线绿、阴线红，盘口卖盘红、买盘绿，下单面板「开多」绿、「开空」红，与 01~27 的配色一致；"
                            + "切换立即生效，不需要重开界面；无重叠、截断和乱码。",
                    View.TRADING,
                    scheme(ColorScheme.GREEN_UP)),
            new ScreenshotStep("42", "默认布局（#31，3 级验收）",
                    "区域比例为缺省（客户端配置里三项比例都是 0）的标准布局，作为拖拽前的对照：标题栏、两行行情栏，下面左侧 K 线图卡片、图表右侧窄一点的盘口栏、底部表格区，"
                            + "最右侧是从行情栏下一直通到底部的下单面板；卡片之间是同样宽的缝；标题栏右端是灰掉的「恢复默认布局」按钮（布局还是缺省，点不动）；"
                            + "截图时鼠标停在窗口正中，正好悬停在 K 线图与盘口栏之间的缝上，所以这条缝高亮成灰色（悬停提示；真机上光标同时变成左右调整形）；"
                            + "表格区回到资产页，无重叠、截断和乱码。",
                    View.TRADING,
                    both(scheme(ColorScheme.GREEN_UP), layout(0, 0, 0))),
            new ScreenshotStep("43", "拖拽边框后的布局（#31，3 级验收）",
                    "等同于玩家把三条边框都拖过后的布局（下单面板宽度比例 0.34、盘口栏 0.26、表格区高度比例 0.50）：下单面板和盘口栏都比 42 更宽，"
                            + "K 线图相应变窄，底部表格区明显变高、图表变矮，但图表仍不小于最小尺寸；卡片之间的缝仍然等宽，没有重叠、不越出窗口；"
                            + "标题栏右端的「恢复默认布局」按钮亮起（白字，可点）；下单面板、盘口、表格内容跟着新尺寸重排，无截断和乱码。",
                    View.TRADING,
                    layout(0.34, 0.26, 0.5)),
            new ScreenshotStep("44", "恢复默认布局（#31，3 级验收）",
                    "相当于点了「恢复默认布局」：布局与 42 完全一致，「恢复默认布局」按钮重新灰掉；客户端配置里的三项比例回到 0（下一次启动不受截图影响）。",
                    View.TRADING,
                    layout(0, 0, 0)),
            new ScreenshotStep("45", "图表放大后（#33，3 级验收）",
                    "1m 周期，绿涨红跌；每根蜡烛约 14 像素宽（比 03 明显更粗更疏），只能看到二十来根，图表停在往回看 120 根处（最新价格虚线不在画面最右，"
                            + "但价格轴右侧的最新价格标签始终在）；价格轴范围只贴着这二十来根（刻度间距与 03 不同）；"
                            + "均线、成交量柱与蜡烛一一对应，最左 / 最右一根若只露出一部分，被裁在绘图区内，不压到图例或价格轴；时间轴标注间隔随蜡烛变粗而变密；无重叠和乱码。",
                    View.CHART_ZOOMED_IN,
                    both(scheme(ColorScheme.GREEN_UP), select(CandlePeriod.M1))),
            new ScreenshotStep("46", "图表缩小后（#33，3 级验收）",
                    "每根蜡烛 2 像素（实体 1 像素宽，比 03 细密得多），同一块图表里能看到约 200 根；价格轴范围随之放大到覆盖这些根；"
                            + "时间轴标注的间隔成倍加大，相邻标注不挤在一起；最新一根贴右，均线与成交量柱仍清晰连续。",
                    View.CHART_ZOOMED_OUT),
            new ScreenshotStep("47", "图表平移后（#33，3 级验收）",
                    "缺省缩放（7 像素）、往回看 150 根：画面里是更早的行情，最右一根不是最新一根；图例默认显示最右可见那一根的开高低收；"
                            + "价格轴随这段可见区间重新缩放（与 03 的价位范围不同）；时间轴上的标注是更早的时间；右侧最新价格标签与虚线按当前最新价格画（虚线若超出价格轴范围会落在图外，不画出界）。",
                    View.CHART_PANNED),
            new ScreenshotStep("48", "图表拖到缓存最旧一根（#33，3 级验收）",
                    "往回看到缓存里最早的一根：它贴着绘图区左边缘；左边缘泛一层向右渐隐的黄光，其右侧有黄字提示框「已到最早的缓存 K 线」；"
                            + "提示框在主图区内、不盖住图例，也不越出图表。",
                    View.CHART_AT_OLDEST));

    private ScreenshotScript() {
    }

    /** 切换涨跌色方案（写进客户端配置，与交易界面上的切换按钮、模组配置界面改的是同一项）。 */
    private static Consumer<Minecraft> scheme(ColorScheme scheme) {
        return minecraft -> ClientConfig.setColorScheme(scheme);
    }

    /** 设置交易界面的区域比例（等同于玩家拖拽边框后松手，写进客户端配置）；全 0 即缺省布局。 */
    private static Consumer<Minecraft> layout(double orderPanel, double column, double table) {
        return minecraft -> ClientConfig.setLayoutRatios(orderPanel, column, table);
    }

    /** 下单面板回到市价、「预估」页（前面的步骤停在限价或止盈止损页）。 */
    private static Consumer<Minecraft> estimatePage() {
        return minecraft -> {
            ClientTrading.setLimitMode(false);
            ClientTrading.setProtectionPage(false);
        };
    }

    private static Consumer<Minecraft> both(Consumer<Minecraft> first, Consumer<Minecraft> second) {
        return first.andThen(second);
    }

    /**
     * 打开视图之前就下两笔 GUNPOWDERCRP 市价单（开多 30 个、平掉 10 个），截图时已过了约 2 秒、来了十几笔模拟成交，
     * 玩家成交仍应在「最新成交」顶部的置顶区里。沿用仓位当前的杠杆（全仓 5x）。
     */
    private static Consumer<Minecraft> placePinnedPlayerTrades() {
        return minecraft -> {
            ClientMarket.select("GUNPOWDERCRP");
            ClientTrading.send(OrderPayload.Action.OPEN_LONG, "GUNPOWDERCRP", 30);
            ClientTrading.send(OrderPayload.Action.CLOSE, "GUNPOWDERCRP", 10);
        };
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

    /**
     * 制造一次强平：先撤掉 DIAMONDCRP 的开仓挂单（不然跳涨会把那张限价空单成交成新仓位），
     * 再让 DIAMONDCRP 在下一个行情步上涨 15%，逐仓 10x 空头越过强平价（约 +9.5%），被行情步的强平阶段强平。
     */
    private static Consumer<Minecraft> liquidateDiamondShort() {
        return minecraft -> {
            ClientTrading.openOrdersNewestFirst().stream()
                    .filter(order -> order.symbol().equals("DIAMONDCRP") && !order.reduceOnly())
                    .forEach(order -> ClientTrading.cancel(order.id()));
            minecraft.player.connection.sendCommand("creeper dev market jump DIAMONDCRP 15");
        };
    }

    /** 让 DIAMONDCRP 在下一个行情步下跌 15%，步骤 27 开的逐仓 10x 多头越过强平价（约 -9.5%）被强平。 */
    private static Consumer<Minecraft> liquidateDiamondLong() {
        return minecraft -> minecraft.player.connection.sendCommand("creeper dev market jump DIAMONDCRP -15");
    }

    /**
     * 让仓位页有两个盈利仓位、ADL 灯数不同：以 10x 开 DIAMONDCRP 多头 1.500 个（上一步它的空头刚被强平，已没有仓位），
     * 再让 GUNPOWDERCRP（全仓 5x 多头 300 个）上涨约 8%、DIAMONDCRP 上涨约 4% 。
     * 得分 = 盈利率 x 杠杆：DIAMONDCRP 约 0.4 x 10 = 4.0，GUNPOWDERCRP 约 0.4 x 5 = 2.0，所以 DIAMONDCRP 排第一（5 格）、GUNPOWDERCRP 排第二（3 格）。
     * 开仓包先于跳涨命令被服务端处理，开仓价是跳涨之前的价格。
     */
    private static Consumer<Minecraft> openDiamondLongAndJump() {
        return minecraft -> {
            ClientTrading.setLimitMode(false);
            ClientTrading.selectLeverage("DIAMONDCRP", 10);
            ClientTrading.send(OrderPayload.Action.OPEN_LONG, "DIAMONDCRP", 1500);
            minecraft.player.connection.sendCommand("creeper dev market jump GUNPOWDERCRP 8");
            minecraft.player.connection.sendCommand("creeper dev market jump DIAMONDCRP 4");
        };
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
