package io.github.djp440.creepercapital.client;

/**
 * 价格相对上一个行情步的涨跌。
 */
enum PriceDirection {
    UP,
    DOWN,
    FLAT;

    static PriceDirection between(double previous, double latest) {
        int comparison = Double.compare(latest, previous);
        if (comparison > 0) {
            return UP;
        }
        return comparison < 0 ? DOWN : FLAT;
    }
}
