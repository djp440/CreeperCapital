package io.github.djp440.creepercapital.dev.screenshot;

import java.nio.file.Path;

import io.github.djp440.creepercapital.CreeperCapital;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 自动截图验收模式的入口，仅存在于 dev 源集：不会打进发布的 jar。
 * 只有设置了 {@value #DIR_PROPERTY} 系统属性（{@code ./gradlew :neoforge:runScreenshots}）时才启用。
 */
@Mod(value = CreeperCapital.MOD_ID, dist = Dist.CLIENT)
public final class ScreenshotMode {
    static final String DIR_PROPERTY = "creepercapital.screenshot.dir";

    public ScreenshotMode() {
        String dir = System.getProperty(DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            return;
        }
        ScreenshotRunner runner = new ScreenshotRunner(Path.of(dir));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> runner.onTick(event));
    }
}
