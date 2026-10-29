# MEMORY

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
