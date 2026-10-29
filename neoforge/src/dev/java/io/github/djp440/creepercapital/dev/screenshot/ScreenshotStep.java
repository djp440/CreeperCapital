package io.github.djp440.creepercapital.dev.screenshot;

import java.util.function.Consumer;

import io.github.djp440.creepercapital.client.ClientMarket;
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
        /** 交易界面，下单面板切到限价，委托价填成当前合约最新价的 97%，数量填 2。 */
        LIMIT_ORDER(minecraft -> TradingScreen.openForLimitOrder("ASSETS", "2", limitPrice())),
        /** 交易界面，表格区切到当前委托页。 */
        OPEN_ORDERS(minecraft -> TradingScreen.openForTrading("OPEN_ORDERS", "")),
        /** 交易界面，表格区切到历史委托页。 */
        ORDER_HISTORY(minecraft -> TradingScreen.openForTrading("ORDER_HISTORY", "")),
        /** 交易界面，下单面板切到「止盈止损」页，止盈价填最新价的 105%、止损价填 97%，数量填 2，表格区在资产页。 */
        PROTECTION(minecraft -> TradingScreen.openForProtection("ASSETS", "2", protectionPrice(1.05), protectionPrice(0.97))),
        /** 交易界面，盘口栏切到「最新成交」页（窄屏时图表卡片切到这一页）。 */
        LATEST_TRADES(minecraft -> TradingScreen.openWithLatestTrades()),
        /** 交易界面，盘口栏在「盘口」页（窄屏时图表卡片切到这一页）。 */
        ORDER_BOOK(minecraft -> TradingScreen.openWithOrderBook()),
        /** 交易界面，表格区切到资金费记录页。 */
        FUNDING(minecraft -> TradingScreen.openForTrading("FUNDING", ""));

        private final Consumer<Minecraft> opener;

        View(Consumer<Minecraft> opener) {
            this.opener = opener;
        }

        void open(Minecraft minecraft) {
            opener.accept(minecraft);
        }

        private static String protectionPrice(double factor) {
            return ClientMarket.selectedSymbol().flatMap(ClientMarket::snapshot)
                    .map(snapshot -> String.format(java.util.Locale.ROOT, "%.2f", snapshot.lastPrice() * factor)).orElse("");
        }

        private static String limitPrice() {
            return ClientMarket.selectedSymbol().flatMap(ClientMarket::snapshot)
                    .map(snapshot -> String.format(java.util.Locale.ROOT, "%.2f", snapshot.lastPrice() * 0.97)).orElse("");
        }
    }

    String fileName() {
        return id + ".png";
    }
}
