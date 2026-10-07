package com.chua.kitesurf.support.agent;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.kitesurf.support.renderer.KitesurfRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Kitesurf MCP 浏览器自动化提供者。
 *
 * <p>走 deeplearning 既有 MCP 客户端体系（{@link McpClient}/{@link com.chua.common.support.ai.mcp.McpManager}，
 * agentscope 在用同一套）：JVM 内直调 Kitesurf 在线端点（默认 {@code https://kitesurf.dev}，
 * 可通过系统属性 {@code kitesurf.base-url} / 环境变量 {@code KITESURF_BASE_URL} 改指向），
 * 提供截图 / PDF / 渲染 HTML 三个 MCP 工具：
 * <ul>
 *   <li>{@code kitesurf_screenshot} — URL 转 PNG（返回 base64，失败时自动降级在线直链）</li>
 *   <li>{@code kitesurf_pdf} — URL 转 PDF（返回 base64）</li>
 *   <li>{@code kitesurf_html} — URL 渲染后 HTML 文本</li>
 * </ul></p>
 *
 * @author CH
 * @since 4.0.0.46
 */
@Spi("kitesurf")
public class KitesurfMcpProvider implements McpProvider {

    /**
     * 日志
     */
    private static final Logger log = LoggerFactory.getLogger(KitesurfMcpProvider.class);

    /**
     * 服务地址系统属性键。
     */
    public static final String BASE_URL_PROPERTY = "kitesurf.base-url";

    /**
     * 服务地址环境变量键。
     */
    public static final String BASE_URL_ENV = "KITESURF_BASE_URL";

    /**
     * 无状态单例客户端（无本地进程/容器，纯 HTTP 调用，无需持有连接）。
     */
    private final KitesurfMcpClient client = new KitesurfMcpClient();

    @Override
    public String name() {
        return "kitesurf";
    }

    @Override
    public McpClient create() {
        return client;
    }

    /**
     * 解析生效的服务地址：系统属性 &gt; 环境变量 &gt; 默认在线地址。
     *
     * @return 服务地址
     */
    public static String resolveBaseUrl() {
        String prop = System.getProperty(BASE_URL_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv(BASE_URL_ENV);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return KitesurfRenderer.DEFAULT_BASE_URL;
    }

    /**
     * Kitesurf MCP 客户端：JVM 内直调在线端点。
     *
     * @author CH
     * @since 4.0.0.46
     */
    public static class KitesurfMcpClient implements McpClient {

        /**
         * 已初始化标记
         */
        private volatile boolean initialized = false;

        @Override
        public void init() {
            initialized = true;
            log.info("[kitesurf] MCP 客户端已初始化，服务地址: {}", resolveBaseUrl());
        }

        @Override
        public List<McpToolDescriptor> listTools() {
            return List.of(
                    new McpToolDescriptor("kitesurf_screenshot", "Kitesurf 截取页面截图（无状态云端浏览器）",
                            Map.of("type", "object", "properties", Map.of(
                                    "url", Map.of("type", "string", "description", "目标网址")),
                                    "required", List.of("url"))),
                    new McpToolDescriptor("kitesurf_pdf", "Kitesurf 将页面渲染为 PDF",
                            Map.of("type", "object", "properties", Map.of(
                                    "url", Map.of("type", "string", "description", "目标网址")),
                                    "required", List.of("url"))),
                    new McpToolDescriptor("kitesurf_html", "Kitesurf 获取页面经 JS 执行后的渲染 HTML",
                            Map.of("type", "object", "properties", Map.of(
                                    "url", Map.of("type", "string", "description", "目标网址")),
                                    "required", List.of("url"))));
        }

        @Override
        public McpToolResult callTool(McpToolCall toolCall) {
            String name = toolCall == null ? null : toolCall.getToolName();
            Map<String, Object> args = toolCall == null || toolCall.getArguments() == null
                    ? Map.of() : toolCall.getArguments();
            Object urlRaw = args.get("url");
            String url = urlRaw == null ? null : String.valueOf(urlRaw).trim();
            if (url == null || url.isEmpty()) {
                return McpToolResult.error("缺少参数: url");
            }
            String baseUrl = resolveBaseUrl();
            try {
                switch (name) {
                    case "kitesurf_screenshot": {
                        byte[] png = KitesurfRenderer.fetch(
                                new KitesurfRenderer.RenderConfig().setBaseUrl(baseUrl),
                                "screenshot", url);
                        Map<String, Object> content = Wraps.linkedMap();
                        content.put("mimeType", "image/png");
                        content.put("base64", Base64.getEncoder().encodeToString(png));
                        content.put("onlineUrl", baseUrl.replaceAll("/+$", "")
                                + "/screenshot?url=" + url);
                        return McpToolResult.success(content);
                    }
                    case "kitesurf_pdf": {
                        byte[] pdf = KitesurfRenderer.fetch(
                                new KitesurfRenderer.RenderConfig().setBaseUrl(baseUrl),
                                "pdf", url);
                        Map<String, Object> content = Wraps.linkedMap();
                        content.put("mimeType", "application/pdf");
                        content.put("base64", Base64.getEncoder().encodeToString(pdf));
                        return McpToolResult.success(content);
                    }
                    case "kitesurf_html": {
                        String html = KitesurfRenderer.html(url,
                                cfg -> cfg.setBaseUrl(baseUrl));
                        return McpToolResult.success(html);
                    }
                    default:
                        return McpToolResult.error("未知工具: " + name);
                }
            } catch (Exception e) {
                log.warn("[kitesurf] 工具 {} 调用失败: {}", name, e.getMessage());
                return McpToolResult.error("调用失败: " + e.getMessage());
            }
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }
    }
}
