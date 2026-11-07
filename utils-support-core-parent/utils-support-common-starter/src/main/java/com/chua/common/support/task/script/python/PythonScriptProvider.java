package com.chua.common.support.task.script.python;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.script.ScriptHost;
import com.chua.common.support.task.script.ScriptProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Python 脚本引擎：调用本机 python 解释器以子进程方式执行脚本。
 *
 * <p>SPI 类型：{@code python}。为什么不用 Jython / GraalPy 这类嵌入式实现：
 * 它们要么停在 Python 2.7 语义（Jython），要么需要特定 JDK 与原生库（GraalPy），
 * 装到部署机上都是一次额外的兼容性赌注。而「本机装了 python」是运维修剪脚本时
 * 最常见的既有事实，直接用宿主解释器执行，脚本能用 pip 装的三方库，行为与命令行
 * 里跑完全一致。</p>
 *
 * <p>执行模型：本类把脚本正文落盘，再用一个极小的内嵌引导程序
 * （{@code -c} 传入，不额外分发文件）启动子进程，通过 stdin 传入 JSON 上下文、
 * 通过 stdout 取回结果。约定如下：</p>
 * <ul>
 *   <li>上下文里的每个条目都成为脚本的全局变量，同时整体作为 {@code ctx} / {@code context} 可用；</li>
 *   <li>脚本可以定义 {@code execute(ctx)} 或 {@code main(ctx)} 作为入口，返回即结果；</li>
 *   <li>没有入口函数时取脚本里名为 {@code result} 的全局变量；</li>
 *   <li>结果走 JSON，无法序列化的对象按 {@code str()} 降级，不因返回值形态让任务失败。</li>
 * </ul>
 *
 * <p>超时由 {@link CmdExecutors} 统一处理（超时即 {@code destroyForcibly}），
 * 与调度侧「脚本超时要中断」的语义一致。宿主装配：上下文带 {@link ScriptHost} 时，
 * 取到的 Bean 会以 {@code bean_<简单类名>} 的形式一并加进脚本变量，脚本里可直接用。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Spi("python")
public class PythonScriptProvider implements ScriptProvider {

    /**
     * 引擎名
     */
    private static final String ENGINE_NAME = "python";

    /**
     * JSON {\\uXXXX} 转义的四位十六进制长度
     */
    private static final int UNICODE_ESCAPE_LEN = 4;
    /**
     * 十六进制解析基址
     */
    private static final int HEX_RADIX = 16;

    /**
     * 上下文里脚本宿主的键（与其它引擎同名）
     */
    private static final String SCRIPT_HOST_KEY = "scriptHost";

    /**
     * 结果标记：引导程序把 JSON 结果放在这一行前缀之后
     */
    private static final String RESULT_MARKER = "__JOB_RESULT__";

    /**
     * 脚本路径 -> 已校验标记（Python 无编译产物，这里只表示「语法检查过」）
     */
    private static final Map<Path, Boolean> LOADED = new ConcurrentHashMap<>();

    /**
     * 默认超时毫秒（与采集脚本节点默认值对齐）
     */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /**
     * 引导程序：读 stdin 的 JSON 上下文，执行目标脚本，把返回值以 JSON 写回 stdout。
     *
     * <p>刻意内嵌成字符串而不是独立资源文件：部署形态是薄 jar + lib/，
     * 多一个需要随包分发的资源就多一处可能漏拷的地方。</p>
     *
     * <p>全链路只走 ASCII：{@code ensure_ascii=True} 让中文以 {@code \\uXXXX} 转义输出。
     * 子进程的 stdout 由 {@code CmdExecutors} 按 JVM 默认字符集解码，Windows 上默认是 GBK，
     * 若让 Python 直接输出 UTF-8 中文，中文返回值必然乱码——转义成 ASCII 就与字符集无关了。</p>
     */
    private static final String BOOTSTRAP = String.join("\n",
            "import sys, json, traceback",
            "script_path = sys.argv[1]",
            "raw = sys.stdin.read()",
            "ctx = json.loads(raw) if raw.strip() else {}",
            "source = open(script_path, 'r', encoding='utf-8').read()",
            "ns = dict(ctx)",
            "ns['ctx'] = ctx",
            "ns['context'] = ctx",
            "try:",
            "    exec(compile(source, script_path, 'exec'), ns)",
            "    if callable(ns.get('execute')):",
            "        value = ns['execute'](ctx)",
            "    elif callable(ns.get('main')):",
            "        value = ns['main'](ctx)",
            "    else:",
            "        value = ns.get('result')",
            "    sys.stdout.write('__JOB_RESULT__' + json.dumps(value, ensure_ascii=True, default=str))",
            "except Exception:",
            "    sys.stderr.write(traceback.format_exc().encode('ascii', 'backslashreplace').decode('ascii'))",
            "    sys.exit(1)");

    /**
     * 语法检查引导程序：只编译不落盘。
     *
     * <p>不用 {@code python -m py_compile}：那条路会在脚本旁边留下 {@code __pycache__}
     * 与 {@code .pyc}，而调度侧的脚本文件是临时产物、编译成功后就会删掉，残留的
     * {@code __pycache__} 会永远没人回收。</p>
     */
    private static final String SYNTAX_CHECK = String.join("\n",
            "import sys",
            "script_path = sys.argv[1]",
            "source = open(script_path, 'r', encoding='utf-8').read()",
            "try:",
            "    compile(source, script_path, 'exec')",
            "except SyntaxError as e:",
            "    sys.stderr.write(str(e).encode('ascii', 'backslashreplace').decode('ascii'))",
            "    sys.exit(1)");

    /**
     * 获取引擎名称。
     *
     * @return 引擎名 {@code python}
     */
    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    /**
     * 加载脚本：做一次语法检查，不执行。
     *
     * <p>Python 没有可复用的编译产物，能提前发现的问题只有语法错误，因此这里用
     * {@code compile()} 校验（不落盘、不产生 {@code __pycache__}）。</p>
     *
     * <p>解释器不存在时**跳过**而不是报错：调度中心不一定装了所有脚本引擎，
     * 装不装是部署问题，不该让保存失败——与 {@code ScriptGlueHandlers.validate}
     * 对其它引擎的口径一致。真正语法不过才是要挡住的问题。</p>
     *
     * @param scriptPath 脚本路径
     * @return 校验通过（或本机无解释器而跳过）返回 true
     */
    @Override
    public boolean loadScript(Path scriptPath) {
        if (scriptPath == null) {
            throw new IllegalArgumentException("Python 脚本路径不能为 null");
        }
        String python;
        try {
            python = resolvePython();
        } catch (IllegalStateException missing) {
            // 本机没装 Python：保存期校验跳过，执行时再给出可读失败
            return true;
        }
        CmdResult result = CmdExecutors.execute(
                new String[]{python, "-c", SYNTAX_CHECK, scriptPath.toString()},
                DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (result.isTimeout()) {
            throw new IllegalStateException("Python 脚本语法检查超时: " + scriptPath);
        }
        if (!result.isSuccess()) {
            throw new IllegalStateException("Python 脚本语法检查失败: " + scriptPath
                    + "\n" + firstLines(result.getStderr()));
        }
        LOADED.put(scriptPath, Boolean.TRUE);
        return true;
    }

    /**
     * 执行脚本：以子进程方式跑宿主 python 解释器。
     *
     * @param scriptPath 脚本路径
     * @param context    绑定上下文（Map 时逐条作为全局变量）
     * @return 脚本返回值（引导程序以 JSON 传回，这里还原成 Java 对象；无结果时为 null）
     */
    @Override
    public Object executeScript(Path scriptPath, Object context) {
        return executeScript(scriptPath, context, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 执行脚本，可指定超时。
     *
     * @param scriptPath 脚本路径
     * @param context    绑定上下文
     * @param timeoutMs  超时毫秒（≤0 表示用默认值）
     * @return 脚本返回值
     */
    public Object executeScript(Path scriptPath, Object context, long timeoutMs) {
        if (scriptPath == null) {
            throw new IllegalArgumentException("Python 脚本路径不能为 null");
        }
        String python = resolvePython();
        long limit = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        Map<String, Object> bindings = bindingsOf(context);
        String stdin = toJson(bindings);

        CmdResult result = CmdExecutors.execute(
                new String[]{python, "-c", BOOTSTRAP, scriptPath.toString()},
                limit, TimeUnit.MILLISECONDS, null, null, stdin);

        if (result.isTimeout()) {
            throw new IllegalStateException("Python 脚本执行超过 " + limit + "ms，已中断: " + scriptPath);
        }
        if (!result.isSuccess()) {
            throw new IllegalStateException("Python 脚本执行失败（exit=" + result.getExitCode() + "）: "
                    + scriptPath + "\n" + firstLines(result.getStderr()));
        }
        String stdout = result.getStdout();
        int idx = stdout == null ? -1 : stdout.indexOf(RESULT_MARKER);
        if (idx < 0) {
            // 脚本跑完了但没产出结果：调用方拿 null 就行，不当失败
            return null;
        }
        return parseResult(stdout.substring(idx + RESULT_MARKER.length()).trim());
    }

    /**
     * 卸载脚本：Python 侧无编译产物，清掉校验标记即可。
     *
     * @param scriptPath 脚本路径
     */
    @Override
    public void unloadScript(Path scriptPath) {
        if (scriptPath != null) {
            LOADED.remove(scriptPath);
        }
    }

    /**
     * 是否已通过校验。
     *
     * @param scriptPath 脚本路径
     * @return 校验过返回 true
     */
    @Override
    public boolean isLoaded(Path scriptPath) {
        return scriptPath != null && LOADED.containsKey(scriptPath);
    }

    /**
     * 探测本机 python 解释器。
     *
     * <p>依次尝试 {@code python} / {@code python3} / {@code py}：Windows 上通常只有
     * {@code python}，部分 Linux 发行版只有 {@code python3}，两处都要能跑。</p>
     *
     * @return 可用的解释器命令名
     * @throws IllegalStateException 都不可用
     */
    static String resolvePython() {
        String[] candidates = {"python", "python3", "py"};
        StringBuilder tried = new StringBuilder();
        for (String candidate : candidates) {
            CmdResult probe = CmdExecutors.execute(
                    new String[]{candidate, "--version"}, 5_000L, TimeUnit.MILLISECONDS);
            if (probe.isSuccess()) {
                return candidate;
            }
            if (tried.length() > 0) {
                tried.append(", ");
            }
            tried.append(candidate);
        }
        throw new IllegalStateException("未找到可用的 Python 解释器（尝试过: " + tried
                + "），请安装 Python 3 并加入 PATH，或在部署机上把 python 指向可用解释器");
    }

    /**
     * 归一执行上下文为可 JSON 化的绑定表。
     *
     * <p>带 {@link ScriptHost} 时，把能取到的 Bean 也塞进变量（键名 {@code bean_<简单类名>}），
     * 让脚本直接使用宿主能力；取不到就跳过，不阻断执行。</p>
     *
     * @param context 执行上下文
     * @return 绑定表
     */
    private static Map<String, Object> bindingsOf(Object context) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        if (context instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && !SCRIPT_HOST_KEY.equals(key)) {
                    bindings.put(key, entry.getValue());
                }
            }
            Object host = map.get(SCRIPT_HOST_KEY);
            if (host instanceof ScriptHost scriptHost) {
                bindings.put("scriptHost", new BeanBridge(scriptHost));
            }
        } else if (context != null) {
            bindings.put("context", context);
        }
        return bindings;
    }

    /**
     * 把绑定表转成 JSON。
     *
     * <p>无法序列化的值统一降级成 {@code toString()}：执行上下文里常有
     * {@code ApplicationContext} 这类对象，硬失败会让本来能跑的脚本跑不了。</p>
     *
     * @param bindings 绑定表
     * @return JSON 文本
     */
    private static String toJson(Map<String, Object> bindings) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
        }
        return builder.append('}').toString();
    }

    /**
     * JSON 化单个值。
     *
     * @param value 原值
     * @return JSON 片段
     */
    private static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(quote(String.valueOf(entry.getKey()))).append(':').append(value(entry.getValue()));
            }
            return builder.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(value(item));
            }
            return builder.append(']').toString();
        }
        return quote(String.valueOf(value));
    }

    /**
     * JSON 字符串转义。
     *
     * @param text 原文本，允许为 null
     * @return 带引号的 JSON 字符串
     */
    private static String quote(String text) {
        if (text == null) {
            return "\"\"";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    /**
     * 还原引导程序写回的 JSON 结果。
     *
     * <p>只做「标量 / 数组 / 对象」三类的基础解析，够脚本返回值用；解析不了时退回原始文本，
     * 不因为结果形态让任务失败。</p>
     *
     * @param json JSON 文本
     * @return 解析结果
     */
    private static Object parseResult(String json) {
        if (json == null || json.isEmpty() || "null".equals(json)) {
            return null;
        }
        try {
            return new JsonReader(json).read();
        } catch (RuntimeException ex) {
            return json;
        }
    }

    /**
     * 取错误输出的前几行，避免把整个 Python traceback 塞进任务日志。
     *
     * @param stderr 错误输出
     * @return 摘录
     */
    private static String firstLines(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "（无错误输出）";
        }
        String[] lines = stderr.strip().split("\n");
        int count = Math.min(lines.length, 6);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        if (lines.length > count) {
            builder.append("\n…（其余 ").append(lines.length - count).append(" 行已省略）");
        }
        return builder.toString();
    }

    /**
     * 把 {@link ScriptHost} 适配成脚本里可调用的对象。
     *
     * <p>Python 侧只能拿到 JSON 值，取 Bean 这种动作没有意义，因此这个桥接对象在 JSON 化时
     * 会退化成它的字符串形态；保留它是为了让脚本能识别「当前有宿主」这件事，并为
     * 后续按需扩展留出位置。</p>
     */
    private static final class BeanBridge {

        /**
         * 宿主能力
         */
        private final ScriptHost host;

        /**
         * 构造 桥接对象
         *
         * @param host 宿主能力
         */
        private BeanBridge(ScriptHost host) {
            this.host = host;
        }

        @Override
        public String toString() {
            return host == null ? "scriptHost(unavailable)" : "scriptHost(available)";
        }
    }

    /**
     * 极简 JSON 读取器：只服务于脚本返回值，覆盖对象 / 数组 / 字符串 / 数字 / 布尔 / null。
     *
     * <p>不引第三方 JSON 库：本模块是纯 JDK 工具层，返回值解析这点需求不值得加一个依赖。</p>
     */
    private static final class JsonReader {

        /**
         * 待解析文本
         */
        private final String text;

        /**
         * 当前下标
         */
        private int pos;

        /**
         * 构造 读取器
         *
         * @param text 待解析文本
         */
        private JsonReader(String text) {
            this.text = text;
        }

        /**
         * 读取一个值。
         *
         * @return 解析结果
         */
        Object read() {
            Object value = value();
            skipBlank();
            if (pos < text.length()) {
                throw new IllegalStateException("JSON 尾部有多余内容: " + text.substring(pos));
            }
            return value;
        }

        /**
         * 读值。
         *
         * @return 值
         */
        private Object value() {
            skipBlank();
            if (pos >= text.length()) {
                throw new IllegalStateException("JSON 意外结束");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        /**
         * 读对象。
         *
         * @return Map
         */
        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipBlank();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipBlank();
                String key = string();
                skipBlank();
                expect(':');
                map.put(key, value());
                skipBlank();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalStateException("JSON 对象缺少逗号");
                }
            }
        }

        /**
         * 读数组。
         *
         * @return List
         */
        private java.util.List<Object> array() {
            java.util.List<Object> list = new java.util.ArrayList<>();
            pos++;
            skipBlank();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipBlank();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalStateException("JSON 数组缺少逗号");
                }
            }
        }

        /**
         * 读字符串。
         *
         * @return 字符串
         */
        private String string() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c != '\\') {
                    builder.append(c);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        builder.append((char) Integer.parseInt(text.substring(pos, pos + UNICODE_ESCAPE_LEN), HEX_RADIX));
                        pos += UNICODE_ESCAPE_LEN;
                    }
                    default -> throw new IllegalStateException("非法转义: \\" + esc);
                }
            }
            throw new IllegalStateException("JSON 字符串未闭合");
        }

        /**
         * 读数字。
         *
         * @return 数字
         */
        private Object number() {
            int start = pos;
            while (pos < text.length() && "+-.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty()) {
                throw new IllegalStateException("JSON 非法字符: " + text.charAt(start));
            }
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(raw);
            }
        }

        /**
         * 读字面量。
         *
         * @param word  期望字面量
         * @param value 对应值
         * @return 对应值
         */
        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw new IllegalStateException("JSON 非法字面量，期望: " + word);
            }
            pos += word.length();
            return value;
        }

        /**
         * 跳过空白。
         */
        private void skipBlank() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        /**
         * 看下一个字符。
         *
         * @return 字符；到末尾返回 0
         */
        private char peek() {
            return pos < text.length() ? text.charAt(pos) : 0;
        }

        /**
         * 取下一个字符。
         *
         * @return 字符
         */
        private char next() {
            if (pos >= text.length()) {
                throw new IllegalStateException("JSON 意外结束");
            }
            return text.charAt(pos++);
        }

        /**
         * 断言并消费指定字符。
         *
         * @param expected 期望字符
         */
        private void expect(char expected) {
            char actual = next();
            if (actual != expected) {
                throw new IllegalStateException("JSON 期望 '" + expected + "' 实际 '" + actual + "'");
            }
        }
    }

    /**
     * 读取脚本文件内容（供自检与诊断使用）。
     *
     * @param scriptPath 脚本路径
     * @return 正文
     */
    static String readSource(Path scriptPath) {
        try {
            return Files.readString(scriptPath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Python 脚本读取失败: " + scriptPath, ex);
        }
    }
}
