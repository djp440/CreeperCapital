package io.github.djp440.creepercapital.client.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** 交互音效（#40）的接缝：结果码 → 音效，音效总开关与音量；纯逻辑，不加载 Minecraft。 */
class CuesTest {
    private static final String ORDER_KEY_PREFIX = "message.creepercapital.order.";

    // ===== 事件枚举 =====

    @Test
    void everyCueHasToneVolumeAndPitch() {
        for (Cue cue : Cue.values()) {
            assertTrue(cue.baseVolume() > 0 && cue.baseVolume() <= 1, cue + " 音量应在 (0, 1]");
            assertTrue(cue.pitch() >= 0.5f && cue.pitch() <= 2.0f, cue + " 音调应在原版允许的 [0.5, 2.0]");
        }
    }

    @Test
    void successFailureAndWarningAreAllRepresented() {
        Set<Tone> tones = EnumSet.noneOf(Tone.class);
        for (Cue cue : Cue.values()) {
            tones.add(cue.tone());
        }
        assertTrue(tones.containsAll(EnumSet.allOf(Tone.class)), "成功 / 失败 / 警示 / 中性四类音色都应有事件：" + tones);
    }

    @Test
    void toneKeepsTheThreeClassesApart() {
        assertEquals(Tone.SUCCESS, Cue.ORDER_OPENED.tone());
        assertEquals(Tone.SUCCESS, Cue.ORDER_CLOSED.tone());
        assertEquals(Tone.SUCCESS, Cue.ORDER_PLACED.tone());
        assertEquals(Tone.SUCCESS, Cue.ORDER_CANCELLED.tone());
        assertEquals(Tone.SUCCESS, Cue.TAKE_PROFIT.tone());
        assertEquals(Tone.FAILURE, Cue.REJECTED.tone());
        assertEquals(Tone.WARNING, Cue.STOP_LOSS.tone());
        assertEquals(Tone.WARNING, Cue.LIQUIDATED.tone());
        assertEquals(Tone.WARNING, Cue.AUTO_DELEVERAGED.tone());
        assertEquals(Tone.NEUTRAL, Cue.BUTTON_CLICK.tone());
    }

    // ===== 结果码 → 事件 =====

    @Test
    void resultCodesMapToTheirCues() {
        assertEquals(Optional.of(Cue.ORDER_OPENED), ResultCues.forResult(true, "open_long"));
        assertEquals(Optional.of(Cue.ORDER_OPENED), ResultCues.forResult(true, "open_short"));
        assertEquals(Optional.of(Cue.ORDER_CLOSED), ResultCues.forResult(true, "close"));
        assertEquals(Optional.of(Cue.ORDER_PLACED), ResultCues.forResult(true, "limit_placed"));
        assertEquals(Optional.of(Cue.ORDER_PLACED), ResultCues.forResult(true, "protection_placed"));
        assertEquals(Optional.of(Cue.ORDER_CANCELLED), ResultCues.forResult(true, "order_cancelled"));
        assertEquals(Optional.of(Cue.LIMIT_FILLED), ResultCues.forResult(true, "limit_filled"));
        assertEquals(Optional.of(Cue.TAKE_PROFIT), ResultCues.forResult(true, "take_profit_filled"));
        assertEquals(Optional.of(Cue.STOP_LOSS), ResultCues.forResult(true, "stop_loss_filled"));
        assertEquals(Optional.of(Cue.LIQUIDATED), ResultCues.forResult(false, "liquidated"));
        assertEquals(Optional.of(Cue.AUTO_DELEVERAGED), ResultCues.forResult(false, "auto_deleveraged"));
        assertEquals(Optional.of(Cue.ORDER_VOIDED), ResultCues.forResult(false, "order_cancelled_rejected"));
        assertEquals(Optional.of(Cue.REJECTED), ResultCues.forResult(false, "insufficient_balance"));
        assertEquals(Optional.of(Cue.REJECTED), ResultCues.forResult(false, "market_paused"));
    }

    @Test
    void settingsChangesFundingAndLiquidationCleanupStaySilent() {
        for (String code : Set.of("set_cross", "set_isolated", "set_leverage", "funding_paid", "funding_received",
                "funding_partial", "order_cancelled_liquidated")) {
            assertEquals(Optional.empty(), ResultCues.forResult(true, code), code);
            assertEquals(Optional.empty(), ResultCues.forResult(false, code), code);
        }
    }

    @Test
    void unknownCodesFallBackOnTheSuccessFlag() {
        assertEquals(Optional.of(Cue.REJECTED), ResultCues.forResult(false, "some_future_rejection"));
        assertEquals(Optional.empty(), ResultCues.forResult(true, "some_future_notice"));
    }

    /** 语言文件里每个下单结果提示键对应一个结果码：每个码都必须在映射表、静音表或拒单表里明确登记，新增结果码会在这里提醒定音效。 */
    @Test
    void everyOrderResultCodeInTheLangFileIsClassified() throws IOException {
        Set<String> classified = new HashSet<>(ResultCues.MAPPED.keySet());
        classified.addAll(ResultCues.SILENT);
        classified.addAll(ResultCues.REJECTIONS);

        Set<String> unclassified = new TreeSet<>(langOrderCodes());
        unclassified.removeAll(classified);
        assertEquals(Set.of(), unclassified, "这些结果码还没决定音效（加到 ResultCues 的映射表、静音表或拒单表）");

        Set<String> stale = new TreeSet<>(classified);
        stale.removeAll(langOrderCodes());
        assertEquals(Set.of(), stale, "这些结果码已不在语言文件里（从 ResultCues 删掉）");
    }

    @Test
    void theThreeTablesDoNotOverlapAndAreSelfConsistent() {
        Set<String> seen = new HashSet<>();
        for (String code : ResultCues.MAPPED.keySet()) {
            assertTrue(seen.add(code), "重复登记：" + code);
        }
        for (String code : ResultCues.SILENT) {
            assertTrue(seen.add(code), "重复登记：" + code);
            assertEquals(Optional.empty(), ResultCues.forResult(true, code), code);
            assertEquals(Optional.empty(), ResultCues.forResult(false, code), code);
        }
        for (String code : ResultCues.REJECTIONS) {
            assertTrue(seen.add(code), "重复登记：" + code);
            assertEquals(Optional.of(Cue.REJECTED), ResultCues.forResult(false, code), code);
        }
        ResultCues.MAPPED.forEach((code, cue) ->
                assertTrue(ResultCues.forResult(true, code).equals(Optional.of(cue)) || ResultCues.forResult(false, code).equals(Optional.of(cue)),
                        code + " 应映射到 " + cue));
    }

    // ===== 总开关与音量 =====

    @Test
    void disabledMeansSilent() {
        for (Cue cue : Cue.values()) {
            assertEquals(0f, CueVolume.of(false, 1.0, cue), cue.name());
        }
    }

    @Test
    void volumeScalesTheCuesBaseVolume() {
        for (Cue cue : Cue.values()) {
            assertEquals(cue.baseVolume(), CueVolume.of(true, 1.0, cue), 1e-6, cue.name());
            assertEquals(cue.baseVolume() * 0.5f, CueVolume.of(true, 0.5, cue), 1e-6, cue.name());
            assertEquals(0f, CueVolume.of(true, 0.0, cue), cue.name());
        }
    }

    @Test
    void outOfRangeVolumeIsClamped() {
        Cue cue = Cue.BUTTON_CLICK;
        assertEquals(cue.baseVolume(), CueVolume.of(true, 5.0, cue), 1e-6);
        assertEquals(0f, CueVolume.of(true, -1.0, cue));
        assertEquals(0f, CueVolume.of(true, Double.NaN, cue));
    }

    @Test
    void audibleOnlyWhenEnabledAndNotZero() {
        assertTrue(CueVolume.audible(CueVolume.of(true, 0.3, Cue.REJECTED)));
        assertFalse(CueVolume.audible(CueVolume.of(true, 0.0, Cue.REJECTED)));
        assertFalse(CueVolume.audible(CueVolume.of(false, 1.0, Cue.REJECTED)));
    }

    // ===== 工具 =====

    private static Set<String> langOrderCodes() throws IOException {
        Path project = Path.of("").toAbsolutePath();
        Path root = Files.isDirectory(project.resolve("core")) ? project : project.getParent();
        Path lang = root.resolve("neoforge/src/main/resources/assets/creepercapital/lang/en_us.json");
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(ORDER_KEY_PREFIX) + "([a-z_]+)\"\\s*:")
                .matcher(Files.readString(lang));
        Set<String> codes = new TreeSet<>();
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        assertTrue(codes.size() > 30, "没读到结果码：" + codes);
        return codes;
    }
}
