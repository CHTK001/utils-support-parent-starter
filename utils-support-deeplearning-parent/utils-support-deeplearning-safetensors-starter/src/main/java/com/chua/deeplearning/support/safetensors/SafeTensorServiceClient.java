package com.chua.deeplearning.support.safetensors;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * safetensor Python HTTP 推理服务客户端。
 * <p>
 * 通过 HTTP 协议调用 Python_服务/safetensor_服务.py 提供的推理接口。
 * 支持模型推理、下载和健康检查。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SafeTensorServiceClient {

    /**
     * 服务基础 URL。
     */
    private final String baseUrl;

    /**
     * HTTP 客户端（Java 11+ 内置）。
     */
    private final HttpClient httpClient;

    /**
     * Jackson JSON 映射器。
     */
    private final ObjectMapper mapper;

    /**
     * 构造函数。
     *
     * @param host Python 服务主机
     * @param port Python 服务端口
     */
    public SafeTensorServiceClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * 健康检查。
     *
     * @return true 表示服务正常
     */
    public boolean health() {
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.warn("[SafeTensor] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 模型推理。
     *
     * @param modelName 模型名称
     * @param modelType 模型类型（llm / 镜像_gen / asr / tts / etc.）
     * @param input     输入数据
     * @param params    推理参数
     * @return 推理结果
     */
    public Map<String, Object> infer(String modelName, String modelType,
                                     Map<String, Object> input, Map<String, Object> params) {
        try {
            var body = Map.of(
                    "model_name", modelName,
                    "model_type", modelType,
                    "input", input,
                    "params", params != null ? params : Map.of()
            );
            var json = mapper.writeValueAsString(body);
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/infer"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofMinutes(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("推理请求失败: HTTP " + resp.statusCode() + " - " + resp.body());
            }
            @SuppressWarnings("unchecked")
            var result = mapper.readValue(resp.body(), Map.class);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("SafeTensor 推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 模型训练（调用 /train 端点）。
     * <p>与 {@link #infer} 不同，本方法调用 Python 服务的 /train 端点，
     * 执行训练、评估、保存、加载等训练相关操作。</p>
     *
     * @param modelName 模型名称
     * @param modelType 操作类型（train / train_step / eval / 加载 / 保存 / prepare_for_培训假）
     * @param input     输入数据
     * @param params    训练参数
     * @return 训练结果
     */
    public Map<String, Object> train(String modelName, String modelType,
                                     Map<String, Object> input, Map<String, Object> params) {
        try {
            var body = Map.of(
                    "model_name", modelName,
                    "model_type", modelType,
                    "input", input,
                    "params", params != null ? params : Map.of()
            );
            var json = mapper.writeValueAsString(body);
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/train"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofMinutes(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("训练请求失败: HTTP " + resp.statusCode() + " - " + resp.body());
            }
            @SuppressWarnings("unchecked")
            var result = mapper.readValue(resp.body(), Map.class);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("SafeTensor 训练失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载模型。
     *
     * @param modelName 模型名称
     * @param source    下载源（modelscope / huggingface）
     * @param revision  版本号
     * @return 下载结果
     */
    public Map<String, Object> downloadModel(String modelName, String source, String revision) {
        try {
            var body = Map.of(
                    "model_name", modelName,
                    "source", source,
                    "revision", revision != null ? revision : ""
            );
            var json = mapper.writeValueAsString(body);
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/download"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofHours(1))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("模型下载失败: HTTP " + resp.statusCode());
            }
            @SuppressWarnings("unchecked")
            var result = mapper.readValue(resp.body(), Map.class);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("SafeTensor 模型下载失败: " + e.getMessage(), e);
        }
    }
}
