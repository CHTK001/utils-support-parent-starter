package com.chua.kitesurf.support;

import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.kitesurf.support.agent.KitesurfMcpProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KitesurfMcpProvider} 生产级回归测试。
 *
 * <p>用仓库 {@code Server} 接口（{@code jdk} 实现）在本地起桩，指向桩地址后覆盖：
 * SPI 可发现、工具清单、截图/PDF/HTML 三工具真实调用、缺参、未知工具，
 * 全程离线、确定性。</p>
 *
 * @author CH
 * @since 4.0.0.46
 */
class KitesurfMcpProviderTest {

    /**
     * 桩 PNG
     */
    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * 桩 PDF
     */
    private static final byte[] PDF_BYTES = "%PDF-1.4 stub".getBytes(StandardCharsets.UTF_8);

    /**
     * 桩 HTML
     */
    private static final String HTML_BODY = "<html><body>stub</body></html>";

    /**
     * 本地桩服务
     */
    private static Server stub;

    /**
     * 桩服务地址
     */
    private static String baseUrl;

    /**
     * 启动本地桩。
     */
    @BeforeAll
    static void startStub() {
        stub = ServerBuilder.create()
                .type("jdk")
                .host("127.0.0.1")
                .port(0)
                .mapping("/screenshot", HttpMethod.GET,
                        (req, resp) -> resp.setStatus(200).setContentType("image/png").setBody(PNG_BYTES).end())
                .mapping("/pdf", HttpMethod.GET,
                        (req, resp) -> resp.setStatus(200).setContentType("application/pdf").setBody(PDF_BYTES).end())
                .mapping("/html", HttpMethod.GET,
                        (req, resp) -> resp.setStatus(200).setContentType("text/plain;charset=utf-8").setBody(HTML_BODY).end())
                .build();
        stub.start();
        assertTrue(stub.isRunning(), "桩服务应启动成功");
        baseUrl = "http://127.0.0.1:" + stub.getPort();
        System.setProperty(KitesurfMcpProvider.BASE_URL_PROPERTY, baseUrl);
    }

    /**
     * 停止本地桩并清理系统属性。
     */
    @AfterAll
    static void stopStub() {
        System.clearProperty(KitesurfMcpProvider.BASE_URL_PROPERTY);
        if (stub != null) {
            stub.stop();
        }
    }

    /**
     * SPI 应能发现 kitesurf provider。
     */
    @Test
    @DisplayName("SPI 可发现")
    void spiDiscoverable() {
        List<String> names = StreamSupport
                .stream(ServiceLoader.load(McpProvider.class).spliterator(), false)
                .map(McpProvider::name)
                .toList();
        assertTrue(names.contains("kitesurf"), "SPI 应注册 kitesurf: " + names);
    }

    /**
     * 工具清单应为截图/PDF/HTML 三个。
     */
    @Test
    @DisplayName("工具清单三工具")
    void toolList() {
        McpClient client = new KitesurfMcpProvider().create();
        client.init();
        assertTrue(client.isInitialized(), "init 后应已初始化");
        List<String> names = client.listTools().stream().map(McpToolDescriptor::getName).toList();
        assertEquals(List.of("kitesurf_screenshot", "kitesurf_pdf", "kitesurf_html"), names);
    }

    /**
     * 截图工具应返回 PNG 的 base64。
     */
    @Test
    @DisplayName("截图工具返回 base64 PNG")
    void screenshotTool() {
        McpClient client = newClient();
        McpToolResult result = client.callTool(
                new McpToolCall("kitesurf_screenshot", Map.of("url", "https://example.com/")));
        assertTrue(result.isSuccess(), "调用应成功: " + result.getErrorMessage());
        Map<?, ?> content = (Map<?, ?>) result.getContent();
        assertEquals("image/png", content.get("mimeType"));
        assertEquals(java.util.Base64.getEncoder().encodeToString(PNG_BYTES), content.get("base64"));
    }

    /**
     * PDF 工具应返回 PDF 的 base64。
     */
    @Test
    @DisplayName("PDF 工具返回 base64 PDF")
    void pdfTool() {
        McpClient client = newClient();
        McpToolResult result = client.callTool(
                new McpToolCall("kitesurf_pdf", Map.of("url", "https://example.com/")));
        assertTrue(result.isSuccess(), "调用应成功: " + result.getErrorMessage());
        Map<?, ?> content = (Map<?, ?>) result.getContent();
        assertEquals("application/pdf", content.get("mimeType"));
    }

    /**
     * HTML 工具应返回渲染文本。
     */
    @Test
    @DisplayName("HTML 工具返回文本")
    void htmlTool() {
        McpClient client = newClient();
        McpToolResult result = client.callTool(
                new McpToolCall("kitesurf_html", Map.of("url", "https://example.com/")));
        assertTrue(result.isSuccess(), "调用应成功: " + result.getErrorMessage());
        assertEquals(HTML_BODY, result.getContent());
    }

    /**
     * 缺 url 参数应返回错误而非抛异常。
     */
    @Test
    @DisplayName("缺参返回错误")
    void missingUrlErrors() {
        McpClient client = newClient();
        McpToolResult result = client.callTool(new McpToolCall("kitesurf_html", Map.of()));
        assertFalse(result.isSuccess(), "缺参应失败");
    }

    /**
     * 未知工具名应返回错误。
     */
    @Test
    @DisplayName("未知工具返回错误")
    void unknownToolErrors() {
        McpClient client = newClient();
        McpToolResult result = client.callTool(
                new McpToolCall("kitesurf_nope", Map.of("url", "https://example.com/")));
        assertFalse(result.isSuccess(), "未知工具应失败");
    }

    /**
     * 服务地址优先级：系统属性 &gt; 默认在线地址。
     */
    @Test
    @DisplayName("服务地址解析优先级")
    void baseUrlResolution() {
        assertEquals(baseUrl, KitesurfMcpProvider.resolveBaseUrl());
    }

    /**
     * 新建已初始化客户端。
     *
     * @return MCP 客户端
     */
    private static McpClient newClient() {
        McpClient client = new KitesurfMcpProvider().create();
        client.init();
        return client;
    }
}
