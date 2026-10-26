package com.chua.deeplearning.support.pytorch.layout;

import ai.djl.modality.cv.Image;
import com.chua.common.support.collection.wrap.Wraps;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WeVisDoc HTTP 客户端：调用 Python 推理服务获取 Markdown 文档解析结果。
 * <p>
 * 管线：Image → HTTP → WeVisDoc (Python) → Markdown
 * <p>
 * 配合 {@link WevisdocLayoutBridge} 可将 Markdown + OCR 文字位置
 * 映射为 11 类版面框。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WevisdocHttpClient {

    /**
     * 默认服务地址。
     */
    private static final String DEFAULT_URL = "http://localhost:8765";

    /**
     * 服务地址。
     */
    private final String baseUrl;

    /**
     * HTTP 客户端。
     */
    private final HttpClient httpClient;

    /**
     * 构造器。
     *
     * @param baseUrl Python 服务地址
     */
    public WevisdocHttpClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 构造器（默认地址）。
     */
    public WevisdocHttpClient() {
        this(DEFAULT_URL);
    }

    /**
     * 发送图片到 WeVisDoc 服务，返回 Markdown 文本。
     *
     * @param image DJL Image
     * @return Markdown 文本
     * @throws Exception 网络错误或推理失败
     */
    public String infer(Image image) throws Exception {
        byte[] pngBytes = toPngBytes(image);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/infer"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pngBytes))
                .build();

        log.debug("发送推理请求: {} ({} bytes)", baseUrl + "/infer", pngBytes.length);
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return parseMarkdown(response.body());
        } else {
            throw new IllegalStateException(
                    "WeVisDoc 服务返回 " + response.statusCode() + ": " + response.body());
        }
    }

    /**
     * 检查服务状态。
     *
     * @return true 如果模型已加载
     */
    public boolean isReady() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/status"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return response.body().replace(" ", "").contains("\"model_loaded\":true");
            }
        } catch (Exception e) {
            log.debug("服务状态检查失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 将 DJL Image 转为 PNG 字节。
     */
    private static byte[] toPngBytes(Image image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        image.save(baos, "png");
        return baos.toByteArray();
    }

    /**
     * 从 JSON 响应中解析 Markdown 文本。
     */
    static String parseMarkdown(String jsonBody) {
        Matcher m = Pattern.compile("\"markdown\"\\s*:\\s*\"(.*?)\"\\s*,").matcher(jsonBody);
        if (m.find()) {
            // 处理 JSON 转义
            String md = m.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\");
            return md;
        }
        throw new IllegalStateException("无法解析 Markdown 响应: " + jsonBody);
    }
}
