package io.github.djp440.creepercapital.dev.screenshot;

import java.util.function.Consumer;

import io.github.djp440.creepercapital.client.TradingScreen;
import net.minecraft.client.Minecraft;

/**
 * 截图脚本里的一步：先做准备动作（例如把行情快进、切换周期），再打开一个视图，等界面稳定后截图。
 *
 * @param id       截图编号，同时是文件名前缀，例如 {@code 01}
 * @param title    截图内容的简述
 * @param expected 预期效果，验收时对照
 * @param view     截图前要打开的视图
 * @param prepare  打开视图之前执行的准备动作，在客户端主线程上运行
 */
public record ScreenshotStep(String id, String title, String expected, View view, Consumer<Minecraft> prepare) {

    public ScreenshotStep(String id, String title, String expected, View view) {
        this(id, title, expected, view, minecraft -> {
        });
    }

    /** 可被截图的视图。新界面加入后在这里追加一个枚举值即可。 */
    public enum View {
        /** 交易界面。 */
        TRADING(minecraft -> TradingScreen.open()),
        /** 交易界面，且合约下拉列表已展开。 */
        CONTRACT_MENU(minecraft -> TradingScreen.openWithContractMenu()),
        /** 交易界面，表格区在资产页，下单面板的数量框里填了 2。 */
        ORDER_PANEL(minecraft -> TradingScreen.openForTrading("ASSETS", "2")),
        /** 交易界面，表格区切到仓位页。 */
        POSITIONS(minecraft -> TradingScreen.openForTrading("POSITIONS", "")),
        /** 交易界面，表格区切到成交历史页。 */
        TRADES(minecraft -> TradingScreen.openForTrading("TRADES", "")),
        /** 交易界面，右侧栏切到「最新成交」页。 */
        LATEST_TRADES(minecraft -> TradingScreen.openWithLatestTrades());

        private final Consumer<Minecraft> opener;

        View(Consumer<Minecraft> opener) {
            this.opener = opener;
        }

        void open(Minecraft minecraft) {
            opener.accept(minecraft);
        }
    }

    String fileName() {
        return id + ".png";
    }
}
