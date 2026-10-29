# MEMORY

## 2026-10-29 20:43 code-review 修复的设计要点（分支 fix/code-review）

- 限价单是否「穿越」只看流动性曲线的第一档（`spec.liquidityCurve(last).touchPrice(buy)` 与委托价比较），不看整单平均价：滑点只影响成交价，不影响能不能成交。
  立即成交时成交价取曲线平均价并限制在委托价之内（买不高于、卖不低于委托价），走 `openAt` / `closeAt`（收 taker 费）。
- 限价挂单检查资金用 `assets().availableBalance`（已扣全仓浮亏），与 `openAt` 同一口径。
- 全仓强平的撤单、没收范围只含「保证金模式是全仓」的合约（`marginMode(player, symbol) == CROSS`）；逐仓合约的挂单冻结资金不动。
  已知残余：`MarginMath.assess` 的钱包余额仍含逐仓挂单冻结，触发判断略宽松（见 memo-fix.md）。
- `TradingBook.estimateLimit(TradingParams, price, lots, leverage)`：下单面板的限价预估与服务端冻结金额共用同一个定点数计算，界面不再用 double 算金额。
- `checkSolvency`：池子权益只算一次，每次 ADL 后用事件的 `lostProfit` 增量更新，避免 O(n²)。
- 魔数收口：`Money.UNITS_PER_CRP`、`ContractSpec.quantityOf` / `lotsPerUnit`、`MarginMath.hasMark` 是共用入口，不要再各处写 `1_000_000` / `Math.pow(10, dec)`。
- 存档里的枚举统一用 `server/EnumCodecs.of(Class)` 按名字编码（TradingSavedData、OfflineSummarySavedData），不要再手写序号 codec。
- `OfflineSummaries.record(OrderEvent, …)` 的 switch 有 `default` 抛异常，新增委托类型时会立刻暴露而不是静默丢事件。
- GameTest：`StageOrderGameTests` 用一次 `ServerTrading.onMarketStep` 验证四阶段顺序（止盈 → 资金费 → 强平 → ADL）。
  共用账本的池子没法在 neoforge 里直接抽干，所以用 GUNPOWDERCRP 上以 1/20 价格开的杠杆 1 巨额多头让池子权益转负，收尾用 `fundPool` 把被平仓支付掉的池子补回。
  `helper.succeedWhen` 的回调会反复重试，清理（离线 / 平仓 / 提现）必须放在全部断言通过之后，不能放 finally。
  `assertValueEqual` 的参数是（期望，实际）：失败信息里「to be X: was Y」中 X 是第一个参数。
- 截图脚本现为 41 步：01~27 绿涨红跌，28~40 红涨绿跌（38 全仓 / 杠杆 / ADL 灯、39 多头强平记录、40 钻石盘口），41 切回。

## 2026-10-29 21:00 翻译键完整性检查与验收清单（#20）

- 检查放在 `neoforge/src/test`（JUnit，`LangFilesTest` + `LangChecksTest`），`./gradlew check` 自带 `:neoforge:test`。选 JUnit 不选 GameTest：只读文件、秒级完成、不用拉起服务端。不引入 JSON 库，自带“只认扁平字符串对象”的小解析器，因为 Gson 之类会静默吞重复键（#16 踩过），而重复键正是要抓的。
- 查什么：无重复键、中英键集合一致、无空值、en_us 无中日韩字符、zh_cn 无汉字的条目须登记 `NEUTRAL_IN_ZH`、格式化参数序号一致、源码里的键字面量与常量拼接都存在、client 目录无硬编码中文。
- 动态拼接键（`trade_side.*`、`order_type.*`、`order_status.*`、`column.*`、`message…order.<拒单原因/结果代码>`、`offline_summary.<EventType>`）按“展开规则”检查，枚举和结果代码直接读 core/server 源码。所以新增枚举常量、拒单原因、表头列而漏翻译会在 check 里失败；新增不属于这些的拼接规则要在 `dynamicKeysAreDeclaredHere` 补展开；`ServerTrading` 里新增的非下单结果的蛇形字面量会被误当结果代码，加进 `NOT_ORDER_CODES` 并写明用途。
- 不覆盖：孤儿键、硬编码英文文案、译文质量（术语对 GLOSSARY 只能人工看）。测试用 `Path.of("")` 往上找仓库根，不用 `-D` 传路径（守护进程 `file.encoding=COMPAT`，中文路径经系统属性传递会损坏）。
- 人工验收清单 `docs/checklist/v0.1-trading-feel.md`（16 节 41 项）。易错点：资金费每 8 市场小时约 80 真实分钟，只能靠 `market funding` 快进；`market advance` 跳过中间步的挂单 / 强平 / 资金费；`jump` 实际跳幅被标记价格平滑，一次不触发就再发；离线 / 重启 / 多人必须用专用服务端；持仓上限最易用 `"open_interest_ratio": 0.01` 触发；ADL 需池子对盈利付不起（1x 约 40 万名义 + `jump +300`）。

## 2026-10-29 20:05 #20 界面还原打磨与涨跌色切换（feat/20-ui-polish）
- 布局改成纯函数 `client/TradingLayout.compute(width, height, tickerRows)`：深色底上卡片式布局，卡片间距 `GAP = 4`；右侧下单面板**通栏到底**（宽 = 屏宽 26%，夹在 150～200），左侧上为图表（+ 盘口栏）、下为表格区（高 = 左侧高度 36%，夹在 64～260，且优先保证图表最小高度 100）。`TradingLayoutTest` 在 8 种窗口（854×480～2560×1440、1024×768、800×600）× GUI 缩放 0（自动）～4 换算出的界面尺寸，以及最小 320×240 上断言卡片互不重叠、不出屏、各自不小于最小尺寸。
- 降级顺序（写在 `TradingLayout` 类注释里）：① 高度 < 330（1080p 自动缩放 480×270、1280×720 自动缩放 427×240 都算）省掉标题栏、行情栏只排一行；② 左侧放不下「图表 ≥ 220 + 盘口栏」时盘口 / 最新成交并进图表区，与 K 线同一页签条切换（`ColumnMode.TABBED`）；③ 表格列放不下时按优先级隐藏次要列（`LayoutFit.columns`），只剩必须列仍放不下就等比缩窄、文字「…」截断；④ 页签条依次试全名宽松 → 全名紧凑 → 短名（`table.*.short` 键）→ 最紧，仍放不下由裁剪兜底。
- 行情栏 `LayoutFit.ticker`：按显示顺序排，最多 2 行（矮屏 1 行），超了按优先级从低到高丢（最新价 10 > 涨跌 9 > 标记价 8 > 资金费 7 > 市场时间 6 > 指数价 5 > 24h 高 4 > 低 3 > 成交量 2），排得下以后再按优先级从高到低把丢掉的逐个放回试（回填，避免丢了一项宽的以后留空白）。
- 涨跌色：`ClientConfig`（NeoForge CLIENT 配置 `config/creepercapital-client.toml`，项 `colorScheme` = `GREEN_UP` | `RED_UP`，默认 `GREEN_UP`）+ `ColorScheme` 枚举。所有涨跌语义只走 `BinancePalette.up()/down()/side(Side)/signed(x)/direction(cur, prev)`，每次绘制时读配置，所以切换立即生效、无缓存。品牌黄（强平 / ADL 方向、资金费率、选中页签）与中性灰不随配色变。入口两个：① 交易界面行情栏卡片第一行最右端的按钮（两个色块 + 「绿涨红跌 / 红涨绿跌」），点击切换并写回配置；② 模组列表 → Creeper Capital → 配置（NeoForge `ConfigurationScreen`）→「涨跌色」。
- 颜色口径：下单成功消息用中性白、失败固定红（不随配色，红在两种配色里都有「警示」含义）；保证金率 ≥ 50% 品牌黄、≥ 80% 用 `down()`；按钮「开多 / 买」用 `up()`，「开空 / 卖」用 `down()`；预估为空（未输数量）显示中性「--」，不着涨跌色。
- 下单预估与实际成交同源：core 新增 `TradingBook.estimateOpen(params, lastPrice, side, lots, leverage)` → `Optional<OpenEstimate>`，内部调用与 `open` 相同的 `fillPrice` 曲线（深度滑点 + 点差）；面板显示「开多均价 / 开空均价」两行，保证金与手续费按名义价值较高的一侧（开多）显示，偏保守。`TradingBookTest` 3 例断言预估均价 = 实际开仓均价。
- 玩家成交停留：`PlayerTradePins`，玩家成交到达后在「最新成交」顶部置顶区停留 **30 秒（真实时间）**，最多 **3 笔**、最多占半页，带品牌黄左边条与底色、第二行标「玩家 <名字>」；下方仍按时间倒序显示全部成交（含这几笔），时间线不被篡改。选这个方案而不是「玩家成交权重更高 / 少挤出」的理由：feed 本身保持诚实（全服可见、只保留最近 50 笔缓存不变），置顶只延长可见时间，不改服务端、不改载荷。
- 金额显示：下单面板窄，余额 2 位小数（`MarketFormat.money`）；底部「资产」页显示账本精确值（`MarketFormat.balance`，最多 6 位小数、去尾零），一行放不下排成 2×2。
- 截图：`runScreenshots` 支持 `-PscreenshotWidth/-PscreenshotHeight/-PscreenshotGuiScale`（缺省 1280×720、GUI 缩放 2；`0` = 自动），README 写明窗口与界面坐标尺寸。脚本 01～27 绿涨红跌（步骤 01 强制 `GREEN_UP`，不受本机配置影响），28～37 红涨绿跌覆盖全部面板（仓位、盘口、最新成交置顶、预估、限价、止盈止损、当前 / 历史委托、成交、资金费），38 切回绿涨红跌。新视图 `View.ORDER_BOOK` → `TradingScreen.openWithOrderBook()`。
- 未改载荷，`PROTOCOL_VERSION` 不变。

## 2026-10-29 19:30 持仓总量上限与 ADL 的实现要点（#17）

- 语义全文见 `docs/adr/0007-open-interest-cap-and-entry-price-adl.md`，这里只记实现。`TradingBook` 的 `checkOpenInterest` 在 `openAt` 里 POSITION_LIMIT 之后检查，市价与限价成交共用；`OrderBook.placeLimit` 挂单时查一次，成交时复检，失败走 `FILL_REJECTED` 撤单。`TradeKind.ADL`、`TradeResult.Reason.OPEN_INTEREST_LIMIT` 追加在枚举末尾（按序号编码）。
- 减仓量：对手数二分，找让 `recovered(position, mark, lots) >= deficit` 的最少手数（对手数单调不减）。池子现金不变、玩家余额只增，所以守恒且不为负。
- 配置 `open_interest_ratio`（可选，缺省 0.5，`ContractSpec` 第 11 个分量，旧构造器委托缺省），旧配置不回写。行情步顺序增至 ①委托 → ②资金费 → ③强平 → ④`autoDeleverageStage`。
- 离线汇总里 ADL 的金额 = 成交额而非已实现盈亏（ADL 的已实现盈亏恒为 0），这样才能和成交历史“价格 × 数量”对账。
- 新增 `AdlLightsPayload`（合约 → 灯数，只含盈利仓位，客户端缺省 1 格），每个行情步给持仓玩家重发；`MockClient` 的 ad-hoc 通道列表要登记新载荷的 `TYPE.id()`。
- GameTest：共用账本里池子权益是几十万，小仓位让它转负不现实，ADL 测试直接调 `ServerTrading.deleverage` 传放大的标记价格 + 玩家限定，只断言“权益回升 = 放弃的浮盈”。OI 上限测试在同一 tick 用杠杆 1 的随机 UUID 填充玩家吃掉剩余空间，断言完立即平掉清理，填充按 1.05 x 最新价估值留出点差与滑点。所有开大仓的 GameTest 现在都受上限约束。
- 旧测试 `TradingBookTest.poolPaysAtMostItsBalance` 原靠 10 CRP 池子开仓，现被上限拒绝，改为 `TradingBook.restore` 直接摆仓位。

## 2026-10-29 19:05 强平与风险保障基金（#12）

- 触发直接复用 `MarginMath.assess`（和仓位页同入口同输入），`marginRatio() >= 1.0` 即强平，没有第二套公式；core 测试 `displayedPriceMatchesTrigger*` 证明展示的强平价 ×(1±1e-6) 内不触发、越过即触发。按**标记价格**直接平，无滑点；触发粒度是行情步。
- 清算费 `LIQUIDATION_FEE_RATE = 0.5%` × 名义价值（常量，非合约参数），封顶为剩余权益，**包含在流入基金的金额里**。结算 `TradingBook.settle`：亏损 ≤ 抵押则亏损归池子、剩余全进基金；穿仓则抵押全进池子，缺口先由基金 `payUpTo` 垫付，不够的部分池子“少收”（`poolBorne`），玩家余额永不为负。全仓抵押 = 全部全仓保证金 + 整个可用余额（含挂单冻结），所有全仓仓位一起强平并撤其全部挂单；逐仓只平越线仓位并撤该合约增加敞口的挂单。强平后必须调 `orders.onPositionClosed`，让只减仓 / 止盈止损单以 `POSITION_CLOSED` 失效。
- 基金 `InsuranceFund` 初始 0，存档可选字段 `fund`（旧档缺省 0）；`TradingState` 保留 4/6/7 参兼容构造器。`LiquidationEvent` 是给 #19 的结构化事件。`TradeKind.LIQUIDATION`、`CancelReason.LIQUIDATED` 追加在枚举末尾。
- **GameTest 与活市场 / 活账本的共同坑**（#12、#13、#14、#15 都踩过）：①所有 GameTest 共用一个活市场与账本，别对它调 `ServerMarket.advance`，别让暂停跨 tick，别注入极端价格（会误伤别人的仓位）。②强平钩子上线后，`queueJump(-0.1)` + 10x 杠杆的多头会在同一步被强平，跳空类测试用 `min(2, 上限)`；跳幅实际由模型限定（−0.1 只跳了约 −3.7%）。③要测强平：直接调 `ServerTrading.liquidate(server, secs, markFn, Predicate<UUID>, touched)` 限定玩家，或用 2 倍价格的假快照开仓，真实价格下一步自然触发。④杠杆、手数 = 10^数量精度、委托价都从 `ServerMarket.spec` 与当前价格现算，不假设内置配置（开发运行目录里的旧格式配置不是内置值）；缺省分档下高杠杆只允许小仓位，开大仓用低杠杆。⑤新世界回填 30 天后首个实时步 432000 本身是资金费结算点，在线玩家很快收到 `funding_paid`，**别断言“最后一条消息”**，用 anyMatch。⑥离线玩家 = `MockClient.leave` 后用合成快照调 `ServerTrading.onMarketStep`；聊天消息用 `MockClient.systemMessages` 取，比较翻译键与参数而不是渲染文字。
- 随机守恒测试（`CrossMarginTest`、`OrderBookTest.totalIsConserved`）已覆盖强平与穿仓并断言发生次数 > 0，防止覆盖退化；守恒式 Σ可用 + Σ保证金 + Σ冻结 + 池子 + 基金 恒定。

## 2026-10-29 19:00 离线事件汇总通知（#19）

- core `OfflineSummaries` 只存聚合：玩家 × 事件类型 × 合约的 `count`、`amount`、首末市场秒；`take(player)` 取走并清空。服务端钩子在 `ServerTrading` 的委托事件循环、`settleFunding`、`liquidate`、`deleverage` 处，仅当事件发生时玩家不在线（`getPlayer(uuid) == null`）才记。
- 金额口径必须与历史页明细一致（core 与 GameTest 都用明细合计断言）：强平 = 成交历史强平行的 `realizedPnl`（未封顶，不是 `collateralLost`）；资金费 = `FundingRecord.amount`（玩家视角带符号）；限价成交 = 成交额；止盈止损 = 平仓行 `realizedPnl`；ADL = 成交额（#17）。`TRIGGERED` 与 `CANCELLED` 不记。
- 存档是独立的 `offline_summary.dat`（`OfflineSummarySavedData`），不动 `TradingState`，旧世界即空汇总；类型按名字存档，别重命名已发布的枚举常量。没有新增载荷，通知是一条 `Component.translatable` 聊天消息，键 `…offline_summary.{header,footer,<枚举名小写>}`，加新类型只需加枚举常量与两份语言键。空汇总上线不发消息。
- 没做截图步骤（需玩家下线再上线，单人截图脚本不便），靠 GameTest 断言消息的键与参数。

## 2026-10-29 18:40 资金费结算（#13）

- `TradingBook.settleFunding` 仅当 `secondsUntilFunding()==0`（4800 步整数倍）、费率有限且标记价 > 0 时结算，复用现有费率与倒计时，没有第二套日程。金额 = 标记价 × 手数 × 费率（1e-6 取整），费率为正多头付，对手方是流动性池，总和为零。
- 全仓从可用余额扣，逐仓从该仓位 `margin` 扣，扣到 0 为止；不足部分记 `FundingRecord.shortfall`，不追缴不补欠；池子余额不足时池只付其余额（`payUpTo`），差额同样记 shortfall。逐仓被抽干后由同一步的强平接手。离线玩家照常结算。
- 日程纯按步数，停机不补算；`ServerMarket.advance` 只广播最后一步，被跳过的结算点不结算。`FundingRecord`（每玩家保留 200 条，存档字段可选）同时是给 #19 的结构化事件，`amount` 玩家视角带符号。
- 新开发命令 `/creeper dev market funding <1..30>`（`ServerMarket.advanceToFunding`）精确推进到后续 N 个结算点，截图与手动验证用。

## 2026-10-29 18:20 止盈止损与只减仓（#15）

- 语义对齐币安固定数量单：止盈止损是**固定数量、只减仓**的平仓委托（附带单 = 开仓数量；`PROTECT` 补设 = 载荷数量，留空 = 当时整个仓位）；触发时取 `min(委托数量, 当前仓位)`，实际手数写进历史委托的 `lots`。不做 closePosition 整仓平。
- 只减仓无仓位 → 拒绝 `NO_POSITION`；数量超过仓位则接受并在成交时缩减（永不反向开仓）；仓位消失（全平 / 强平）自动撤单 `POSITION_CLOSED`。同仓位的止盈止损不联动（无 OCO），仓位还有剩余时另一张继续挂着。下单时触发价已会立刻触发 → `INVALID_TRIGGER_PRICE`；基准价：市价单 / `PROTECT` 用触发价来源当前价，限价开仓附带的用限价。
- 触发后按**市价**成交（`TradingBook.fillPrice`，含半个点差与滑点），收 taker 费，等价于同数量同方向市价平仓（`triggerFillEqualsTheSameSizeMarketClose` 证明）；算不出价格时保持挂着。限价平仓 = 只减仓限价单，不冻结资金，按委托价 + maker 费成交。触发价来源 `TriggerSource.LAST` 缺省 / `MARK`。
- 结构：仍是 #14 的 `OrderBook`。`Order` 加第 16 个字段 `extras`：`RecordCodecBuilder` 上限 16、`StreamCodec.composite` 上限 12，所以网络 codec 手写、存档 extras 用 `optionalFieldOf`（旧档照常加载）。再加字段要重构 `Order`。`TradingBook.close` 拆成校验算价 + `closeAt`，资金流同源所以守恒式不变。`OrderEvent` 13 字段，`Kind` 加 `TRIGGERED`（`TRIGGERED` → `FILLED`）；玩家自己平仓引起的失效先入 `OrderBook.deferred`，由 `takeEvents()` 取走。
- 界面踩坑：面板高度很紧，数量框下是“预估 | 止盈止损”共用子页签；限价模式 + 有仓位 + 止盈止损页最拥挤，已按 1280×720 确认不越界。`Order.filled(...)` 要记录实际成交手数，否则缩减后的历史数量不对；测止损用低杠杆，否则跳空会吃光保证金、手续费被截为 0。

## 2026-10-29 18:10 限价委托（#14）

- 价格约定：买单在“最新价 + 半个点差”穿过委托价时触发，卖单反之；检测用 `TradingBook.fillPrice`（按订单手数吃曲线的均价）比较，所以大单要价格更深地越过委托价才触发（不做部分成交）。触发（含跳空）一律按**委托价**成交，收 maker 费 0.02%；下单时已可成交的按当前可成交价成交、收 taker 费 0.05%（走 `open`，同一个 `fillPrice` 入口，#16 曲线自动生效）。冻结金额恰好 = 保证金 + maker 费，不用补扣退差。
- 冻结模型：下单时从 `AccountBook` 取出放进 `Order.frozen`，成交时先存回账户再 `openAt`，撤单直接存回。`Assets.walletBalance` 含冻结，`availableBalance` 不含。成交时再校验开仓条件，不满足以 `FILL_REJECTED` 撤单。每人最多 20 张挂单（`TOO_MANY_ORDERS`），历史 100 条，同一步多张单按 id 顺序依次成交。离线玩家的挂单照常触发。
- 触发粒度是行情步：一步内“穿越又回来”检测不到，`market advance` 跳过的步也不触发。限价挂单成交走的 `openAt` 不带全合约 `markPrice`，其他合约的全仓浮亏按 0 估，只会让全仓玩家限价成交时的可用余额判断偏宽松。
- 网络：`Order` 15 个字段超过 `StreamCodec.composite` 的 12 上限，手写 codec；存档 `last_order_id` / `orders` 用 `optionalFieldOf`。`OrderEvent` 是给 #19 的结构化记录。

## 2026-10-29 18:00 最新成交面板（#18）

- 模拟成交是 core 纯函数 `SimulatedTrades.of(市场种子, 合约, 快照, 上一步最新价)`：随机数由 `MarketRandom.forTrades(种子, 代码, 行情步序号)` 派生，**不碰行情自己的随机数流**，无状态不存档，同一步永远拆出同一批（`doesNotDisturbTheMarketRandomStream` 证明）。把该步成交量拆成 2~8 笔，总和精确等于总手数；买单概率 = 0.5 + 0.35 × tanh(涨跌幅 / 单步波动率)，限制在 [15%, 85%]；上一步价格未知（刚启动、dev 快进后）当持平。
- K 线成交量口径不变（占位值），模拟成交只是把它拆开展示；玩家成交不计入 K 线成交量也不进模拟总和。
- 玩家成交广播挂在源头 `TradingBook.addTradeListener`（在 `record()` 里编号入历史后调用），强平、限价成交只要经过 `record()` 就自动广播；新成交路径若绕过 `record()` 要补调监听。买入判定 `(side == LONG) == (kind == OPEN)`。
- `LatestTradesPayload` 发给**全体在线玩家**（每步所有合约合成一包，玩家成交一笔一包），登录时补发服务端缓冲；服务端 / 客户端每合约环形缓冲 50 笔，不持久化，停服清空。
- 玩家成交的时间戳是下单时快照的市场时间，列表按**到达顺序**排，所以总在更新的模拟批次之下；截图脚本在打开视图后第 36 个 tick 才下单（40 tick 截图），否则玩家成交会被挤出可视范围。英文页签用 "Trades"（"Latest Trades" 撑出 144 宽的栏）。

## 2026-10-29 17:55 全仓与杠杆分档（#11）

- 公式取自币安官方 FAQ。强平价 `LP = (WB − TMM1 + UPNL1 + cum − s·q·EP) / (q·MMR − s·q)`，若落在另一档用那一档的 MMR/cum 重算，取与档位自洽的解（`MarginMath.liquidationPrice` 逐档扫描）。单一来源 `core/trading/MarginMath` + `core/market/MarginTiers`，客户端与服务端共用，强平不得复制公式。保证金率 = 维持保证金 / 保证金余额（币安页面无明确定义），≥ 100% 强平，余额 ≤ 0 记 +∞；全仓所有仓位共用一个保证金率。
- 档位：`ContractSpec.marginTiers`，配置字段 `maintenance_tiers` 可选（缺字段不回写；第一档杠杆必须等于 `max_leverage`），每档 `(上界, 最高杠杆, 维持保证金率)`，速算额自动推出 `cum_k = cum_{k-1} + lower_k × (r_k − r_{k-1})`；校验上界严格递增、杠杆不升、费率不降、费率 × 杠杆 < 1。缺省档位上界 = `DEFAULT_MAX_NOTIONAL`（500,000 CRP）的 1/5/20/50/100%，杠杆 = round(最高 × [1,.8,.4,.16,.08])，费率 = round(0.5/杠杆)。**高杠杆被分档限制成小仓位**：50x 的火药单仓名义价值上限只有 5000 CRP，钻石 125x 第一档上界 5000；开仓超限 `POSITION_LIMIT`，杠杆超合约最高仍是 `INVALID_LEVERAGE`（先判）。
- `Assets` 口径：wallet = 可用 + 全部保证金（+ 冻结）；逐仓未实现盈亏最低记 −保证金，全仓不设下限；**availableBalance = max(0, free + min(0, Σ全仓浮亏))**，全仓浮盈在平仓落袋前不能当可用（比币安保守）。全仓平仓亏损最多亏到“释放保证金 + free”，手续费从 free 垫付。
- 保证金模式按 (玩家, 合约) 存，缺省逐仓；存档只存全仓设置 `margin_modes`，仓位带 `margin_mode`；有仓位或挂单时不能切换（`MARGIN_MODE_LOCKED`）。`adjustLeverage`：杠杆须 ≤ 合约最高和当前名义价值所在档最高（`LEVERAGE_ABOVE_TIER`）；调低杠杆（增保证金）要可用余额够，调高杠杆退保证金时逐仓检查调整后保证金率仍 < 100%（`LEVERAGE_UNSAFE`）。逐仓两个方向都允许（与币安不同），因为没有单独的追加保证金操作。
- 踩坑：`Money.toDisplayString()` 是 `#,##0.00####`，测试里期望的参数串写 "5,000.00" 不是 "5000"；界面保证金列最长 12 位，列宽权重要留够（曾与盈亏列粘连）；Windows 下 Bash 工具会拒绝长 heredoc，脚本先写进文件再跑。

## 2026-10-29 17:40 虚拟流动性曲线（#16）

- `core/market/LiquidityCurve`：买卖盘各无穷多档，每档数量 = 约 500 CRP 名义价值按当前最新价折成整数手（至少 1 手），第 i 档价格 = 第一档 × (1±step)^i（几何级数，卖盘价格不会为负），第一档 = 最新价 ± 半个点差。`depthSlippage` = 名义价值 10,000 CRP 市价单的平均滑点，**相对第一档、不含半个点差**；step = 2·s/19，复利二阶项使实际略大约 0.2%，测试容差 ±10%。
- `ContractSpec.depthSlippage`：内置钻石 0.0005、火药 0.003，缺省 0.1%；配置字段 `depth_slippage` 可选（0 = 无限深，范围 [0, 0.5)），旧配置缺字段走缺省且不改写；8 参构造器保留。
- **唯一成交价入口** `TradingBook.fillPrice(spec, snapshot, side, kind, lots)`，限价 taker 与止盈止损触发都必须调它，不要再用 `lastPrice ± spread/2`。溢出 / 非正返回 NaN，调用方按 `INVALID_PRICE` 拒单。
- 盘口不走网络：`depthSlippage` 随 `TradingParams` 同步，客户端用 `TradingParams.liquidityCurve(lastPrice)` 本地算前 N 档，与服务端同一份代码。右侧栏 `client/MarketColumn`（宽 144，`Page` 枚举追加页签）。
- 老 `TradingBookTest` 大单断言“最新价 ± 半个点差”，改用 `depthSlippage=0` 的“无限深”变体保持手算值，随机守恒测试用有滑点的默认合约。下单面板的预估价 / 保证金 / 手续费仍按最新价算、没接曲线，大单实际成交价会比预估差。
- 踩坑：新语言键别用 `trading.column.*`（成交历史表头占了），盘口用 `trading.book.*` / `trading.sidebar.*`；JSON 重复键不报错而是后者覆盖前者（表头曾显示成“成交价”才发现）——#20 的检查现已拦截。

## 2026-10-29 16:50 市价开平仓与流动性池的设计取舍（#10）

- 钱的流向（方案 A）：`AccountBook` 的余额就是**可用余额**；开仓从中扣保证金与手续费（手续费进池子，保证金存在 `Position` 里），平仓把「释放的保证金 + 已实现盈亏 − 手续费」存回。恒等式：Σ可用余额 + Σ仓位保证金 + 池子余额不变，只有 dev 充值 / 扣除 / 注资能改它（`TradingBookTest` 随机 2000 笔单验证）。钱包 = 可用 + Σ保证金，保证金余额 = 钱包 + 未实现盈亏，可用余额不含逐仓的未实现盈亏。#11 做全仓时可能要改 `Assets.availableBalance`。
- 两处临时处理，等后续票替换：①平仓亏损封顶为被释放的保证金（没有强平，#11 之前亏损不会超过保证金）；②池子兑付盈利最多付它的余额（`payUpTo`，#16 做 ADL 前池子不够就少付），手续费封顶为税前所得。
- 单向持仓：已有仓位时反向的单子被拒（`OPPOSITE_POSITION`），不做对冲净额；同向加仓用加权平均开仓价、保证金累加；单子的杠杆必须等于仓位杠杆（`LEVERAGE_MISMATCH`），所以有仓位时下单面板的杠杆被锁定。部分平仓释放 floor(保证金 × 平仓手数 / 仓位手数)，全平释放剩余的全部。
- 数量是整数「手」（`long`），1 手 = 10^-数量精度 个标的；金额与手续费用 `BigDecimal` 算、HALF_UP 到 1e-6，成交价是 double。保证金 = ceil(名义价值 / 杠杆)，吃单费率 0.05%。点差是合约的绝对价格间隔（缺省起始价的 0.05%）。已存在的配置文件缺新字段时落到缺省（20x、3 位小数），不按内置合约补；要换成内置值需删文件重新生成（开发运行目录 `neoforge/runs/*/config/` 里的就是，截图前删掉 `runs/screenshots` 那份）。
- 持久化：仓位、成交历史、池子存 `data/creepercapital/trading.dat`（`TradingSavedData`），与 `accounts.dat` 分开；两文件之间崩溃的窗口很窄但存在，以后若要原子性要合并或加版本戳。成交历史每人最多留 200 条，同步给客户端最近 100 条。
- 联机：`PROTOCOL_VERSION` 升到 "6"（新增 `OrderPayload`、`TradingStatePayload`、`OrderResultPayload`）；市场暂停时拒绝下单（`market_paused`）；每个行情步服务端给持仓的在线玩家重发资产，客户端用最新标记价格在仓位页实时算未实现盈亏与收益率（收益率 = 盈亏 / 保证金）。
- UI 踩坑：`Screen.extractRenderState` 先画可渲染部件，之后画的面板底色会盖住它，所以数量输入框用 `addWidget` 只登记输入，由 `OrderPanel` 自己在底色之后渲染；输入框有焦点时 K 键不能关界面。面板高度很紧（逐仓、杠杆、数量、4 行预估、持仓行、开多开空、平仓、结果提示），结果提示只画放得下的行。
- 工具坑：bash heredoc 里嵌多引号的 Python 偶尔报 `unexpected EOF`，改成用 Write 工具写文件到临时目录再 `python <文件>` 执行。

## 2026-10-29 14:45 预生成历史的设计取舍（#8）

- `Market.backfill(days)` 就是正常行情步循环（同一模型、同一随机数流），所以与实时行情天然无缝、不用特殊衔接；停在最后一个市场日的最后一个行情步之前（共 `days*14400-1` 步），使 1d K 线开市时恰好 30 根。开市后一个行情步（1 真实秒）就跨日界变 31 根，验收截图里最右一根是极矮的当日线，属正常。
- 必须先 `KlineStore.bind` 再 `backfill`，历史才会随 K 线收盘追加进文件；`ServerMarket` 只在「存档里没有市场」时预生成，`Market.backfill` 对走过行情步的市场抛 `IllegalStateException`，双重防止重复。历史里的跳跃不通知监听者（不发新闻、不刷日志）。
- 实测 30 日两个合约含写文件约 460 ms。预生成后步数从 431999 起，`restart-e2e.ps1` 的 K 线根数断言因此改为带保留上限（1m 7 日、1h 90 日）的公式，并断言重启不重复预生成。
- GameTest 的共享活市场现在带 30 日历史，步数不再从 0 起；GameTest 只能断言 1d 根数在 30~31 之间（运行中会跨日界），精确的 30 根由核心单测 `MarketBackfillTest` 断言。

## 2026-10-29 14:20 逼真行情模型与多合约的设计取舍（#7）

- 指数价格在对数空间演化（恒正）：`ln P = ln F + 慢偏离 + 跳跃偏离`，强度由 `VolatilityProfile` 五个数闭式反推（`IndexModel.Calibration`），不靠调参；统计验收用固定种子 300 个市场日的样本（`IndexModelStatisticsTest`，容差写在断言里）。跳跃用户确认为「部分回吐」：瞬时到位，30% 永久计入基本面价值，其余半衰期 1.5 个市场小时回吐。
- 每个合约有自己的 `MarketRandom` 流：`forContract(市场种子, 交易代码)`，增删合约不改变其他合约走势。`MarketSavedData` 读出后只是状态，要等 `ContractConfig` 读完配置再 `bind(specs)` 恢复成活市场；配置里去掉的合约状态留在存档（休眠），新加的从起始价格开始。#6 及以前的单合约存档用 `Codec.withAlternative` 兼容，读成 DIAMONDCRP。
- 合约配置错误整份回退到内置合约、不覆盖用户的文件（见 ADR-0006）；改配置要重启生效。GameTest 里用 `ContractConfig.load(临时路径)` 测，不碰真实 `config/`。
- 联机载荷（快照、K 线请求 / 历史）都带交易代码，`PROTOCOL_VERSION` 升到 "4"；客户端的合约列表由快照到达顺序推出，不另发包；服务端每个行情步每个合约发一个快照包。
- 所有 GameTest 共用同一个活市场：别对它调 `ServerMarket.advance`，也别让暂停跨 tick；需要推进的测试自己 `new Market(seed, ContractSpec.defaults())`。
- `/creeper dev market jump <合约> <百分比>`（OP）：15 表示 +15%，范围 [-99, 1000]；暂停中排队的跳跃随存档保存，恢复后在第一个行情步发生（`restart-e2e.ps1` 覆盖）。

## 2026-10-29 12:30 K 线的设计取舍（#6）

- 只有「正在走的 1m」随 `MarketState` 存档，已收盘的写世界目录的只追加文件（见 ADR-0005）；启动时 `KlineStore.bind` 先 `discardAfter(存档市场时间)` 再读回，靠行情确定性重放对账，崩溃后也不缺不重。核心单测 `MarketKlinesTest` 覆盖干净重启和崩溃重启。
- 成交量是占位值（`BASE_VOLUME × (1 + |对数收益| / 步波动率)`），不改随机数序列；真成交量接入后要换掉（见 HUM_MEMO）。
- 客户端没有行走线的 API：MC 26.1 的 `GuiGraphicsExtractor` 只有 fill / 水平线 / 竖线，均线要按列补满竖向像素（`KlineChart.extractAverage`）。发包是 `net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer`，不是 `PacketDistributor.sendToServer`。
- `/creeper dev market advance <步数>` 只广播最后一步，客户端发现行情步不连续会重新拉取 K 线；自动截图靠它在 `ScreenshotScript` 里快进 5 个市场日再截不同周期。
- 载荷字段变了（快照多了成交量，行情带 24h 统计）已把 `PROTOCOL_VERSION` 升到 "3"。

## 2026-10-29 12:00 双层定价的设计取舍（#5）

- 资金费率的 P 用币安时间加权均值 (1·P1+…+n·Pn)/(1+…+n)，状态只存样本数和加权和；标记价格里的「最近一期资金费率」取本期实时预估费率（用户确认）。
- 结算点那一步的采样归旧期，下一步才清零开新期；倒计时在结算点显示 00:00:00。基差每市场分钟采样、取最近 30 个。
- 溢价与指数价格共用同一个 `MarketRandom`（存档只多一组定价状态，不多一个随机数状态）；代价是加入溢价后同一种子的指数路径与以前不同，发布前无影响。
- `MarketSnapshotPayload` 字段变化时要升 `ModPayloads.PROTOCOL_VERSION`，否则新旧端不报版本不匹配而是解码失败。

## 2026-10-29 11:20 端到端脚本读服务端日志，不解析 stdout

- `runServer` 的 `standardInput = System.in`（`neoforge/build.gradle`）让控制台能收命令；`runServer` 在 DEBUG 下一次启动输出两万行以上，PowerShell `Register-ObjectEvent` 读 stdout 会丢行（曾误报“跳步”，服务端日志证明实际无误）。断言一律读 `logs/` 下服务端自己写的开发日志，stdout 只用 `CopyToAsync` 落盘防止管道写满。
- 专用服务端空服 60 秒会自动暂停（`pause-when-empty-seconds`），市场随之冻结；e2e 流程都在 60 秒内完成。

## 2026-10-29 10:30 市场持久化的设计取舍（#4）

- 行情随机数用自研 `MarketRandom`（SplitMix64，状态只有一个 long），不用 `java.util.Random`：后者内部状态取不出来，无法随存档保存。Box-Muller 每次消耗两个均匀数、不缓存多余的一个，保证状态完整。
- `MarketSavedData` 持有活的 `Market`，保存时才取 `state()` 编码；`ServerMarket` 用 `storage.get/set` 显式创建（按世界种子），`SavedDataType` 的工厂故意抛异常，别改成 `computeIfAbsent`。
- 暂停状态（`MarketState.paused`）随存档保存，codec 用 `optionalFieldOf("paused", false)` 兼容旧存档；暂停时每 tick 不再标脏，所以 `pause()/resume()` 必须主动 `setDirty`，否则暂停切换不会落盘。单人游戏暂停时 `IntegratedServer.tickServer` 不调用 `super.tickServer`，不会发 `ServerTickEvent`，市场天然冻结。
- 持久化有两层测试：GameTest 用独立临时 `SavedDataStorage` 做真实文件读写；`scripts/restart-e2e.ps1` 真实启停专用服务端三次（暂停重启、恢复后不补算、运行中停服离线后重启不补算）。
- 测试 dev 命令时用 `server.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS)` 模拟普通玩家；暂停必须在同一 tick 内恢复，避免干扰并行的其他 GameTest。

## 2026-10-29 09:47 中文 Windows + 非 ASCII 路径的编码链路

- 仓库位于 `E:\文档\...`。Gradle 按守护进程默认编码写测试 worker 的 @argfile，java 启动器却按本机编码（GBK）读取，classpath 读成乱码，表现为 `ClassNotFoundException`。对策：`gradle.properties` 的 `org.gradle.jvmargs` 加 `-Dfile.encoding=COMPAT`。
- 副作用：由 Gradle 启动的游戏 JVM 继承 GBK 作默认编码。别给 JavaExec 设 `defaultCharacterEncoding = 'UTF-8'`，否则 Gradle 会按 GBK 解码子进程输出，控制台里的中文全部变乱码（已试过并撤回）。
- 所以写文件一律显式指定 UTF-8（例如 `DevLogFile` 的 `PatternLayout.withCharset`），不依赖默认字符集。

## 2026-10-29 09:47 自动化驱动开发客户端

- windows-mcp 的 Shortcut/Type 发出的按键 scancode 为 0，GLFW 收不到。要用 `scripts/sendkey.ps1`（SendInput + 扫描码）发键；鼠标点击和截图用 windows-mcp 即可。
- 进入世界后鼠标被游戏锁定，点标题栏关闭按钮只会转视角，改用 Alt+F4（windows-mcp Shortcut 能用）。
- 主菜单的 “Create Test World” 一键建超平坦测试世界；语言和按键绑定可以直接改 `neoforge/runs/client/options.txt`（`lang:`、`key_key.*`）。

## 2026-10-29 09:47 MC 26.1 与旧教程的 API 差异

- `ResourceLocation` 改名为 `Identifier`。
- 界面渲染改为 `GuiGraphicsExtractor` 配合 `extractRenderState` / `extractBackground`。
- 按键分类是 `KeyMapping.Category(Identifier)`，要在 `RegisterKeyMappingsEvent` 里 `registerCategory`；按键事件类是 `net.minecraft.client.input.KeyEvent`。
- GameTest 的函数注册在 `Registries.TEST_FUNCTION`，再在 `RegisterGameTestsEvent` 里注册环境和测试，写法见 `MarketGameTests`。

## 2026-10-29 10:20 自动截图验收模式的结构

- 代码在 `neoforge/src/dev`（独立 `dev` 源集），只通过 `neoForge.mods` 并入开发运行，不进发布 jar；入口是与主类同 modId 的第二个 `@Mod(dist = CLIENT)`，靠系统属性 `creepercapital.screenshot.dir` 才激活。
- `runScreenshots` 依赖 `prepareScreenshotRun`：每次删除测试世界并重写 `runs/screenshots/options.txt`（中文、`pauseOnLostFocus:false`、跳过无障碍引导），否则无人值守会卡在弹窗或失去焦点暂停。
- dev 代码要访问的客户端类需是 public（`ClientMarket`、`TradingScreen.open()`），这是为开发工具放宽可见性的已知代价。
- 失败（超时、保存出错）用 `Runtime.halt(1)` 以非零退出码结束，让 Gradle 命令失败。
