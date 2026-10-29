package io.github.djp440.creepercapital.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.i18n.LangChecks.LangFile;

/** 检查工具自身的单元测试：用合成输入证明 “真有问题时检查会报出来”，否则语言文件检查全绿也不可信。 */
class LangChecksTest {
    @Test
    void parserReportsDuplicateKeysInsteadOfOverwritingSilently() {
        LangFile file = LangChecks.parseLang("{\"a\": \"1\", \"b\": \"2\", \"a\": \"3\"}");
        assertEquals(List.of("a"), file.duplicateKeys());
        assertEquals("3", file.entries().get("a"));
    }

    @Test
    void parserUnderstandsEscapesAndUnicode() {
        LangFile file = LangChecks.parseLang("{\"k\": \"\\\"引号\\\" \\u00b7 \\\\ 100%%\"}");
        assertEquals("\"引号\" · \\ 100%%", file.entries().get("k"));
    }

    @Test
    void parserRejectsMalformedAndNonFlatJson() {
        assertThrows(IllegalArgumentException.class, () -> LangChecks.parseLang("{\"a\": \"1\",}"));
        assertThrows(IllegalArgumentException.class, () -> LangChecks.parseLang("{\"a\": 1}"));
        assertThrows(IllegalArgumentException.class, () -> LangChecks.parseLang("{\"a\": \"1\""));
        assertThrows(IllegalArgumentException.class, () -> LangChecks.parseLang("{\"a\": \"1\"} x"));
        assertThrows(IllegalArgumentException.class, () -> LangChecks.parseLang("{\"a\": {\"b\": \"1\"}}"));
    }

    @Test
    void cjkDetectionCatchesChineseAndFullWidthPunctuation() {
        assertTrue(LangChecks.containsCjk("Cancel 撤单"));
        assertTrue(LangChecks.containsCjk("Price，Size"));
        assertFalse(LangChecks.containsCjk("TP · Last"));
    }

    @Test
    void placeholderIndexesHandleSequentialPositionalAndPercent() {
        assertEquals(Set.of(1, 2), LangChecks.placeholderIndexes("%s and %s"));
        assertEquals(Set.of(2, 3), LangChecks.placeholderIndexes("Funding moved %2$s, short %3$s CRP"));
        assertEquals(Set.of(1), LangChecks.placeholderIndexes("%s%% up"));
        assertEquals(Set.of(), LangChecks.placeholderIndexes("100%% sure"));
    }

    @Test
    void commentsAreStrippedButStringsAreKept() {
        String source = "// \"screen.creepercapital.nope\"\nString a = \"keep // me\"; /* \"message.creepercapital.nope\" */ char c = '\"';\n"
                + "String b = \"x\";";
        List<String> literals = LangChecks.stringLiterals(LangChecks.stripComments(source));
        assertEquals(List.of("keep // me", "x"), literals);
    }

    @Test
    void modKeyDetectionAndConstantPrefixExpansion() {
        assertTrue(LangChecks.looksLikeModKey("screen.creepercapital.trading.title"));
        assertTrue(LangChecks.looksLikeModKey("message.creepercapital.order."));
        assertFalse(LangChecks.looksLikeModKey("creepercapital.screenshot.dir"));
        assertFalse(LangChecks.looksLikeModKey("io.github.djp440.creepercapital"));
        String source = "private static final String KEY_PREFIX = \"message.creepercapital.offline_summary.\";\n"
                + "t(KEY_PREFIX + \"header\"); t(KEY_PREFIX + \"footer\"); t(KEY_PREFIX + type.name());";
        assertEquals(Set.of("message.creepercapital.offline_summary.header", "message.creepercapital.offline_summary.footer"),
                LangChecks.constantPrefixedKeys(LangChecks.stripComments(source)));
    }

    @Test
    void enumConstantsAndHeaderKeysAreExtracted() {
        String enumSource = "public enum E { A, B_C(1), D; boolean f() { return this != A; } }";
        assertEquals(List.of("a", "b_c", "d"), LangChecks.enumConstants(enumSource, "E"));
        String headers = "header(graphics, top, columns, \"time\", \"symbol\",\n \"side\");";
        assertEquals(Set.of("time", "symbol", "side"), LangChecks.headerColumnKeys(headers));
        assertEquals(Set.of("key.category.creepercapital.main"), LangChecks.keyCategoryKeys(
                "new KeyMapping.Category(CreeperCapital.id(\"main\"))", "creepercapital"));
    }
}
