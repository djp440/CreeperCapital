package io.github.djp440.creepercapital.network;

import java.util.HashMap;
import java.util.Map;

import io.github.djp440.creepercapital.CreeperCapital;
import io.github.djp440.creepercapital.core.trading.AdlRanking;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：玩家自己各合约仓位的 ADL 指示灯格数（1 到 5，#17）。灯数随全服排名变化，每个行情步都会变，
 * 所以不夹在只在成交时才发的交易状态里，单独发。没出现的合约（没有仓位、仓位亏损或持平）按最低的 1 格显示。
 *
 * @param lights 交易代码 → 灯数
 */
public record AdlLightsPayload(Map<String, Integer> lights) implements CustomPacketPayload {
    public static final Type<AdlLightsPayload> TYPE = new Type<>(CreeperCapital.id("adl_lights"));

    /** 合约数是个位数，留足余量即可。 */
    private static final int MAX_CONTRACTS = 256;

    private static final StreamCodec<ByteBuf, Map<String, Integer>> LIGHTS = ByteBufCodecs
            .<ByteBuf, String, Integer, Map<String, Integer>>map(HashMap::new, ModCodecs.SYMBOL, ByteBufCodecs.VAR_INT,
                    MAX_CONTRACTS);

    public static final StreamCodec<ByteBuf, AdlLightsPayload> STREAM_CODEC = LIGHTS.map(AdlLightsPayload::new,
            AdlLightsPayload::lights);

    public AdlLightsPayload {
        lights = Map.copyOf(lights);
        for (int value : lights.values()) {
            if (value < 1 || value > AdlRanking.LIGHTS) {
                throw new IllegalArgumentException("指示灯格数必须在 1 到 " + AdlRanking.LIGHTS + " 之间：" + value);
            }
        }
    }

    @Override
    public Type<AdlLightsPayload> type() {
        return TYPE;
    }
}
