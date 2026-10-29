package io.github.djp440.creepercapital.dev.screenshot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.djp440.creepercapital.client.ClientMarket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 无人值守的截图流程：标题界面 → 创建固定种子的测试世界 → 等行情到达 → 逐步截图 → 写说明 → 退出。
 */
final class ScreenshotRunner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD_NAME = "ScreenshotWorld";
    private static final long SEED = 20260101L;
    /** 进世界后等待的 tick 数，让区块与行情稳定。 */
    private static final int WORLD_SETTLE_TICKS = 100;
    /** 打开视图后等待的 tick 数，让界面渲染并出现价格变化。 */
    private static final int VIEW_SETTLE_TICKS = 40;
    /** 整个流程的超时 tick 数（约 5 分钟），防止无人值守时卡死。 */
    /** 等标题界面超过这个 tick 数仍未出现时，记录当前界面帮助排查。 */
    private static final int TITLE_WARN_TICKS = 20 * 60;
    private static final int TIMEOUT_TICKS = 20 * 300;

    private enum Phase { WAIT_TITLE, WAIT_WORLD, SETTLE, OPEN_VIEW, CAPTURE, WAIT_SAVE, DONE }

    private final Path outputDir;
    private final List<String> captured = new ArrayList<>();
    private Phase phase = Phase.WAIT_TITLE;
    private int stepIndex;
    /** 当前阶段已等待的 tick 数，每次进入需要等待的阶段前清零。 */
    private int phaseTicks;
    private int totalTicks;
    private volatile boolean saved;
    private volatile String saveError;

    ScreenshotRunner(Path baseDir) {
        this.outputDir = baseDir.resolve(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss").format(LocalDateTime.now()));
    }

    void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (phase == Phase.DONE) {
            return;
        }
        if (++totalTicks > TIMEOUT_TICKS) {
            LOGGER.error("自动截图超时，阶段 {}，已截 {}", phase, captured);
            finish(minecraft, false);
            return;
        }
        switch (phase) {
            case WAIT_TITLE -> {
                if (minecraft.screen instanceof TitleScreen) {
                    createWorld(minecraft);
                    phase = Phase.WAIT_WORLD;
                } else if (totalTicks == TITLE_WARN_TICKS) {
                    LOGGER.warn("自动截图：{} tick 后仍未进入标题界面，当前界面 {}（可能被其它弹窗挡住）",
                            totalTicks, minecraft.screen);
                }
            }
            case WAIT_WORLD -> {
                if (minecraft.player != null && minecraft.level != null && ClientMarket.latest().isPresent()) {
                    phaseTicks = 0;
                    phase = Phase.SETTLE;
                }
            }
            case SETTLE -> {
                if (++phaseTicks >= WORLD_SETTLE_TICKS) {
                    phase = Phase.OPEN_VIEW;
                }
            }
            case OPEN_VIEW -> {
                if (stepIndex >= ScreenshotScript.STEPS.size()) {
                    finish(minecraft, true);
                    return;
                }
                ScreenshotStep step = ScreenshotScript.STEPS.get(stepIndex);
                LOGGER.info("自动截图 {}：打开视图 {}", step.id(), step.view());
                step.prepare().accept(minecraft);
                step.view().open(minecraft);
                phaseTicks = 0;
                phase = Phase.CAPTURE;
            }
            case CAPTURE -> {
                if (++phaseTicks >= VIEW_SETTLE_TICKS) {
                    capture(minecraft, ScreenshotScript.STEPS.get(stepIndex));
                    phase = Phase.WAIT_SAVE;
                }
            }
            case WAIT_SAVE -> {
                if (saveError != null) {
                    LOGGER.error("截图保存失败：{}", saveError);
                    finish(minecraft, false);
                } else if (saved) {
                    captured.add(ScreenshotScript.STEPS.get(stepIndex).id());
                    stepIndex++;
                    phase = Phase.OPEN_VIEW;
                }
            }
            default -> {
            }
        }
    }

    private void createWorld(Minecraft minecraft) {
        LOGGER.info("自动截图：创建固定种子 {} 的测试世界", SEED);
        LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true),
                true, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings,
                new WorldOptions(SEED, false, false), WorldPresets::createFlatWorldDimensions, minecraft.screen);
    }

    private void capture(Minecraft minecraft, ScreenshotStep step) {
        saved = false;
        saveError = null;
        File file = outputDir.resolve(step.fileName()).toFile();
        Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> {
            try (image) {
                Files.createDirectories(outputDir);
                image.writeToFile(file);
                LOGGER.info("自动截图 {} 已保存：{}", step.id(), file);
                saved = true;
            } catch (IOException | RuntimeException e) {
                saveError = String.valueOf(e);
            }
        });
    }

    private void finish(Minecraft minecraft, boolean success) {
        phase = Phase.DONE;
        try {
            ScreenshotManifest.write(outputDir, SEED, success, captured);
        } catch (IOException e) {
            LOGGER.error("无法写入截图说明", e);
        }
        LOGGER.info("自动截图结束：{}，输出目录 {}", success ? "成功" : "失败", outputDir);
        if (success) {
            minecraft.stop();
        } else {
            // 失败时以非零退出码结束，让 Gradle 命令也跟着失败
            Runtime.getRuntime().halt(1);
        }
    }
}
