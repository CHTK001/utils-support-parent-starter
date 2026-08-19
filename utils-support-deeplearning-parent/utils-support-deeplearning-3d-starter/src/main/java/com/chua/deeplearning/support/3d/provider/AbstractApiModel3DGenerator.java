package com.chua.deeplearning.support._3d.provider;

import com.chua.deeplearning.support._3d.api.Model3DConfig;
import com.chua.deeplearning.support._3d.api.Model3DGenerator;
import com.chua.deeplearning.support._3d.api.TextTo3DGenerator;
import com.chua.deeplearning.support._3d.api.ImageTo3DGenerator;
import com.chua.deeplearning.support._3d.api.SketchTo3DGenerator;
import com.chua.deeplearning.support._3d.api.Model3DStylizer;
import com.chua.deeplearning.support._3d.model.DefaultModel3D;
import com.chua.deeplearning.support._3d.model.Model3D;
import com.chua.deeplearning.support._3d.model.Model3DFormat;
import com.chua.deeplearning.support._3d.model.Model3DStyle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 基于 HTTP API 的 3D 生成器抽象基类
 *
 * @since 4.0.0.42
 */
public abstract class AbstractApiModel3DGenerator implements Model3DGenerator, TextTo3DGenerator, ImageTo3DGenerator, SketchTo3DGenerator, Model3DStylizer {

    /** 日志记录器 */
    /** Logger */
    private static final Logger logger = LoggerFactory.getLogger(AbstractApiModel3DGenerator.class);
    /** JSON 媒体类型 */
    /** JSON */
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    /** 八位组媒体类型 */
    /** Octet */
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    /** 配置定义 */
    /** 配置 */
    protected final Model3DConfig config;
    /** JSON 对象映射器 */
    /** Objectmapper */
    protected final ObjectMapper objectMapper;
    /** HTTP 客户端 */
    /** HTTP客户端 */
    protected final OkHttpClient httpClient;

    /**
     * 创建 AbstractApiModel3DGenerator 实例
     * @param config config
     */
    protected AbstractApiModel3DGenerator(Model3DConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout())
                .readTimeout(config.getReadTimeout())
                .writeTimeout(config.getWriteTimeout())
                .build();
    }

    @Override
    /** Generate */
    public Model3D generate(String prompt, Model3DFormat format, Model3DStyle style, String quality) throws IOException {
        if (logger.isInfoEnabled()) {
            logger.info("[3D] Text-to-3D: prompt={}, format={}, style={}, quality={}", prompt, format, style, quality);
        }
        String requestBody = buildTextRequestBody(prompt, format, style, quality);
        byte[] result = doPost(requestBody);
        return new DefaultModel3D(prompt, format, result);
    }

    @Override
    /** Generate */
    public Model3D generate(byte[] image, Model3DFormat format, Model3DStyle style, String quality) throws IOException {
        if (logger.isInfoEnabled()) {
            logger.info("[3D] Image-to-3D: imageSize={}, format={}, style={}, quality={}", image.length, format, style, quality);
        }
        String requestBody = buildImageRequestBody(image, format, style, quality);
        byte[] result = doPost(requestBody);
        return new DefaultModel3D("image-3d", format, result);
    }

    @Override
    /** Generate */
    public Model3D generate(byte[][] images, Model3DFormat format, Model3DStyle style, String quality) throws IOException {
        if (logger.isInfoEnabled()) {
            logger.info("[3D] MultiView-to-3D: imageCount={}, format={}, style={}, quality={}", images.length, format, style, quality);
        }
        String requestBody = buildMultiViewRequestBody(images, format, style, quality);
        byte[] result = doPost(requestBody);
        return new DefaultModel3D("multiview-3d", format, result);
    }

    @Override
    /** GenerateFromSketch */
    public Model3D generateFromSketch(byte[] sketch, String description, Model3DFormat format, Model3DStyle style, String quality) throws IOException {
        if (logger.isInfoEnabled()) {
            logger.info("[3D] Sketch-to-3D: description={}, sketchSize={}, format={}, style={}, quality={}",
                    description, sketch.length, format, style, quality);
        }
        String requestBody = buildSketchRequestBody(sketch, description, format, style, quality);
        byte[] result = doPost(requestBody);
        return new DefaultModel3D("sketch-3d", format, result);
    }

    @Override
    /** Generate */
    public Model3D generate(byte[] sketch, String description, Model3DFormat format, Model3DStyle style, String quality) throws IOException {
        return generateFromSketch(sketch, description, format, style, quality);
    }

    @Override
    /** Stylize */
    public Model3D stylize(Model3D model, Model3DStyle style, int resolution) throws IOException {
        if (logger.isInfoEnabled()) {
            logger.info("[3D] Stylize: style={}, resolution={}", style, resolution);
        }
        String requestBody = buildStylizeRequestBody(model, style, resolution);
        byte[] result = doPost(requestBody);
        DefaultModel3D styled = new DefaultModel3D("stylized-" + model.getName(), model.getFormat(), result);
        styled.setTexture(model.getTexture());
        return styled;
    }

    /**
     * 构建文本请求体
     */
    protected abstract String buildTextRequestBody(String prompt, Model3DFormat format, Model3DStyle style, String quality);

    /**
     * 构建图片请求体
     */
    protected abstract String buildImageRequestBody(byte[] image, Model3DFormat format, Model3DStyle style, String quality);

    /**
     * 构建多视角图片请求体
     */
    protected abstract String buildMultiViewRequestBody(byte[][] images, Model3DFormat format, Model3DStyle style, String quality);

    /**
     * 构建草图请求体
     */
    protected abstract String buildSketchRequestBody(byte[] sketch, String description, Model3DFormat format, Model3DStyle style, String quality);

    /**
     * 构建风格化请求体
     */
    protected abstract String buildStylizeRequestBody(Model3D model, Model3DStyle style, int resolution);

    /**
     * 执行 POST 请求
     */
    protected byte[] doPost(String requestBody) throws IOException {
        RequestBody body = RequestBody.create(requestBody, JSON);
        Request.Builder builder = new Request.Builder()
                .url(config.getEndpoint() + "/generate")
                .post(body);
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            builder.addHeader("Authorization", "Bearer " + config.getApiKey());
        }
        Request request = builder.build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response: " + response);
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }
            String contentType = responseBody.contentType() != null ? responseBody.contentType().toString() : "";
            if (contentType.contains("application/json")) {
                String json = responseBody.string();
                return parseResultFromJson(json);
            }
            return responseBody.bytes();
        }
    }

    /**
     * 从 JSON 响应中解析结果
     */
    protected byte[] parseResultFromJson(String json) throws IOException {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isTextual()) {
                return Base64.getDecoder().decode(dataNode.asText());
            }
            JsonNode resultNode = root.get("result");
            if (resultNode != null && resultNode.isTextual()) {
                return Base64.getDecoder().decode(resultNode.asText());
            }
            throw new IOException("Cannot parse result from JSON: " + json);
        } catch (Exception e) {
            throw new IOException("Failed to parse response JSON: " + e.getMessage(), e);
        }
    }
}