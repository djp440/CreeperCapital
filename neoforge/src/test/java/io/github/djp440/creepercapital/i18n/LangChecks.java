package io.github.djp440.creepercapital.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 语言文件检查用的纯文本工具：只读文件、不依赖 Minecraft 的类，所以能放进普通 JUnit 测试。
 * 这里不用 JSON 库：Gson 之类会静默吞掉重复键（后者覆盖前者），而重复键正是要抓的问题之一。
 */
final class LangChecks {
    private LangChecks() {
    }

    /** 一个语言文件的解析结果：键值（按出现顺序）与重复出现的键。 */
    record LangFile(Map<String, String> entries, List<String> duplicateKeys) {
    }

    // ===== 语言文件解析 =====

    /** 解析 “扁平的 JSON 对象，值全是字符串” 的语言文件；其他形状（嵌套、数组、数字）一律抛异常。 */
    static LangFile parseLang(String json) {
        Parser parser = new Parser(json);
        return parser.parse();
    }

    static LangFile readLang(Path file) throws IOException {
        return parseLang(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            // 去掉可能存在的 BOM
            this.text = text.startsWith("\uFEFF") ? text.substring(1) : text;
        }

        LangFile parse() {
            Map<String, String> entries = new LinkedHashMap<>();
            List<String> duplicates = new ArrayList<>();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
            } else {
                while (true) {
                    skipWhitespace();
                    String key = readString();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    String value = readString();
                    if (entries.containsKey(key)) {
                        duplicates.add(key);
                    }
                    entries.put(key, value);
                    skipWhitespace();
                    char c = next();
                    if (c == '}') {
                        break;
                    }
                    if (c != ',') {
                        throw error("应为 ',' 或 '}'，实际是 '" + c + "'");
                    }
                }
            }
            skipWhitespace();
            if (pos != text.length()) {
                throw error("对象之后还有多余内容");
            }
            return new LangFile(entries, duplicates);
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\n' || c == '\r') {
                    throw error("字符串里不能直接换行");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("\\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("非法转义 \\" + e);
                }
            }
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("文件意外结束");
            }
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw error("应为 '" + expected + "'，实际是 '" + c + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            int line = 1;
            for (int i = 0; i < Math.min(pos, text.length()); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            return new IllegalArgumentException("语言文件第 " + line + " 行附近：" + message);
        }
    }

    // ===== 值的检查 =====

    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9FFF\\uF900-\\uFAFF\\u3000-\\u303F\\uFF00-\\uFFEF]");

    /** 含有中日韩字符或全角标点（英文文案里不该出现）。 */
    static boolean containsCjk(String value) {
        return CJK.matcher(value).find();
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?[\\d.,]*[sdf]");

    /**
     * 文案使用的参数序号集合（1 起）。{@code %s} 按出现顺序依次编号，{@code %2$s} 用显式序号；{@code %%} 不是参数。
     * 中英文语序不同，所以只比较 “用了哪些参数”，不比较出现顺序。
     */
    static Set<Integer> placeholderIndexes(String value) {
        String stripped = value.replace("%%", "");
        Set<Integer> indexes = new TreeSet<>();
        int sequential = 0;
        Matcher m = PLACEHOLDER.matcher(stripped);
        while (m.find()) {
            if (m.group(1) != null) {
                indexes.add(Integer.parseInt(m.group(1)));
            } else {
                indexes.add(++sequential);
            }
        }
        return indexes;
    }

    // ===== Java 源码里引用的翻译键 =====

    /** 去掉行注释与块注释，保留字符串与字符字面量原样（注释里经常写 “message.creepercapital.order.<code>” 之类的说明）。 */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                // 文本块：整体保留，不当作普通字符串（里面不会有翻译键）
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append("\"\"");
                i = end;
            } else if (c == '"' || c == '\'') {
                int start = i;
                i++;
                while (i < n && source.charAt(i) != c) {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(n, i + 1);
                out.append(source, start, i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    static List<String> stringLiterals(String strippedSource) {
        List<String> result = new ArrayList<>();
        Matcher m = STRING_LITERAL.matcher(strippedSource);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    /**
     * 本模组的翻译键：根节点是原版常见的几类，且含有模组 id 一段。
     * 以 “.” 结尾的字面量是拼接用的前缀（后面接枚举名之类），只要求语言文件里至少有一个以它开头的键。
     */
    private static final Pattern MOD_KEY = Pattern.compile(
            "^(?:key|screen|message|commands|gui|item|block|entity|tooltip|itemGroup|subtitles)\\.[a-z0-9_.]*creepercapital[a-z0-9_.]*$");

    static boolean looksLikeModKey(String literal) {
        return MOD_KEY.matcher(literal).matches();
    }

    /** 常量 {@code XXX = "前缀."} 之后以 {@code XXX + "后缀"} 拼接的完整键。 */
    static Set<String> constantPrefixedKeys(String strippedSource) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher decl = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*\\.)\"\\s*;").matcher(strippedSource);
        while (decl.find()) {
            String name = decl.group(1);
            String prefix = decl.group(2);
            if (!looksLikeModKey(prefix)) {
                continue;
            }
            Matcher use = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\+\\s*\"([^\"]+)\"").matcher(strippedSource);
            while (use.find()) {
                keys.add(prefix + use.group(1));
            }
        }
        return keys;
    }

    /** 框架从注册 id 推导出的键：{@code new KeyMapping.Category(CreeperCapital.id("main"))} 对应 {@code key.category.creepercapital.main}。 */
    static Set<String> keyCategoryKeys(String strippedSource, String namespace) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("KeyMapping\\.Category\\(\\s*CreeperCapital\\.id\\(\"(\\w+)\"\\)").matcher(strippedSource);
        while (m.find()) {
            keys.add("key.category." + namespace + "." + m.group(1));
        }
        return keys;
    }

    /** 调用 {@code header(...)} 的字符串实参（表头列键，拼成 {@code screen.creepercapital.trading.column.<键>}）。 */
    static Set<String> headerColumnKeys(String strippedSource) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher call = Pattern.compile("\\bheader\\(graphics,\\s*top,\\s*columns,([^;]*)\\);").matcher(strippedSource);
        while (call.find()) {
            Matcher literal = Pattern.compile("\"([a-z0-9_]+)\"").matcher(call.group(1));
            while (literal.find()) {
                keys.add(literal.group(1));
            }
        }
        return keys;
    }

    // ===== 枚举常量（读 core 源码，避免依赖被测代码本身） =====

    /** 读取 {@code enum 名 { A, B; ... }} 的常量名（按声明顺序，小写）。 */
    static List<String> enumConstants(String strippedSource, String enumName) {
        Matcher m = Pattern.compile("\\benum\\s+" + Pattern.quote(enumName) + "\\s*(?:implements[^{]*)?\\{").matcher(strippedSource);
        if (!m.find()) {
            throw new IllegalArgumentException("找不到枚举 " + enumName);
        }
        int depth = 1;
        int i = m.end();
        StringBuilder head = new StringBuilder();
        while (i < strippedSource.length() && depth > 0) {
            char c = strippedSource.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 1 && c == ';') {
                break;
            }
            if (depth == 1) {
                head.append(c);
            }
            i++;
        }
        List<String> names = new ArrayList<>();
        for (String part : head.toString().split(",")) {
            String name = part.replaceAll("\\([^)]*\\)", "").trim();
            if (!name.isEmpty()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /** 匹配 {@code 驼峰/下划线} 小写蛇形字面量（订单结果代码之类）。 */
    static Set<String> snakeCaseLiterals(String strippedSource) {
        Set<String> result = new TreeSet<>();
        for (String literal : stringLiterals(strippedSource)) {
            if (literal.matches("[a-z]+(_[a-z]+)*")) {
                result.add(literal);
            }
        }
        return result;
    }

    // ===== 文件系统 =====

    static String readSource(Path file) throws IOException {
        return stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
