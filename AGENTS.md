## 项目结构

NeoForge 26.1.2 模组（Java 25）。两个 Gradle 子项目合成同一个 mod、同一个 jar：

- `core/`：纯 Java 领域逻辑（市场、行情步），按 Java 17 编译，只用 JDK，不引用 Minecraft 与加载器的类。用 JUnit 5 测试。
- `neoforge/`：接入游戏（事件、网络、命令、界面），用 GameTest 测试。

## 构建与验证

- `./gradlew check`：一条命令跑完 core 单元测试和全部 GameTest。
- `./gradlew :neoforge:runClient` 和 `./gradlew :neoforge:runServer` 启动开发客户端和专用服务端（开发环境跳过 EULA 检查）。
- 开发运行的 DEBUG 日志写在仓库根目录 `logs/yyyy-MM-dd HH-mm-ss.log`，每次启动一个文件。

## Agent skills

### Issue tracker

Issue 和 spec 记录在 GitHub Issues（使用 `gh` CLI）。See `docs/agents/issue-tracker.md`.

### Triage labels

使用默认五个 triage 标签（needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix）。See `docs/agents/triage-labels.md`.

### Domain docs

单上下文：根目录一份 `GLOSSARY.md` + `docs/adr/`。See `docs/agents/domain.md`.
