package io.github.djp440.creepercapital.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * 开发环境调试日志：把 DEBUG 及以上的全部日志额外写入 {@code <仓库>/logs/yyyy-MM-dd HH-mm-ss.log}。
 * <p>
 * 只有开发运行配置设置了 {@value #DIR_PROPERTY} 系统属性时才启用；一次启动只建一个文件。
 * 同一秒已有文件（例如客户端和服务端同时启动）时文件名顺延一秒，保证两次启动不共用文件。
 */
public final class DevLogFile {
    static final String DIR_PROPERTY = "creepercapital.devLogDir";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");
    private static final String PATTERN = "[%d{HH:mm:ss.SSS}] [%t/%level] [%logger{1}]: %msg%n";

    private static boolean installed;

    private DevLogFile() {
    }

    public static synchronized void installIfConfigured() {
        String dir = System.getProperty(DIR_PROPERTY);
        if (installed || dir == null || dir.isBlank()) {
            return;
        }
        installed = true;
        try {
            Path file = createUniqueFile(Path.of(dir), LocalDateTime.now());
            attachAppender(file);
            LOGGER.info("开发调试日志写入 {}", file);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("无法创建开发调试日志文件，目录 {}", dir, e);
        }
    }

    private static Path createUniqueFile(Path dir, LocalDateTime startedAt) throws IOException {
        Files.createDirectories(dir);
        LocalDateTime time = startedAt.truncatedTo(ChronoUnit.SECONDS);
        while (true) {
            try {
                return Files.createFile(dir.resolve(FILE_NAME.format(time) + ".log"));
            } catch (FileAlreadyExistsException e) {
                time = time.plusSeconds(1);
            }
        }
    }

    private static void attachAppender(Path file) {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        // 显式 UTF-8：PatternLayout 默认用 JVM 默认字符集，在中文 Windows 上可能是 GBK
        PatternLayout layout = PatternLayout.newBuilder()
                .withPattern(PATTERN)
                .withCharset(StandardCharsets.UTF_8)
                .withConfiguration(config)
                .build();
        FileAppender appender = FileAppender.newBuilder()
                .setName("CreeperCapitalDevLog")
                .withFileName(file.toString())
                .withAppend(true)
                .setLayout(layout)
                .setConfiguration(config)
                .build();
        appender.start();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, Level.DEBUG, null);
        context.updateLoggers();
    }
}
