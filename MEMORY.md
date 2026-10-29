# MEMORY

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
