package com.chua.hprof.support.mcp;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.hprof.support.action.HprofActionPlanner;
import com.chua.hprof.support.ai.HprofAiSummarizer;
import com.chua.hprof.support.analyzer.HprofAnalyzer;
import com.chua.hprof.support.analyzer.HprofAnalyzer.HprofAnalysis;
import com.chua.hprof.support.analyzer.HprofAnalyzer.HprofFinding;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.parser.HprofParser;
import com.chua.hprof.support.serializer.HprofToJsonSerializer;
import com.chua.hprof.support.serializer.HprofToHtmlSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HPROF 堆转储的 MCP 提供者。
 *
 * <p>把 hprof 分析链路注册为 MCP 工具，使 LLM 智能体可以直接
 * 请求堆转储分析：</p>
 *
 * <pre>
 * hprof binary
 *   | Java parse (GridKit hprof-heap, NetBeans PerfLib backend)
 * Java objects / structured data
 *   | serialize (Jackson / hand-written template)
 * JSON / HTML
 *   | hand to AI
 * AI gives a plain-language conclusion
 * </pre>
 *
 * <p>实现 {@link McpProvider} SPI，因此可通过
 * {@code ServiceProvider.of(McpProvider.class).getExtension("hprof")} 被发现。工具集
 * 与 {@code SkillsmpMcpProvider} / {@code TimeMcpProvider} 保持一致。</p>
 *
 * <h2>Tools</h2>
 * <ul>
 *   <li>{@code hprof_parse_to_json} - 解析 hprof 文件并返回 JSON 文档</li>
 *   <li>{@code hprof_parse_to_html} - 解析 hprof 文件并返回自包含的 HTML
 *       报告（图表 + 结论 + 可选的 AI 摘要）</li>
 *   <li>{@code hprof_top_retained} - 解析 hprof 文件并返回 retained 占用最高的类</li>
 *   <li>{@code hprof_diagnose} - 解析 hprof 文件并返回简明的诊断卡：
 *       一句话结论、算法判定的根因、支撑证据以及
 *       按优先级排列的解决方案（纯文本，无需解析 HTML / JSON）</li>
 * </ul>
 *
 * <h2>可选的 AI 摘要</h2>
 *
 * <p>本提供者持有一个可选的 {@link ChatClient}（通过
 * {@link #setChatClient} 或 {@link #setChatClientSetting} 设置）。配置后，
 * {@code hprof_parse_to_html} 工具会在报告中内嵌一段 AI 摘要区块；
 * 未配置时该区块直接省略。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("hprof")
public class HprofMcpProvider implements McpProvider {

    /**
     * Provider name.
     */
    public static final String NAME = "hprof";

    /**
     * Tool input parameter name.
     */
    private static final String PARAM_PATH = "path";

    /**
     * 可选的 AI 聊天客户端，用于生成 HTML 报告中的 AI 摘要区块。
     */
    private volatile ChatClient chatClient;

    /**
     * Set the optional AI chat client.
     *
     * @param chatClient AI client, null clears
     * @return this provider for chaining
     */
    public HprofMcpProvider setChatClient(ChatClient chatClient) {
        this.chatClient = chatClient;
        return this;
    }

    /**
     * 通过配置项设置可选的 AI 聊天客户端。
     *
     * @param setting AI client setting, null clears
     * @return this provider for chaining
     */
    public HprofMcpProvider setChatClientSetting(ChatClientSetting setting) {
        this.chatClient = setting == null ? null : ChatClient.create(setting);
        return this;
    }

    @Override
    /**
     * Provider name
    */
    public String name() {
        return NAME;
    }

    @Override
    /**
     * Create client
    */
    public McpClient create() {
        return new HprofMcpClient();
    }

    @Override
    /**
     * List available
    */
    public List<String> listAvailable() {
        return List.of(NAME);
    }

    /**
     * HPROF MCP client, in-JVM implementation of tool listing and invocation.
     *
     * @author CH
     * @since 4.0.0.42
     */
    protected class HprofMcpClient implements McpClient {

        /**
         * Initialized flag.
         */
        private volatile boolean initialized = false;

        @Override
        /**
         * Init
        */
        public void init() {
            initialized = true;
            log.info("[hprof] MCP client initialized");
        }

        @Override
        /**
         * List tools
        */
        public List<McpToolDescriptor> listTools() {
            return toolDescriptors();
        }

        @Override
        /**
         * Call tool
        */
        public McpToolResult callTool(McpToolCall toolCall) {
            try {
                String toolName = toolCall.getToolName();
                Map<String, Object> args = toolCall.getArguments() == null
                        ? Map.of()
                        : toolCall.getArguments();
                switch (toolName) {
                    case "hprof_parse_to_json" -> {
                        return McpToolResult.success(
                                HprofToJsonSerializer.serialize(
                                        parse(stringArg(args)), stringArg(args)));
                    }
                    case "hprof_parse_to_html" -> {
                        String path = stringArg(args);
                        HprofParser.Result result = parse(path);
                        HprofAiSummarizer summarizer = HprofAiSummarizer.of(chatClient);
                        String aiSummary = summarizer.isEnabled() ? summarizer.summarize(result) : null;
                        return McpToolResult.success(
                                HprofToHtmlSerializer.serialize(result, path, aiSummary));
                    }
                    case "hprof_top_retained" -> {
                        return McpToolResult.success(topRetained(stringArg(args)));
                    }
                    case "hprof_diagnose" -> {
                        String path = stringArg(args);
                        return McpToolResult.success(diagnose(parse(path), path));
                    }
                    default -> {
                        return McpToolResult.error("unknown tool: " + toolName);
                    }
                }
            } catch (Exception e) {
                log.error("[hprof] tool call failed", e);
                return McpToolResult.error("call failed: " + e.getMessage());
            }
        }

        @Override
        /**
         * Is initialized
        */
        public boolean isInitialized() {
            return initialized;
        }
    }

    /**
     * hprof 分析工具的工具描述符列表。
     *
     * @return the tool descriptor list
     */
    public static List<McpToolDescriptor> toolDescriptors() {
        Map<String, Object> pathSchema = Map.of(
                "type", "object",
                "properties", Map.of(PARAM_PATH,
                        Map.of("type", "string", "description", "hprof file path")),
                "required", List.of(PARAM_PATH));
        Map<String, Object> htmlSchema = Map.of(
                "type", "object",
                "properties", Map.of(PARAM_PATH,
                        Map.of("type", "string", "description", "hprof file path")),
                "required", List.of(PARAM_PATH));
        return List.of(
                new McpToolDescriptor("hprof_parse_to_json",
                        "Parse an hprof heap dump file and return the JSON analysis document.",
                        pathSchema),
                new McpToolDescriptor("hprof_parse_to_html",
                        "Parse an hprof heap dump file and return a self-contained HTML report "
                                + "with memory-usage ranking charts, GC-root distribution, "
                                + "algorithmic leak findings, conclusions and an optional AI summary.",
                        htmlSchema),
                new McpToolDescriptor("hprof_top_retained",
                        "Parse an hprof heap dump file and return the top retained classes.",
                        pathSchema),
                new McpToolDescriptor("hprof_diagnose",
                        "Parse an hprof heap dump file and return a concise plain-text diagnosis "
                                + "card: a one-line OOM conclusion, the algorithmic root cause "
                                + "(crash verdict / primary holder / mechanisms), the supporting "
                                + "evidence, and a prioritized solution plan.",
                        pathSchema));
    }

    /**
     * Parse the hprof file referenced by the argument map.
     *
     * @param args tool arguments
     * @return parsed result
     * @throws IOException 无法读取该文件时
     */
    private HprofParser.Result parse(Map<String, Object> args) throws IOException {
        return parse(stringArg(args));
    }

    /**
     * Parse the hprof file at the given path.
     *
     * @param path hprof file path
     * @return parsed result
     * @throws IOException 无法读取该文件时
     */
    private HprofParser.Result parse(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            throw new IOException("hprof file not found: " + path);
        }
        return HprofParser.parse(file);
    }

    /**
     * Build the top-retained report text.
     *
     * @param path hprof file path
     * @return report text
     * @throws IOException 无法读取该文件时
     */
    private String topRetained(String path) throws IOException {
        HprofParser.Result result = parse(path);
        StringBuilder sb = new StringBuilder();
        sb.append("Top retained objects in ").append(path).append('\n');
        for (var o : result.topRetained()) {
            sb.append(o.getClassName())
                    .append(" | retained ").append(o.getRetainedSizeText())
                    .append(" | root ").append(o.getGcRoot() == null ? "unknown" : o.getGcRoot())
                    .append('\n');
        }
        sb.append("\nGC roots:\n");
        for (String root : result.gcRoots()) {
            sb.append("- ").append(root).append('\n');
        }
        return sb.toString();
    }

    /**
     * 生成简洁的诊断卡（纯文本）：一句话结论 + 根因 + 证据 + 按优先级的解决方案。
     *
     * <p>复用 {@link HprofAnalyzer} 的结构化根因与 {@link HprofActionPlanner}
     * 的处置计划，让 LLM agent 一次调用即可读到清晰的"为什么 / 怎么办"，
     * 无需解析整页 HTML 或大 JSON。离线安全：全部来自规则引擎。</p>
     *
     * @param result   解析结果
     * @param fileName hprof 文件路径（用于崩溃语境探测，可为 null）
     * @return 诊断卡文本
     */
    public static String diagnose(HprofParser.Result result, String fileName) {
        HprofAnalysis analysis = HprofAnalyzer.analyze(result,
                fileName == null ? null : new File(fileName));
        HprofActionPlanner.ActionPlan plan = HprofActionPlanner.plan(result, analysis);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("══ HPROF 内存诊断 · ")
                .append(fileName == null ? "stream" : fileName).append(" ══\n\n");

        sb.append("【一句话结论】").append(analysis.rootCauseHeadline).append("\n\n");

        sb.append("【问题原因（根因判定）】\n");
        if (analysis.rootCauseSections != null && !analysis.rootCauseSections.isEmpty()) {
            for (HprofAnalyzer.RootCauseSection s : analysis.rootCauseSections) {
                sb.append("  ▸ ").append(s.label()).append("：").append(s.text()).append("\n");
            }
        } else {
            sb.append("  ").append(analysis.rootCause).append("\n");
        }
        sb.append("\n");

        sb.append("【关键证据】\n");
        sb.append("  · 存活堆 ").append(HprofObject.formatSize(result.totalRetainedBytes()))
                .append(" / ").append(result.totalObjectCount()).append(" 个对象\n");
        sb.append("  · Top-10 集中度 ")
                .append(String.format(Locale.ROOT, "%.1f%%", analysis.topNRetainedRatio * 100.0)).append("\n");
        sb.append("  · 集合 + 数组 ").append(HprofObject.formatSize(analysis.collectionRetainedBytes))
                .append(" / ").append(analysis.collectionInstances).append(" 实例\n");
        sb.append("  · 类加载器 ").append(analysis.classLoaderClassCount).append(" 个\n");
        Map<String, Long> roots = analysis.gcRootsByKind;
        if (roots != null && !roots.isEmpty()) {
            StringBuilder rs = new StringBuilder("  · GC 根：");
            boolean first = true;
            for (Map.Entry<String, Long> e : roots.entrySet()) {
                if (!first) {
                    rs.append("，");
                }
                first = false;
                rs.append(e.getKey()).append("=").append(e.getValue());
            }
            sb.append(rs).append("\n");
        }
        if (analysis.findingDetails != null && !analysis.findingDetails.isEmpty()) {
            StringBuilder fs = new StringBuilder("  · 命中规则：");
            boolean first = true;
            for (HprofFinding f : analysis.findingDetails) {
                if (!first) {
                    fs.append("；");
                }
                first = false;
                fs.append("[").append(f.severity()).append("] ").append(f.title());
            }
            sb.append(fs).append("\n");
        }
        sb.append("\n");

        sb.append("【解决方案（按优先级）】\n");
        int seq = 0;
        for (HprofActionPlanner.ActionItem item : plan.items()) {
            seq++;
            sb.append("  [P").append(item.priority()).append("] ").append(seq).append(". ")
                    .append(item.title()).append("\n");
            sb.append("        怎么做：").append(item.how()).append("\n");
            sb.append("        预期：").append(item.expectedEffect()).append("\n");
            sb.append("        验证：").append(item.verify()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Read the "path" argument as a string.
     *
     * @param args tool arguments
     * @return the string value or null
     */
    private static String stringArg(Map<String, Object> args) {
        Object value = args.get(PARAM_PATH);
        return value == null ? null : value.toString();
    }
}
