## 项目结构

NeoForge 26.1.2 模组（Java 25）。两个 Gradle 子项目合成同一个 mod、同一个 jar：

- `core/`：纯 Java 领域逻辑（市场、行情步），按 Java 17 编译，只用 JDK，不引用 Minecraft 与加载器的类。用 JUnit 5 测试。
- `neoforge/`：接入游戏（事件、网络、命令、界面），用 GameTest 测试。

## 构建与验证

- `./gradlew check`：一条命令跑完 core 单元测试、neoforge 的 JUnit（`neoforge/src/test`，中英文语言文件完整性检查）和全部 GameTest。新增语言键须 zh_cn / en_us 成对，漏写会在这里失败。
- `docs/checklist/`：4 级人工验收清单（当前 `v0.1-trading-feel.md`），需要人手感受的验收往里追加。
- `./gradlew :neoforge:runClient` 和 `./gradlew :neoforge:runServer` 启动开发客户端和专用服务端（开发环境跳过 EULA 检查）。
- `./gradlew :neoforge:runScreenshots`：无人值守自动截图验收，产物在仓库根目录 `screenshots/<时间>/`（编号 PNG + `README.md` 说明）。可选参数 `-PscreenshotWidth=… -PscreenshotHeight=… -PscreenshotGuiScale=…`（缺省 1280×720、缩放 2，`0` = 自动）。现为 51 步：01～27 绿涨红跌，28～40 红涨绿跌，41 切回，42～44 可拖拽布局（缺省 / 拖拽后 / 恢复默认），45～48 图表缩放 / 平移 / 到头反馈（#33），49～51 线形图两种涨跌色与切回 K 线（#32）。涨跌色由客户端配置 `config/creepercapital-client.toml` 的 `colorScheme` 决定（`GREEN_UP` 默认 / `RED_UP`）；交易界面可拖拽边框调整区域大小，比例存在同一文件的 `layout*Ratio`（0 = 缺省，标题栏「恢复默认布局」清零），仅标准布局生效、矮屏 / 窄屏降级时忽略。新增界面的验收截图，在 `neoforge/src/dev/.../ScreenshotScript.java` 追加步骤；该代码在 `dev` 源集，不进发布 jar。
- `pwsh -NoProfile -File scripts/restart-e2e.ps1`：端到端重启测试，真实启停专用服务端三次，验证市场存档恢复、K 线、暂停保持、不补算、合约账户余额持久化（约 2 分钟，不在 `check` 里）。**不**覆盖仓位 / 委托 / 基金，这些靠 GameTest 的存档往返。
- 合约由服务端配置文件 `config/creepercapital-contracts.json` 定义（不存在时自动生成 DIAMONDCRP 与 GUNPOWDERCRP，改动重启生效）。OP 用 `/creeper dev market jump <合约> <百分比>` 让合约在下一个行情步跳跃，详见 ADR-0006。
- 合约定义里还有点差 `spread`（绝对价格间隔，缺省为起始价格的 0.05%）、最大杠杆 `max_leverage`、数量精度 `quantity_decimals`，都是可选项；已存在的配置文件缺这些字段时用缺省值（20x、3 位小数），想要新内置合约的值需自己加字段或删掉文件重新生成。另有可选的盘口深度 `depth_slippage`（1 万 CRP 市价单的平均滑点，缺省 0.1%，0 = 无限深）、杠杆分档 `maintenance_tiers`（每档 `up_to` / `max_leverage` / `maintenance_rate`，缺省按最大杠杆自动生成）、持仓总量上限比例 `open_interest_ratio`（占池子权益，缺省 0.5）；同样缺字段时用缺省且不改写文件。清算费率（0.5%）是常量，不在配置里。
- OP 用 `/creeper dev market funding <1..30>` 精确推进到后续 N 个资金费结算点（每 8 个市场小时一次）；`/creeper dev market advance <步数>` 只广播最后一步，会跳过中间步的挂单触发、强平与资金费；另有 `market pause|resume`。
- OP 用 `/creeper dev pool fund <数量>` 给流动性池注资（池子初始 1,000,000 CRP）。
- OP 用 `/creeper dev deposit|withdraw <玩家> <数量>` 给合约账户充值或扣除 CRP（金额为 6 位小数的定点数，离线玩家同样有效）。
- 开发运行的 DEBUG 日志写在仓库根目录 `logs/yyyy-MM-dd HH-mm-ss.log`，每次启动一个文件。

## Agent skills

### Issue tracker

Issue 和 spec 记录在 GitHub Issues（使用 `gh` CLI）。See `docs/agents/issue-tracker.md`.

### Triage labels

使用默认五个 triage 标签（needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix）。See `docs/agents/triage-labels.md`.

### Domain docs

单上下文：根目录一份 `GLOSSARY.md` + `docs/adr/`。See `docs/agents/domain.md`.
