package io.github.djp440.creepercapital.network;

import io.github.djp440.creepercapital.core.kline.Candle;
import io.github.djp440.creepercapital.core.kline.CandlePeriod;
import io.github.djp440.creepercapital.core.kline.Stats24h;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * core 里的值类型不依赖 MC，它们的网络编解码统一写在适配层。
 */
final class ModCodecs {
    /** 合约的交易代码；长度上限防止恶意客户端发超长字符串。 */
    static final StreamCodec<ByteBuf, String> SYMBOL = ByteBufCodecs.stringUtf8(64);

    static final StreamCodec<ByteBuf, CandlePeriod> PERIOD = ByteBufCodecs.VAR_INT.map(ModCodecs::periodAt, CandlePeriod::ordinal);

    static final StreamCodec<ByteBuf, Candle> CANDLE = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, Candle::start,
            ByteBufCodecs.DOUBLE, Candle::open,
            ByteBufCodecs.DOUBLE, Candle::high,
            ByteBufCodecs.DOUBLE, Candle::low,
            ByteBufCodecs.DOUBLE, Candle::close,
            ByteBufCodecs.DOUBLE, Candle::volume,
            Candle::new);

    static final StreamCodec<ByteBuf, Stats24h> STATS_24H = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Stats24h::open,
            ByteBufCodecs.DOUBLE, Stats24h::high,
            ByteBufCodecs.DOUBLE, Stats24h::low,
            ByteBufCodecs.DOUBLE, Stats24h::close,
            ByteBufCodecs.DOUBLE, Stats24h::volume,
            Stats24h::new);

    private ModCodecs() {
    }

    private static CandlePeriod periodAt(int ordinal) {
        CandlePeriod[] periods = CandlePeriod.values();
        if (ordinal < 0 || ordinal >= periods.length) {
            throw new IllegalArgumentException("未知的 K 线周期编号：" + ordinal);
        }
        return periods[ordinal];
    }
}
