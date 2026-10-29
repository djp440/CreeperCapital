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
        FUNDING(minecraft -> TradingScreen.openForTrading("FUNDING", "")),
        /** 交易界面，图表放大到每根 K 线 14 像素，并往回看 120 根。 */
        CHART_ZOOMED_IN(minecraft -> TradingScreen.openWithChartView(14, 120, 0)),
        /** 交易界面，图表缩小到每根 K 线 2 像素（最新一根贴右）。 */
        CHART_ZOOMED_OUT(minecraft -> TradingScreen.openWithChartView(2, 0, 0)),
        /** 交易界面，图表缺省缩放、往回看 150 根。 */
        CHART_PANNED(minecraft -> TradingScreen.openWithChartView(7, 150, 0)),
        /** 交易界面，图表拖到缓存里最旧一根（往回看一个很大的数，被夹到边界），显示到头反馈。 */
        CHART_AT_OLDEST(minecraft -> TradingScreen.openWithChartView(7, 1_000_000, 1)),
        /** 交易界面，图表的指标设置浮层展开，第 2 行（MA 25）的色盘展开。 */
        INDICATOR_SETTINGS(minecraft -> TradingScreen.openWithIndicatorSettings(1, -1, null)),
        /** 交易界面，图表的指标设置浮层展开，第 1 行周期输入框里是不合法的 0（显示错误提示）。 */
        INDICATOR_SETTINGS_INVALID(minecraft -> TradingScreen.openWithIndicatorSettings(-1, 0, "0")),
        /** 交易界面，图表上正从持仓线往上拖出止盈线（触发价 = 最新价 × 1.008，还按着鼠标）。 */
        CHART_EDIT_DRAGGING(minecraft -> TradingScreen.openWithChartEdit(1.008, true)),
        /** 交易界面，图表上从持仓线往下拖出的止损线已松开（触发价 = 最新价 × 0.994），tag 上出现 ✓ / ✕。 */
        CHART_EDIT_CONFIRM(minecraft -> TradingScreen.openWithChartEdit(0.994, false));

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
