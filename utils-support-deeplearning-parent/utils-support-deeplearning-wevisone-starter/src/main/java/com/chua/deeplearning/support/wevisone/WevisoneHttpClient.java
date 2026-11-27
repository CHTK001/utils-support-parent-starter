package com.chua.deeplearning.support.wevisone;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.deeplearning.support.image.VlmBox;
import com.chua.deeplearning.support.image.VlmRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * WeVisOne-VL Python 推理服务的 HTTP 客户端。
 *
 * <p>只负责协议编解码，不持有模型、不做提示词拼接：提示词由服务端按任务类型生成
 * （与官方 {@code prompts.py} 同措辞），本客户端只把请求字段与服务字段一一对应。
 *
 * <p>协议（服务端实现见本模块 {@code python/server.py}）：
 * <pre>
 * POST /infer
 *   { "image": "&lt;base64&gt;", "task": "detection", "query": "...", "prompt": "",
 *     "reference_boxes": [[x1,y1,x2,y2]], "decode_matting": false }
 *   → { "ok": true, "text": "...",
 *       "objects": [{"label": "cat", "box": [x1,y1,x2,y2], "point": [x,y]}],
 *       "masks":   [{"width": 640, "height": 480, "png": "&lt;base64&gt;"}],
 *       "latency_s": 1.23 }
 * GET /status → { "model_loaded": true, "model": "..." }
 * </pre>
 *
 * <p>坐标约定：请求的 {@code reference_boxes} 与响应的 {@code box}/{@code point} <b>都是归一化 0..1</b>，
 * 由服务端在读取图片后换算成模型需要的像素坐标，避免本层为了拿宽高而引入图像解码依赖。
 *
 * <p>失败口径：网络错误、非 200 状态、{@code ok=false} 一律抛 {@link IllegalStateException}，
 * 消息里带服务端原文，便于定位是模型未加载还是提示词非法。
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class WevisoneHttpClient {

    /**
     * 默认服务地址（与 WeVisDoc 本地服务同端口约定）
     */
    public static final String DEFAULT_URL = "http://localhost:8765";

    /**
     * 推理默认超时（秒）：2B 模型在 CPU 上单图可达分钟级，不能用普通接口的超时口径
     */
    public static final int DEFAULT_INFER_TIMEOUT_SECONDS = 600;

    /**
     * 健康检查默认超时（秒）
     */
    public static final int DEFAULT_HEALTH_TIMEOUT_SECONDS = 5;

    /**
     * 服务地址（不含尾部斜杠）
     */
    private final String baseUrl;

    /**
     * 推理超时（秒）
     */
    private final int inferTimeoutSeconds;

    /**
     * 健康检查超时（秒）
     */
    private final int healthTimeoutSeconds;

    /**
     * HTTP 客户端
     */
    private final HttpClient httpClient;

    /**
     * 构造器（默认地址）。
     */
    public WevisoneHttpClient() {
        this(DEFAULT_URL);
    }

    /**
     * 构造器。
     *
     * @param baseUrl 服务地址，如 {@code http://192.168.50.192:8765}
     */
    public WevisoneHttpClient(String baseUrl) {
        this(baseUrl, DEFAULT_INFER_TIMEOUT_SECONDS);
    }

    /**
     * 构造器。
     *
     * @param baseUrl            服务地址
     * @param inferTimeoutSeconds 推理超时（秒），必须大于 0
     */
    public WevisoneHttpClient(String baseUrl, int inferTimeoutSeconds) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("WeVisOne-VL 服务地址不能为空");
        }
        if (inferTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("推理超时必须大于 0 秒: " + inferTimeoutSeconds);
        }
        String trimmed = baseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.inferTimeoutSeconds = inferTimeoutSeconds;
        this.healthTimeoutSeconds = DEFAULT_HEALTH_TIMEOUT_SECONDS;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 服务地址。
     *
     * @return 服务地址
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 提交一次推理请求。
     *
     * @param imageData 图像字节（JPEG/PNG 等原始编码）
     * @param request   推理请求
     * @return 服务端原始响应 JSON（已确认 {@code ok} 非 false）
     * @throws IllegalStateException 网络失败、非 200、或服务端返回错误
     */
    public JsonObject infer(byte[] imageData, VlmRequest request) {
        if (imageData == null || imageData.length == 0) {
            throw new IllegalArgumentException("图像数据不能为空");
        }
        if (request == null) {
            throw new IllegalArgumentException("推理请求不能为空");
        }
        JsonObject payload = Json.createJsonObject();
        payload.fluent("image", Base64.getEncoder().encodeToString(imageData));
        payload.fluent("task", request.task().prompt());
        payload.fluent("query", nullToEmpty(request.query()));
        payload.fluent("prompt", nullToEmpty(request.prompt()));
        payload.fluent("reference_boxes", referenceBoxes(request));
        payload.fluent("decode_matting", request.decodeMatting());
        return post("/infer", payload.toJSONString(), inferTimeoutSeconds, "推理");
    }

    /**
     * 检查服务与模型是否就绪。
     *
     * @return 服务可达且模型已加载返回 true；任何失败都返回 false（不抛异常）
     */
    public boolean isReady() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/status"))
                    .timeout(Duration.ofSeconds(healthTimeoutSeconds))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.debug("[WeVisOne] 状态检查返回 {}: {}", response.statusCode(), response.body());
                return false;
            }
            Object loaded = Json.getJsonObject(response.body()).get("model_loaded");
            return Boolean.TRUE.equals(loaded) || "true".equalsIgnoreCase(String.valueOf(loaded));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[WeVisOne] 状态检查被中断: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.debug("[WeVisOne] 服务状态检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 把参考框序列化为 {@code [[x1,y1,x2,y2], ...]}（归一化）。
     *
     * @param request 推理请求
     * @return 参考框数组，无参考框时为空数组
     */
    private static JsonArray referenceBoxes(VlmRequest request) {
        JsonArray array = Json.createJsonArray();
        for (VlmBox box : request.referenceBoxes()) {
            JsonArray item = Json.createJsonArray();
            item.fluent(box.x1()).fluent(box.y1()).fluent(box.x2()).fluent(box.y2());
            array.fluent(item);
        }
        return array;
    }

    /**
     * 发送 JSON POST 请求并校验响应。
     *
     * @param path           接口路径
     * @param body           请求体
     * @param timeoutSeconds 超时（秒）
     * @param action         动作名（用于错误消息）
     * @return 响应 JSON
     */
    private JsonObject post(String path, String body, int timeoutSeconds, String action) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WeVisOne-VL " + action + "被中断: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("WeVisOne-VL " + action + "调用失败（" + baseUrl + path + "）: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("WeVisOne-VL " + action + "失败，服务返回 " + response.statusCode() + ": " + response.body());
        }
        JsonObject json = Json.getJsonObject(response.body());
        if (Boolean.FALSE.equals(json.get("ok"))) {
            throw new IllegalStateException("WeVisOne-VL " + action + "失败: " + json.get("error"));
        }
        return json;
    }

    /**
     * null 转空串（请求字段统一用空串表示缺省，避免服务端区分 null/缺失）。
     *
     * @param value 原值
     * @return 非 null 原值或空串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
