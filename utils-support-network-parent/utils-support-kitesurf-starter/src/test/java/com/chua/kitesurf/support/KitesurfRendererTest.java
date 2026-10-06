package com.chua.kitesurf.support;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.kitesurf.support.renderer.KitesurfRenderer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KitesurfRenderer} 生产级回归测试。
 *
 * <p>用仓库 {@code Server} 接口（{@code jdk} 实现，JDK 内置 HttpServer）在本地起桩：
 * 复刻 Kitesurf 在线端点协议（{@code GET /{screenshot|pdf|html}?url=}），
 * 覆盖截图 / PDF / 渲染 HTML / 文件落盘 / 异常分支 / 参数校验全部 public 方法，
 * 全程离线、确定性、无外部依赖。</p>
 *
 * @author CH
 * @since 4.0.0.46
 */
class KitesurfRendererTest {

    /**
     * PNG 魔数头
     */
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /**
     * 最小合法 PDF 头
     */
    private static final byte[] PDF_BYTES = "%PDF-1.4 stub".getBytes(StandardCharsets.UTF_8);

    /**
     * 渲染 HTML 桩内容
     */
    private static final String HTML_BODY = "<html><body>rendered</body></html>";

    /**
     * 桩服务端收到的 url 参数（断言参数透传与编码用）
     */
    private static final List<String> RECEIVED_URLS = new CopyOnWriteArrayList<>();

    /**
     * 桩服务端收到的原始请求 URI（断言客户端编码用，绕开 JDK HttpServer 的 query 解码语义）
     */
    private static final List<String> RECEIVED_URIS = new CopyOnWriteArrayList();

    /**
     * 本地桩服务
     */
    private static Server stub;

    /**
     * 桩服务地址
     */
    private static String baseUrl;

    /**
     * 启动本地桩：三个路由分别回固定 PNG / PDF / HTML。
     */
    @BeforeAll
    static void startStub() {
        stub = ServerBuilder.create()
                .type("jdk")
                .host("127.0.0.1")
                .port(0)
                .mapping("/screenshot", HttpMethod.GET, (req, resp) -> {
                    RECEIVED_URLS.add(req.getParam("url"));
                    resp.setStatus(200).setContentType("image/png").setBody(PNG_BYTES).end();
                })
                .mapping("/pdf", HttpMethod.GET, (req, resp) -> {
                    RECEIVED_URLS.add(req.getParam("url"));
                    resp.setStatus(200).setContentType("application/pdf").setBody(PDF_BYTES).end();
                })
                .mapping("/html", HttpMethod.GET, (req, resp) -> {
                    RECEIVED_URLS.add(req.getParam("url"));
                    RECEIVED_URIS.add(req.getUri());
                    resp.setStatus(200).setContentType("text/plain;charset=utf-8").setBody(HTML_BODY).end();
                })
                .build();
        stub.start();
        assertTrue(stub.isRunning(), "桩服务应启动成功");
        baseUrl = "http://127.0.0.1:" + stub.getPort();
    }

    /**
     * 停止本地桩。
     */
    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop();
        }
    }

    /**
     * screenshot 应返回 PNG 字节并透传 url 参数。
     *
     * @param tempDir 临时目录
     */
    @Test
    @DisplayName("screenshot 返回 PNG 并透传 url")
    void screenshotReturnsPng(@TempDir Path tempDir) {
        RECEIVED_URLS.clear();
        Path out = tempDir.resolve("shot.png");
        KitesurfRenderer.screenshot("https://example.com/",
                out.toString(), cfg -> cfg.setBaseUrl(baseUrl));
        assertArrayEquals(PNG_BYTES, readAll(out));
        assertEquals(List.of("https://example.com/"), RECEIVED_URLS);
    }

    /**
     * 客户端必须对 url 参数做百分号编码（含中文与 &amp;）。
     */
    @Test
    @DisplayName("url 参数百分号编码正确")
    void urlParameterEncoded() {
        RECEIVED_URIS.clear();
        KitesurfRenderer.html("https://example.com/?a=1&b=\u4e2d",
                cfg -> cfg.setBaseUrl(baseUrl));
        assertEquals(1, RECEIVED_URIS.size());
        String uri = RECEIVED_URIS.get(0);
        assertTrue(uri.startsWith("/html?url="), "应命中 html 端点: " + uri);
        assertTrue(uri.contains("%26"), "目标 URL 内的 & 应编码为 %26: " + uri);
        assertTrue(uri.contains("%E4%B8%AD"), "中文应 UTF-8 编码: " + uri);
    }

    /**
     * pdf 应返回 PDF 字节并落盘。
     *
     * @param tempDir 临时目录
     */
    @Test
    @DisplayName("pdf 返回 PDF 并落盘")
    void pdfReturnsPdf(@TempDir Path tempDir) {
        Path out = tempDir.resolve("sub").resolve("page.pdf");
        KitesurfRenderer.pdf("https://example.com/", out.toString(), cfg -> cfg.setBaseUrl(baseUrl));
        assertArrayEquals(PDF_BYTES, readAll(out));
    }

    /**
     * html 应返回渲染后文本。
     */
    @Test
    @DisplayName("html 返回渲染文本")
    void htmlReturnsText() {
        String html = KitesurfRenderer.html("https://example.com/",
                cfg -> cfg.setBaseUrl(baseUrl));
        assertEquals(HTML_BODY, html);
    }

    /**
     * 服务端 500 应转为 RuntimeException（含动作与地址信息）。
     */
    @Test
    @DisplayName("服务端 500 抛错")
    void serverErrorThrows() {
        Server err = ServerBuilder.create()
                .type("jdk")
                .host("127.0.0.1")
                .port(0)
                .mapping("/screenshot", HttpMethod.GET,
                        (req, resp) -> resp.setStatus(500).setBody("boom").end())
                .build();
        err.start();
        try {
            String badBase = "http://127.0.0.1:" + err.getPort();
            RuntimeException e = assertThrows(RuntimeException.class, () ->
                    KitesurfRenderer.screenshot("https://example.com/", "x.png",
                            cfg -> cfg.setBaseUrl(badBase)));
            assertTrue(e.getMessage().contains("500"), "异常应携带状态码: " + e.getMessage());
        } finally {
            err.stop();
        }
    }

    /**
     * 空 url 应参数校验失败（不发起请求）。
     */
    @Test
    @DisplayName("空 url 参数校验")
    void blankUrlRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                KitesurfRenderer.screenshot("  ", "x.png", cfg -> cfg.setBaseUrl(baseUrl)));
        assertThrows(IllegalArgumentException.class, () ->
                KitesurfRenderer.pdf(null, "x.pdf", cfg -> cfg.setBaseUrl(baseUrl)));
        assertThrows(IllegalArgumentException.class, () ->
                KitesurfRenderer.html(""));
    }

    /**
     * 默认配置应指向在线地址且超时为正。
     */
    @Test
    @DisplayName("默认 RenderConfig")
    void defaultConfig() {
        KitesurfRenderer.RenderConfig cfg = new KitesurfRenderer.RenderConfig();
        assertEquals(KitesurfRenderer.DEFAULT_BASE_URL, cfg.getBaseUrl());
        assertTrue(cfg.getTimeout().toMillis() > 0, "默认超时应为正");
    }

    /**
     * 读文件字节。
     *
     * @param path 文件路径
     * @return 文件字节
     */
    private static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception e) {
            throw new AssertionError("读取输出文件失败: " + path, e);
        }
    }
}
