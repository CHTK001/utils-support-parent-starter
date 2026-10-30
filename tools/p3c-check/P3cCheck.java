import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * P3C 质量门禁：Alibaba Java Coding Guidelines（黄山版）中可机械校验部分的检查器。
 *
 * <p>只依赖 JDK，可直接单文件运行（JDK 11+）：
 * <pre>{@code
 * java tools/p3c-check/P3cCheck.java            # 检查整个仓库
 * java tools/p3c-check/P3cCheck.java --quiet    # 只输出汇总
 * }</pre>
 *
 * <p>退出码：0 = 全部通过；1 = 存在违规。
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class P3cCheck {

    private static final int MAX_LINE = 120;

    /** 语法不可拆的历史遗留超长行（文件名 -> 原因）。 */
    private static final Map<String, String> LONG_LINE_ALLOW = new LinkedHashMap<>();

    static {
        LONG_LINE_ALLOW.put("AlibabaSmsMessagePush.java", "import 语句不可折行");
        LONG_LINE_ALLOW.put("SystemdServiceManager.java", "lambda 内单条 append 链，含多处引号转义");
    }

    private static final Pattern LOG_CALL = Pattern.compile(
            "^[ \\t]*log\\.(?:trace|debug|info|warn|error)\\s*\\((?<args>[^\\r\\n]*)\\);\\s*$");

    private static final Pattern ANNOTATION_ONLY = Pattern.compile(
            "^@[A-Za-z]\\w*(\\s*\\([^)]*\\))?\\s*$");

    private static final Pattern VOID_METHOD = Pattern.compile(
            "^\\s*(public|private|protected)?\\s*(static\\s+)?(final\\s+)?void\\s+\\w+\\s*\\(");

    private static final Pattern CATCH_EMPTY_SAME_LINE = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}");

    private static final Pattern CATCH_OPEN = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*$");

    private final List<String> violations = new ArrayList<>();

    private final Map<String, Integer> counts = new LinkedHashMap<>();

    private final String[] names = {
            "LONG_LINE", "TRAILING_WS", "TAB_INDENT", "EMPTY_JAVADOC", "ANNOTATION_BEFORE_DOC",
            "VOID_WITH_RETURN", "EMPTY_CATCH", "LOG_PLACEHOLDER", "BOM", "U+FFFD", "MODULE_CYCLE"
    };

    private final String[] labels = {
            "1.3.7 超长行（>120 字符）", "1.3.1 行尾空白", "1.3.1 制表符缩进", "1.8 空 Javadoc",
            "1.8 注解位于 Javadoc 之前", "1.8 void 方法带 @return", "2.1 空 catch",
            "2.4 日志占位符不匹配", "编码 BOM", "编码替换字符", "6.x 模块循环依赖"
    };

    public static void main(String[] args) throws Exception {
        boolean quiet = args.length > 0 && "--quiet".equals(args[0]);
        Path root = Paths.get("").toAbsolutePath();
        P3cCheck check = new P3cCheck();
        for (String key : check.names) {
            check.counts.put(key, 0);
        }
        List<Path> files = check.collectSources(root);
        if (!quiet) {
            System.out.println("扫描 " + files.size() + " 个 Java 文件…");
        }
        for (Path file : files) {
            check.checkFile(file);
        }
        check.checkModuleCycles(root);
        System.exit(check.report(quiet));
    }

    private List<Path> collectSources(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("\\target\\") && !p.toString().contains("/target/"))
                    .collect(Collectors.toList());
        }
    }

    private void add(String key, String where, String detail) {
        counts.merge(key, 1, Integer::sum);
        violations.add(String.format("%-20s %s  %s", key, where, detail));
    }

    private void checkFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            add("BOM", name, "文件含 UTF-8 BOM");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            add("U+FFFD", name, "非合法 UTF-8 编码");
            return;
        }
        if (text.indexOf('\uFFFD') >= 0) {
            add("U+FFFD", name, "含替换字符（编码损坏）");
        }
        String[] lines = text.split("\r?\n", -1);
        boolean[] inTextBlock = markTextBlocks(lines);
        boolean docOpen = false;
        boolean docEmpty = true;
        int docStart = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            boolean masked = inTextBlock[i];

            if (!masked && line.length() > MAX_LINE
                    && !trimmed.startsWith("*") && !trimmed.startsWith("//") && !trimmed.startsWith("/*")
                    && !LONG_LINE_ALLOW.containsKey(name)) {
                add("LONG_LINE", name + ":" + (i + 1), line.length() + " 字符");
            }
            if (!masked && line.matches(".*[ \t]+$")) {
                add("TRAILING_WS", name + ":" + (i + 1), "行尾存在空白");
            }
            if (!masked && line.startsWith("\t")) {
                add("TAB_INDENT", name + ":" + (i + 1), "使用制表符缩进");
            }
            if (trimmed.startsWith("/**")) {
                docOpen = true;
                docEmpty = true;
                docStart = i + 1;
                String rest = trimmed.substring(3);
                if (rest.trim().length() > 0 && !rest.trim().startsWith("*/")) {
                    docEmpty = false;
                }
                if (trimmed.contains("*/")) {
                    docOpen = false;
                }
                continue;
            }
            if (docOpen) {
                if (trimmed.matches("^\\*\\s*\\S.*") && !trimmed.matches("^\\*\\s*@.*")) {
                    docEmpty = false;
                }
                if (trimmed.contains("*/")) {
                    docOpen = false;
                    if (docEmpty) {
                        add("EMPTY_JAVADOC", name + ":" + docStart, "Javadoc 没有任何描述");
                    }
                }
            }
            if (ANNOTATION_ONLY.matcher(trimmed).matches() && i + 1 < lines.length
                    && lines[i + 1].trim().startsWith("/**")) {
                add("ANNOTATION_BEFORE_DOC", name + ":" + (i + 1), "注解位于 Javadoc 之前");
            }
            if (trimmed.contains("@return")) {
                for (int k = i + 1; k <= Math.min(i + 6, lines.length - 1); k++) {
                    String sig = lines[k].trim();
                    if (sig.isEmpty() || sig.startsWith("*") || sig.startsWith("@")) {
                        continue;
                    }
                    if (VOID_METHOD.matcher(sig).find()) {
                        add("VOID_WITH_RETURN", name + ":" + (i + 1), "void 方法标注了 @return");
                    }
                    break;
                }
            }
            if (!masked && !trimmed.contains("\"") && !trimmed.contains("//")
                    && CATCH_EMPTY_SAME_LINE.matcher(trimmed).find()) {
                add("EMPTY_CATCH", name + ":" + (i + 1), "空 catch 且无说明");
            }
            if (!masked && CATCH_OPEN.matcher(trimmed).find() && i + 1 < lines.length
                    && lines[i + 1].trim().equals("}")) {
                add("EMPTY_CATCH", name + ":" + (i + 1), "空 catch 且无说明");
            }
        }
        checkLogPlaceholders(name, lines);
    }

    private boolean[] markTextBlocks(String[] lines) {
        boolean[] mask = new boolean[lines.length];
        boolean inside = false;
        for (int i = 0; i < lines.length; i++) {
            int quotes = count(lines[i], "\"\"\"");
            if (inside) {
                mask[i] = true;
                if (quotes % 2 == 1) {
                    inside = false;
                }
                continue;
            }
            if (quotes % 2 == 1) {
                mask[i] = true;
                inside = true;
            }
        }
        return mask;
    }

    private static int count(String text, String token) {
        int n = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            n++;
            idx += token.length();
        }
        return n;
    }

    private void checkLogPlaceholders(String name, String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            Matcher m = LOG_CALL.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            String args = m.group("args");
            int placeholders = count(args, "{}");
            List<String> parts = splitTopLevel(args);
            if (parts.size() < 2) {
                continue;
            }
            int argCount = parts.size() - 1;
            String last = parts.get(parts.size() - 1);
            if (argCount > placeholders && last.matches("^[A-Za-z_][\\w.]*$")) {
                argCount--;
            }
            if (placeholders != argCount) {
                add("LOG_PLACEHOLDER", name + ":" + (i + 1),
                        "占位符 " + placeholders + " 个，实参 " + argCount + " 个");
            }
        }
    }

    /** 按顶层逗号拆分实参，忽略字符串与括号内的逗号。 */
    private List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0;
        boolean inStr = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inStr) {
                buf.append(ch);
                if (ch == '\\') {
                    i++;
                    if (i < text.length()) {
                        buf.append(text.charAt(i));
                    }
                    continue;
                }
                if (ch == '"') {
                    inStr = false;
                }
                continue;
            }
            if (ch == '"') {
                inStr = true;
                buf.append(ch);
                continue;
            }
            if (ch == '(' || ch == '{' || ch == '[') {
                depth++;
                buf.append(ch);
                continue;
            }
            if (ch == ')' || ch == '}' || ch == ']') {
                depth--;
                buf.append(ch);
                continue;
            }
            if (ch == ',' && depth == 0) {
                parts.add(buf.toString().trim());
                buf.setLength(0);
                continue;
            }
            buf.append(ch);
        }
        String tail = buf.toString().trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private void checkModuleCycles(Path root) throws Exception {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        List<Path> poms;
        try (Stream<Path> stream = Files.walk(root)) {
            poms = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.toString().contains("\\target\\") && !p.toString().contains("/target/"))
                    .collect(Collectors.toList());
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        for (Path pom : poms) {
            Document doc;
            try {
                doc = builder.parse(pom.toFile());
            } catch (Exception e) {
                continue;
            }
            Element project = doc.getDocumentElement();
            String artifactId = textOf(project, "artifactId");
            if (artifactId == null) {
                continue;
            }
            String groupId = textOf(project, "groupId");
            if (groupId == null) {
                Element parent = firstChild(project, "parent");
                groupId = parent == null ? null : textOf(parent, "groupId");
            }
            Set<String> deps = new LinkedHashSet<>();
            Element dependencies = firstChild(project, "dependencies");
            if (dependencies != null) {
                NodeList children = dependencies.getElementsByTagName("dependency");
                for (int i = 0; i < children.getLength(); i++) {
                    Element dep = (Element) children.item(i);
                    String depGroup = textOf(dep, "groupId");
                    if (depGroup == null) {
                        depGroup = groupId;
                    }
                    if (depGroup != null && depGroup.startsWith("com.chua")) {
                        String depArtifact = textOf(dep, "artifactId");
                        if (depArtifact != null) {
                            deps.add(depArtifact);
                        }
                    }
                }
            }
            graph.put(artifactId, deps);
        }
        Map<String, Integer> state = new LinkedHashMap<>();
        for (String node : graph.keySet()) {
            visit(node, graph, state, new ArrayList<>());
        }
    }

    private void visit(String node, Map<String, Set<String>> graph,
                       Map<String, Integer> state, List<String> stack) {
        Integer st = state.get(node);
        if (st != null && st == 1) {
            List<String> loop = new ArrayList<>(stack);
            loop.add(node);
            add("MODULE_CYCLE", node, String.join(" -> ", loop));
            return;
        }
        if (st != null && st == 2) {
            return;
        }
        state.put(node, 1);
        stack.add(node);
        Set<String> deps = graph.get(node);
        if (deps != null) {
            for (String dep : deps) {
                visit(dep, graph, state, stack);
            }
        }
        stack.remove(stack.size() - 1);
        state.put(node, 2);
    }

    private static String textOf(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (node.getParentNode() == parent) {
                return node.getTextContent().trim();
            }
        }
        return null;
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (node instanceof Element && tag.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private int report(boolean quiet) {
        System.out.println();
        System.out.println("================ P3C 质量门禁 ================");
        int total = 0;
        for (int i = 0; i < names.length; i++) {
            int n = counts.getOrDefault(names[i], 0);
            total += n;
            System.out.printf("%-4s %-28s %d%n", n == 0 ? "PASS" : "FAIL", labels[i], n);
        }
        System.out.println("----------------------------------------------");
        if (!quiet) {
            violations.stream().limit(30).forEach(v -> System.out.println("  " + v));
            if (violations.size() > 30) {
                System.out.println("  … 另有 " + (violations.size() - 30) + " 处");
            }
        }
        if (total == 0) {
            System.out.println("结果：全部通过");
            return 0;
        }
        System.out.println("结果：" + total + " 处违规");
        return 1;
    }
}
