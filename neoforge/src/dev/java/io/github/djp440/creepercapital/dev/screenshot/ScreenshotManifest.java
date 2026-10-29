package io.github.djp440.creepercapital.dev.screenshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;

/** 截图说明文件：编号、内容、预期效果，可直接随截图推送做人工评审。 */
final class ScreenshotManifest {
    private ScreenshotManifest() {
    }

    static void write(Path outputDir, long seed, boolean success, Collection<String> capturedIds) throws IOException {
        StringBuilder md = new StringBuilder("# 自动截图验收\n\n");
        md.append("- 时间：").append(LocalDateTime.now().withNano(0)).append('\n');
        md.append("- 世界：固定种子 ").append(seed).append(" 的超平坦创造世界\n");
        md.append("- 结果：").append(success ? "全部截图完成" : "未完成，已截 " + capturedIds).append("\n\n");
        for (ScreenshotStep step : ScreenshotScript.STEPS) {
            md.append("## ").append(step.id()).append(' ').append(step.title()).append("\n\n");
            md.append("- 文件：").append(capturedIds.contains(step.id()) ? step.fileName() : "（未生成）").append('\n');
            md.append("- 预期效果：").append(step.expected()).append("\n\n");
        }
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("README.md"), md, StandardCharsets.UTF_8);
    }
}
