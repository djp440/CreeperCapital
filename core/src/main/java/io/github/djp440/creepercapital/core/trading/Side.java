package io.github.djp440.creepercapital.core.trading;

/** 仓位方向。 */
public enum Side {
    LONG(1),
    SHORT(-1);

    private final int sign;

    Side(int sign) {
        this.sign = sign;
    }

    /** 多头为 +1，空头为 −1：价格上涨对多头是盈利，对空头是亏损。 */
    public int sign() {
        return sign;
    }

    public Side opposite() {
        return this == LONG ? SHORT : LONG;
    }
}
