package io.github.djp440.creepercapital.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.djp440.creepercapital.i18n.LangChecks.LangFile;

/**
 * 真实语言文件的完整性检查（进入 {@code ./gradlew check}）：
 * <ol>
 *   <li>zh_cn 与 en_us 的键集合完全一致，JSON 合法、无重复键、无空值，参数占位符用法一致，en_us 里没有中文；</li>
 *   <li>Java 源码里引用的翻译键都在语言文件里：字面量键精确匹配，拼接前缀至少匹配到一个键，
 *       由枚举 / 表头 / 结果代码动态拼出的键按 “动态键” 一节逐组展开后逐个检查。</li>
 * </ol>
 * 覆盖不到的范围（运行时完全动态的键、第三方库生成的键）见 {@link #dynamicKeysAreDeclaredHere()} 旁的白名单说明。
 */
class LangFilesTest {
    private static final String NS = "creepercapital";
    private static final String TRADING = "screen.creepercapital.trading.";

    private static Path repoRoot;
    private static LangFile zh;
    private static LangFile en;
    /** 源码相对路径 → 去掉注释后的内容。 */
    private static final Map<String, String> sources = new LinkedHashMap<>();

    @BeforeAll
    static void load() throws IOException {
        // Gradle 的测试进程工作目录是子项目目录（neoforge/），仓库根目录是它的上一级
        Path project = Path.of("").toAbsolutePath();
        repoRoot = Files.isDirectory(project.resolve("core")) ? project : project.getParent();
        assertTrue(Files.isDirectory(repoRoot.resolve("core")) && Files.isDirectory(repoRoot.resolve("neoforge")),
                "找不到仓库根目录，当前目录：" + project);
        Path lang = repoRoot.resolve("neoforge/src/main/resources/assets/" + NS + "/lang");
        zh = LangChecks.readLang(lang.resolve("zh_cn.json"));
        en = LangChecks.readLang(lang.resolve("en_us.json"));
        // 界面 / 服务端 / 开发源集都可能引用键；core 不引用 Minecraft，但枚举要读
        for (String dir : List.of("neoforge/src/main/java", "neoforge/src/dev/java", "core/src/main/java")) {
            Path root = repoRoot.resolve(dir);
            for (Path file : LangChecks.javaFiles(root)) {
                sources.put(repoRoot.relativize(file).toString().replace('\\', '/'), LangChecks.readSource(file));
            }
        }
        assertTrue(sources.size() > 20, "源码读取数量异常：" + sources.size());
    }

    // ===== 语言文件本身 =====

    @Test
    void langFilesHaveNoDuplicateKeys() {
        assertEquals(List.of(), zh.duplicateKeys(), "zh_cn.json 有重复键（后者会静默覆盖前者）");
        assertEquals(List.of(), en.duplicateKeys(), "en_us.json 有重复键（后者会静默覆盖前者）");
    }

    @Test
    void langFilesHaveIdenticalKeySets() {
        Set<String> missingInEn = new TreeSet<>(zh.entries().keySet());
        missingInEn.removeAll(en.entries().keySet());
        Set<String> missingInZh = new TreeSet<>(en.entries().keySet());
        missingInZh.removeAll(zh.entries().keySet());
        assertTrue(missingInEn.isEmpty() && missingInZh.isEmpty(),
                "en_us.json 缺少 " + missingInEn.size() + " 个键：" + missingInEn
                        + "；zh_cn.json 缺少 " + missingInZh.size() + " 个键：" + missingInZh);
    }

    @Test
    void valuesAreNotBlank() {
        List<String> blank = new ArrayList<>();
        for (Map.Entry<String, LangFile> file : Map.of("zh_cn", zh, "en_us", en).entrySet()) {
            file.getValue().entries().forEach((key, value) -> {
                if (value.isBlank()) {
                    blank.add(file.getKey() + ":" + key);
                }
            });
        }
        assertEquals(List.of(), blank.stream().sorted().toList(), "语言文件里有空值");
    }

    @Test
    void englishHasNoChineseLeftOver() {
        List<String> leftovers = new ArrayList<>();
        en.entries().forEach((key, value) -> {
            if (LangChecks.containsCjk(value)) {
                leftovers.add(key + " = " + value);
            }
        });
        assertEquals(List.of(), leftovers, "en_us.json 里留着中文（等同于没翻译）");
    }

    @Test
    void chineseHasSomeChineseUnlessNeutral() {
        // 中文文件里整条没有任何汉字的条目，多半是漏翻译（直接贴了英文）；纯符号 / 缩写的白名单见 NEUTRAL
        List<String> untranslated = new ArrayList<>();
        zh.entries().forEach((key, value) -> {
            if (!LangChecks.containsCjk(value) && !NEUTRAL_IN_ZH.contains(key)) {
                untranslated.add(key + " = " + value);
            }
        });
        assertEquals(List.of(), untranslated, "zh_cn.json 里没有汉字的条目（漏翻译？若确属中英通用写法，加进 NEUTRAL_IN_ZH）");
    }

    /**
     * 中文界面里本来就没有汉字的键：纯格式（只有参数和符号）与币安中文版同样直接写缩写的列名（ADL）。
     * 新增这类键时在这里登记并写明原因，别为了过检查随便塞进来。
     */
    private static final Set<String> NEUTRAL_IN_ZH = Set.of(
            TRADING + "order.holding_short", // 仅 “方向 合约 · 杠杆” 三个参数
            TRADING + "column.adl");         // 列名缩写，币安中文版同样写 ADL

    @Test
    void placeholdersAreConsistentBetweenLanguages() {
        List<String> mismatches = new ArrayList<>();
        zh.entries().forEach((key, zhValue) -> {
            String enValue = en.entries().get(key);
            if (enValue == null) {
                return; // 缺键由另一个测试报告
            }
            Set<Integer> zhArgs = LangChecks.placeholderIndexes(zhValue);
            Set<Integer> enArgs = LangChecks.placeholderIndexes(enValue);
            if (!zhArgs.equals(enArgs)) {
                mismatches.add(key + " zh" + zhArgs + " en" + enArgs);
            }
        });
        assertEquals(List.of(), mismatches, "中英文使用的格式化参数不一致（会导致显示错参数或抛异常）");
    }

    // ===== 源码引用的键 =====

    @Test
    void literalKeysInSourcesExistInBothLanguages() {
        List<String> problems = new ArrayList<>();
        sources.forEach((file, source) -> {
            for (String literal : new TreeSet<>(LangChecks.stringLiterals(source))) {
                if (!LangChecks.looksLikeModKey(literal)) {
                    continue;
                }
                problems.addAll(checkKey(literal, file));
            }
        });
        assertEquals(List.of(), problems, "源码里引用了语言文件没有的翻译键");
    }

    @Test
    void constantPrefixedKeysExist() {
        List<String> problems = new ArrayList<>();
        sources.forEach((file, source) -> {
            for (String key : LangChecks.constantPrefixedKeys(source)) {
                problems.addAll(checkKey(key, file));
            }
        });
        assertEquals(List.of(), problems, "KEY_PREFIX + \"…\" 拼出的键缺失");
    }

    @Test
    void frameworkDerivedKeysExist() {
        List<String> problems = new ArrayList<>();
        sources.forEach((file, source) -> {
            for (String key : LangChecks.keyCategoryKeys(source, NS)) {
                problems.addAll(checkKey(key, file));
            }
        });
        assertEquals(List.of(), problems, "按键分类名缺失");
        long categories = sources.values().stream().mapToLong(s -> LangChecks.keyCategoryKeys(s, NS).size()).sum();
        assertTrue(categories >= 1, "没找到按键分类的注册，检查正则是否失效");
    }

    /**
     * 客户端界面代码里不能硬编码中文文案（要走翻译键）。服务端 / 开发源集 / GameTest 的日志与断言信息允许中文，所以只查 client 目录。
     * 抓不到的：硬编码的英文文案（无法和 “代码、单位、符号” 区分，如 "CRP"、"--"），靠 Component.literal 白名单也太宽，所以不查。
     */
    @Test
    void clientCodeHasNoHardCodedChineseText() {
        List<String> found = new ArrayList<>();
        sources.forEach((file, source) -> {
            if (!file.startsWith("neoforge/src/main/java/io/github/djp440/creepercapital/client/")) {
                return;
            }
            for (String literal : LangChecks.stringLiterals(source)) {
                if (LangChecks.containsCjk(literal)) {
                    found.add(file + "：" + literal);
                }
            }
        });
        assertEquals(List.of(), found, "client 代码里有硬编码的中文字符串，应改为翻译键");
    }

    /**
     * 动态键：源码里 “前缀字面量 + 运行时值” 拼出来的键，这里按拼接规则展开成完整键逐个检查。
     * 展开所依据的枚举 / 表头 / 结果代码直接读源码，所以新增枚举常量或新的结果代码而忘记写翻译会在这里失败。
     *
     * <p>不覆盖的范围（前缀白名单，只能检查 “至少有一个键以它开头”，见 {@link #literalKeysInSourcesExistInBothLanguages}）：
     * 完全由运行时数据决定后缀的键。当前代码里没有这样的键，所有动态后缀都来自上面几类有限集合。
     */
    @Test
    void dynamicKeysAreDeclaredHere() {
        List<String> problems = new ArrayList<>();
        String tradeTables = source("neoforge/src/main/java/io/github/djp440/creepercapital/client/TradeTables.java");
        String serverTrading = source("neoforge/src/main/java/io/github/djp440/creepercapital/server/ServerTrading.java");

        // 成交历史方向列：trade_side.<TradeKind>_<Side>
        List<String> kinds = LangChecks.enumConstants(source(trading("TradeKind")), "TradeKind");
        for (String kind : kinds) {
            for (String side : List.of("long", "short")) {
                problems.addAll(checkKey(TRADING + "trade_side." + kind + "_" + side, "TradeTables(TradeKind × Side)"));
            }
        }
        // 开仓 / 平仓委托行的方向列用 open_ / close_ 前缀，同样属于上面的键集合
        assertTrue(kinds.containsAll(List.of("open", "close")), "TradeKind 里应有 OPEN / CLOSE：" + kinds);

        // 多空列：side.<Side>
        for (String side : LangChecks.enumConstants(source("core/src/main/java/io/github/djp440/creepercapital/core/trading/Side.java"), "Side")) {
            problems.addAll(checkKey(TRADING + "side." + side, "TradeTables(Side)"));
        }

        // 委托类型列：order_type.<OrderType>_<TriggerSource>（触发类）与 limit / limit_reduce
        List<String> sources2 = LangChecks.enumConstants(source(trading("TriggerSource")), "TriggerSource");
        for (String type : LangChecks.enumConstants(source(trading("OrderType")), "OrderType")) {
            if (type.equals("limit")) {
                problems.addAll(checkKey(TRADING + "order_type.limit", "TradeTables(OrderType)"));
                problems.addAll(checkKey(TRADING + "order_type.limit_reduce", "TradeTables(OrderType)"));
            } else {
                for (String triggerSource : sources2) {
                    problems.addAll(checkKey(TRADING + "order_type." + type + "_" + triggerSource, "TradeTables(OrderType × TriggerSource)"));
                }
            }
        }

        // 委托状态列：order_status.<statusKey>，statusKey 是 TradeTables 里那个三元表达式的字面量
        Set<String> statuses = statusKeyLiterals(tradeTables);
        assertTrue(statuses.containsAll(Set.of("filled", "cancelled")), "没读到状态键字面量：" + statuses);
        for (String status : statuses) {
            problems.addAll(checkKey(TRADING + "order_status." + status, "TradeTables(statusKey)"));
        }

        // 表头：column.<键>
        Set<String> columns = LangChecks.headerColumnKeys(tradeTables);
        assertTrue(columns.size() >= 15, "没读到足够的表头列键：" + columns);
        for (String column : columns) {
            problems.addAll(checkKey(TRADING + "column." + column, "TradeTables.header"));
        }

        // 下单结果提示：message.creepercapital.order.<code>。code 来自 TradeResult.Reason 的小写名与 ServerTrading 里的蛇形字面量
        Set<String> codes = new TreeSet<>(LangChecks.snakeCaseLiterals(serverTrading));
        codes.removeAll(NOT_ORDER_CODES);
        LangChecks.enumConstants(source(trading("TradeResult")), "Reason").forEach(codes::add);
        assertTrue(codes.containsAll(Set.of("open_long", "close", "insufficient_balance", "funding_paid")), "没读到结果代码：" + codes);
        for (String code : codes) {
            problems.addAll(checkKey("message.creepercapital.order." + code, "ServerTrading(OrderResultPayload.code)"));
        }

        // 离线汇总：message.creepercapital.offline_summary.<EventType>
        for (String type : LangChecks.enumConstants(source(trading("OfflineSummaries")), "EventType")) {
            problems.addAll(checkKey("message.creepercapital.offline_summary." + type, "ServerOfflineSummary(EventType)"));
        }

        // 涨跌色方案（#20）：界面按钮 color_scheme.<ColorScheme>，模组配置界面 creepercapital.configuration.colorScheme.<ColorScheme>
        String colorScheme = source("neoforge/src/main/java/io/github/djp440/creepercapital/client/ColorScheme.java");
        List<String> schemes = LangChecks.enumConstants(colorScheme, "ColorScheme");
        assertTrue(schemes.containsAll(List.of("green_up", "red_up")), "没读到涨跌色方案：" + schemes);
        for (String scheme : schemes) {
            problems.addAll(checkKey(TRADING + "color_scheme." + scheme, "ColorScheme.label"));
            problems.addAll(checkKey("creepercapital.configuration.colorScheme." + scheme, "ColorScheme.getTranslatedName"));
        }
        // 模组配置界面（NeoForge ConfigurationScreen）按 <模组 id>.configuration.<配置项> 找标题与说明，按配置文件名找分区标题
        for (String key : List.of("creepercapital.configuration.title", "creepercapital.configuration.colorScheme",
                "creepercapital.configuration.colorScheme.tooltip",
                "creepercapital.configuration.section.creepercapital.client.toml",
                "creepercapital.configuration.section.creepercapital.client.toml.title")) {
            problems.addAll(checkKey(key, "ClientConfig（模组配置界面）"));
        }

        assertEquals(List.of(), problems, "动态拼接的键缺失");
    }

    /** ServerTrading 里不是 “下单结果代码” 的蛇形字面量（目前没有；以后出现误报时加在这里并说明用途）。 */
    private static final Set<String> NOT_ORDER_CODES = Set.of();

    // ===== 工具 =====

    private static String trading(String type) {
        return "core/src/main/java/io/github/djp440/creepercapital/core/trading/" + type + ".java";
    }

    private static String source(String path) {
        String text = sources.get(path);
        assertTrue(text != null, "没读到源码：" + path);
        return text;
    }

    private static Set<String> statusKeyLiterals(String tradeTables) {
        int start = tradeTables.indexOf("String statusKey");
        assertTrue(start >= 0, "TradeTables 里找不到 statusKey");
        int end = tradeTables.indexOf(';', start);
        Set<String> result = new TreeSet<>();
        for (String literal : LangChecks.stringLiterals(tradeTables.substring(start, end + 1))) {
            result.add(literal);
        }
        return result;
    }

    /** 检查一个键（或以 “.” 结尾的前缀）是否在两个语言文件里都存在，返回问题描述。 */
    private static List<String> checkKey(String key, String where) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, LangFile> file : Map.of("zh_cn", zh, "en_us", en).entrySet()) {
            Set<String> keys = file.getValue().entries().keySet();
            boolean ok = key.endsWith(".") ? keys.stream().anyMatch(k -> k.startsWith(key)) : keys.contains(key);
            if (!ok) {
                problems.add(file.getKey() + " 缺少 " + (key.endsWith(".") ? "前缀 " : "键 ") + key + "（引用处：" + where + "）");
            }
        }
        return problems;
    }
}
