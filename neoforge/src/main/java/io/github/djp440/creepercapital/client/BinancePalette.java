package io.github.djp440.creepercapital.client;

/**
 * 币安深色主题的界面配色（ARGB）。
 * <p>
 * 涨跌语义（涨 / 买 / 多 / 盈利，跌 / 卖 / 空 / 亏损）一律经 {@link #up()}、{@link #down()} 及其派生方法取色，
 * 它们按客户端配置的 {@link ColorScheme} 在绿、红之间切换；不要在界面代码里直接用 {@link #GREEN}、{@link #RED}。
 */
final class BinancePalette {
    static final int BACKGROUND = 0xFF0B0E11;
    static final int PANEL = 0xFF181A20;
    static final int DIVIDER = 0xFF2B3139;
    static final int TEXT_PRIMARY = 0xFFEAECEF;
    static final int TEXT_SECONDARY = 0xFF848E9C;
    static final int BRAND_YELLOW = 0xFFF0B90B;
    /** 币安的绿、红两种涨跌色，只由 {@link ColorScheme} 引用。 */
    static final int GREEN = 0xFF0ECB81;
    static final int RED = 0xFFF6465D;
    /** 下单失败、本地校验不过等错误提示：与涨跌无关，两种方案下都是红色。 */
    static final int ERROR = 0xFFF6465D;
    /** 鼠标悬停时的底色。 */
    static final int HOVER = 0xFF1E2329;
    /** 按钮底色与悬停色、禁用文字色。 */
    static final int BUTTON = 0xFF2B3139;
    static final int BUTTON_HOVER = 0xFF3C4552;
    static final int DISABLED = 0xFF474D57;
    /** K 线图的网格线，比分隔线更淡。 */
    static final int GRID = 0xFF1E2329;
    /** K 线图的十字线（半透明）。 */
    static final int CROSSHAIR = 0x80848E9C;
    /** 均线 MA(7) / MA(25) / MA(99)。 */
    static final int MA_7 = 0xFFF0B90B;
    static final int MA_25 = 0xFFE05CF0;
    static final int MA_99 = 0xFF4C9BE8;

    private BinancePalette() {
    }

    /** 涨、买入、做多、盈利的颜色。 */
    static int up() {
        return ClientConfig.colorScheme().up();
    }

    /** 跌、卖出、做空、亏损的颜色。 */
    static int down() {
        return ClientConfig.colorScheme().down();
    }

    /** 买入 / 做多取涨色，卖出 / 做空取跌色。 */
    static int side(boolean buyOrLong) {
        return buyOrLong ? up() : down();
    }

    /** 正数取涨色，负数取跌色，零是主文字色（涨跌额、盈亏、资金费等）。 */
    static int signed(double value) {
        return value > 0 ? up() : value < 0 ? down() : TEXT_PRIMARY;
    }

    /** 价格相对上一次的方向：涨、跌或持平（主文字色）。 */
    static int direction(PriceDirection direction) {
        return switch (direction) {
            case UP -> up();
            case DOWN -> down();
            case FLAT -> TEXT_PRIMARY;
        };
    }

    /** 把颜色换成指定的不透明度（0~255），用于盘口深度条、成交量柱等半透明填充。 */
    static int withAlpha(int argb, int alpha) {
        return (alpha & 0xFF) << 24 | (argb & 0x00FFFFFF);
    }
}
