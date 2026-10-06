package com.chua.kitesurf.support.renderer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Kitesurf URL 渲染器。
 *
 * <p>复用 Kitesurf（Cloudflare Workers 上的无状态浏览器）的公共端点能力，
 * 将任意 URL 渲染为图片（PNG）、PDF 或渲染后 HTML：
 * <ul>
 *   <li>{@code GET {base}/screenshot?url=...} — PNG 图片</li>
 *   <li>{@code GET {base}/pdf?url=...} — PDF 文档</li>
 *   <li>{@code GET {base}/html?url=...} — JS 执行后的渲染 HTML</li>
 * </ul>
 * 服务地址默认在线直调 {@code https://kitesurf.dev}（免账号），可通过
 * {@link RenderConfig#setBaseUrl(String)} 指向自建 Browser Run（{@code browser=kitesurf}）。</p>
 *
 * @author CH
 * @since 4.0.0.46
 */
public class KitesurfRenderer {

    /**
     * 默认在线服务地址（公共 playground，免账号）。
     */
    public static final String DEFAULT_BASE_URL = "https://kitesurf.dev";

    /**
     * 默认请求超时。
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);

    /**
     * 截取 URL 页面截图（PNG）。
     *
     * @param url     页面地址
     * @param outFile 输出文件路径
     */
    public static void screenshot(String url, String outFile) {
        screenshot(url, outFile, null);
    }

    /**
     * 截取 URL 页面截图（PNG）。
     *
     * @param url     页面地址
     * @param outFile 输出文件路径
     * @param options 配置回调，可设置服务地址、超时等
     */
    public static void screenshot(String url, String outFile, Consumer<RenderConfig> options) {
        RenderConfig config = new RenderConfig();
        if (options != null) {
            options.accept(config);
        }
        byte[] body = fetch(config, "screenshot", url);
        writeFile(outFile, body);
    }

    /**
     * 将 URL 页面渲染为 PDF。
     *
     * @param url     页面地址
     * @param outFile 输出文件路径
     */
    public static void pdf(String url, String outFile) {
        pdf(url, outFile, null);
    }

    /**
     * 将 URL 页面渲染为 PDF。
     *
     * @param url     页面地址
     * @param outFile 输出文件路径
     * @param options 配置回调，可设置服务地址、超时等
     */
    public static void pdf(String url, String outFile, Consumer<RenderConfig> options) {
        RenderConfig config = new RenderConfig();
        if (options != null) {
            options.accept(config);
        }
        byte[] body = fetch(config, "pdf", url);
        writeFile(outFile, body);
    }

    /**
     * 获取 URL 页面经 JS 执行后的渲染 HTML。
     *
     * @param url 页面地址
     * @return 渲染后的 HTML 文本
     */
    public static String html(String url) {
        return html(url, null);
    }

    /**
     * 获取 URL 页面经 JS 执行后的渲染 HTML。
     *
     * @param url     页面地址
     * @param options 配置回调，可设置服务地址、超时等
     * @return 渲染后的 HTML 文本
     */
    public static String html(String url, Consumer<RenderConfig> options) {
        RenderConfig config = new RenderConfig();
        if (options != null) {
            options.accept(config);
        }
        return new String(fetch(config, "html", url), StandardCharsets.UTF_8);
    }

    /**
     * 调用 Kitesurf 端点并返回原始响应字节。
     *
     * @param config   渲染配置
     * @param action   端点动作（screenshot / pdf / html）
     * @param url      目标页面地址
     * @return 响应体字节
     */
    public static byte[] fetch(RenderConfig config, String action, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        String base = config.getBaseUrl() == null || config.getBaseUrl().isBlank()
                ? DEFAULT_BASE_URL : config.getBaseUrl().replaceAll("/+$", "");
        String endpoint = base + "/" + action + "?url="
                + URLEncoder.encode(url, StandardCharsets.UTF_8);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(config.getTimeout())
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(config.getTimeout())
                    .GET();
            if (config.getExtraHeaders() != null) {
                config.getExtraHeaders().forEach(builder::header);
            }
            HttpResponse<byte[]> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Kitesurf 渲染失败: HTTP " + response.statusCode()
                        + " [" + action + " " + url + "]");
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Kitesurf 渲染失败 [" + action + " " + url + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 写文件（父目录不存在时自动创建）。
     *
     * @param outFile 输出文件路径
     * @param body    文件内容
     */
    private static void writeFile(String outFile, byte[] body) {
        try {
            Path path = Paths.get(outFile);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + outFile, e);
        }
    }

    /**
     * 渲染配置。
     *
     * @author CH
     * @since 4.0.0.46
     */
    public static class RenderConfig {
        /**
         * 服务地址
         */
        private String baseUrl = DEFAULT_BASE_URL;
        /**
         * 请求超时
         */
        private Duration timeout = DEFAULT_TIMEOUT;
        /**
         * 额外请求头
         */
        private java.util.Map<String, String> extraHeaders;

        /**
         * 获取服务地址。
         *
         * @return 服务地址
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置服务地址（默认 {@link KitesurfRenderer#DEFAULT_BASE_URL}；
         * 自建 Browser Run 指向 {@code ...?browser=kitesurf} 对应地址）。
         *
         * @param baseUrl 服务地址
         * @return 当前配置
         */
        public RenderConfig setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 获取请求超时。
         *
         * @return 请求超时
         */
        public Duration getTimeout() {
            return timeout;
        }

        /**
         * 设置请求超时。
         *
         * @param timeout 请求超时
         * @return 当前配置
         */
        public RenderConfig setTimeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * 获取额外请求头。
         *
         * @return 额外请求头
         */
        public java.util.Map<String, String> getExtraHeaders() {
            return extraHeaders;
        }

        /**
         * 设置额外请求头（如自建 Browser Run 的 Authorization）。
         *
         * @param extraHeaders 额外请求头
         * @return 当前配置
         */
        public RenderConfig setExtraHeaders(java.util.Map<String, String> extraHeaders) {
            this.extraHeaders = extraHeaders;
            return this;
        }
    }
}
