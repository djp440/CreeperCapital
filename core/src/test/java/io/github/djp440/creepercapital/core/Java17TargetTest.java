package io.github.djp440.creepercapital.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.core.market.Market;

class Java17TargetTest {
    private static final int JAVA_17_CLASS_FILE_MAJOR_VERSION = 61;

    @Test
    @DisplayName("core 按 Java 17 编译，将来可直接迁移到 Forge 1.20.1（ADR-0004）")
    void coreClassesTargetJava17() throws IOException {
        try (InputStream in = Market.class.getResourceAsStream("Market.class")) {
            assertNotNull(in);
            DataInputStream data = new DataInputStream(in);
            data.readInt(); // 魔数 0xCAFEBABE
            data.readUnsignedShort(); // 次版本号

            assertEquals(JAVA_17_CLASS_FILE_MAJOR_VERSION, data.readUnsignedShort());
        }
    }
}
