package com.chua.hprof.support.mcp;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.hprof.support.ai.HprofAiSummarizer;
import com.chua.hprof.support.parser.HprofParser;
import com.chua.hprof.support.serializer.HprofToJsonSerializer;
import com.chua.hprof.support.serializer.HprofToHtmlSerializer;
import com.chua.hprof.support.serializer.HprofToMarkdownSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * HPROF heap-dump MCP provider.
 *
 * <p>Registers the hprof analysis chain as MCP tools so LLM agents can request
 * heap-dump analysis directly:</p>
 *
 * <pre>
 * hprof binary
 *   | Java parse (GridKit hprof-heap, NetBeans PerfLib backend)
 * Java objects / structured data
 *   | serialize (Jackson / hand-written template)
 * JSON / Markdown / HTML
 *   | hand to AI
 * AI gives a plain-language conclusion
 * </pre>
 *
 * <p>Implements the {@link McpProvider} SPI so it is discovered via
 * {@code ServiceProvider.of(McpProvider.class).getExtension("hprof")}. The
 * tool set mirrors {@code SkillsmpMcpProvider} / {@code TimeMcpProvider}.</p>
 *
 * <h2>Tools</h2>
 * <ul>
 *   <li>{@code hprof_parse_to_json} - parse an hprof file and return the JSON document</li>
 *   <li>{@code hprof_parse_to_markdown} - parse an hprof file and return the Markdown report</li>
 *   <li>{@code hprof_parse_to_html} - parse an hprof file and return the self-contained HTML
 *       report (charts + findings + optional AI summary)</li>
 *   <li>{@code hprof_top_retained} - parse an hprof file and return the top retained classes</li>
 * </ul>
 *
 * <h2>Optional AI summary</h2>
 *
 * <p>The provider carries an optional {@link ChatClient} (set via
 * {@link #setChatClient} or {@link #setChatClientSetting}). When configured,
 * the {@code hprof_parse_to_html} tool embeds an AI summary block into the
 * report; when absent, the block is simply omitted.</p>
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
    * Optional AI chat client used for the HTML report's AI summary block.
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
    * Set the optional AI chat client from a setting.
    *
    * @param setting AI client setting, null clears
    * @return this provider for chaining
    */
    public HprofMcpProvider setChatClientSetting(ChatClientSetting setting) {
        this.chatClient = setting == null ? null : ChatClient.create(setting);
        return this;
    }

    @Override
    /** Provider name */
    public String name() {
        return NAME;
    }

    @Override
    /** Create client */
    public McpClient create() {
        return new HprofMcpClient();
    }

    @Override
    /** List available */
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
        /** Init */
        public void init() {
            initialized = true;
            log.info("[hprof] MCP client initialized");
        }

        @Override
        /** List tools */
        public List<McpToolDescriptor> listTools() {
            return toolDescriptors();
        }

        @Override
        /** Call tool */
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
                    case "hprof_parse_to_markdown" -> {
                        return McpToolResult.success(
                                HprofToMarkdownSerializer.serialize(
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
        /** Is initialized */
        public boolean isInitialized() {
            return initialized;
        }
    }

    /**
        * Tool descriptors for the hprof analysis tools.
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
                new McpToolDescriptor("hprof_parse_to_markdown",
                        "Parse an hprof heap dump file and return the Markdown report.",
                        pathSchema),
                new McpToolDescriptor("hprof_parse_to_html",
                        "Parse an hprof heap dump file and return a self-contained HTML report "
                                + "with memory-usage ranking charts, GC-root distribution, "
                                + "algorithmic leak findings, conclusions and an optional AI summary.",
                        htmlSchema),
                new McpToolDescriptor("hprof_top_retained",
                        "Parse an hprof heap dump file and return the top retained classes.",
                        pathSchema));
    }

    /**
    * Parse the hprof file referenced by the argument map.
    *
    * @param args tool arguments
    * @return parsed result
    * @throws IOException when the file cannot be read
    */
    private HprofParser.Result parse(Map<String, Object> args) throws IOException {
        return parse(stringArg(args));
    }

    /**
    * Parse the hprof file at the given path.
    *
    * @param path hprof file path
    * @return parsed result
    * @throws IOException when the file cannot be read
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
    * @throws IOException when the file cannot be read
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
