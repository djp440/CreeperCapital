package io.github.djp440.creepercapital.server;

import com.mojang.serialization.Codec;

/** 存档里的枚举一律按常量名（字符串）存，不按序号：以后在中间插入新常量不会让旧存档错位。 */
final class EnumCodecs {
    private EnumCodecs() {
    }

    static <E extends Enum<E>> Codec<E> of(Class<E> type) {
        return Codec.STRING.xmap(name -> Enum.valueOf(type, name), Enum::name);
    }
}
