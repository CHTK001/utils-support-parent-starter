package com.chua.google.support.image;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.ImageResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
* Google Imagen 图片生成客户端
*
* <p>基于 Google Vertex AI Imagen 或 Gemini API 的 {@link ImageClient} 实现，
* 通过 HTTP 协议调用 Imagen 系列模型的图片生成接口，支持文生图功能。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi({"google", "gemini"})
public class GoogleImageClient implements ImageClient {

    /**
    * Google API 默认地址
     */
    private static final String DEFAULT_URL = "https://generativelanguage.googleapis.com/v1beta";

    /**
    * HTTP 客户端
     */
    private final HttpClient httpClient;

    /**
    * 客户端配置
     */
    private final ImageClientSetting setting;

    /**
    * 当前使用的模型名称
     */
    private String model;

    /**
    * 当前图片宽度
     */
    private Integer width;

    /**
    * 当前图片高度
     */
    private Integer height;

    /**
    * 当前提示词
     */
    private String prompt;

    /**
    * 当前反向提示词
     */
    private String negativePrompt;

    /**
    * 当前质量等级
     */
    private String quality;

    /**
    * 当前风格
     */
    private String style;

    /**
    * 当前随机种子
     */
    private Long seed;

    /**
    * 当前推理步数
     */
    private Integer steps;

    /**
    * 构造 Google Imagen 图片生成客户端
    *
    * @param setting 客户端配置
     */
    public GoogleImageClient(ImageClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    /** 模型 */
    public ImageClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 获取大小 */
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    @Override
    /** 提示符 */
    public ImageClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    /** negative提示符 */
    public ImageClient negativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
        return this;
    }

    @Override
    /** Quality */
    public ImageClient quality(String quality) {
        this.quality = quality;
        return this;
    }

    @Override
    /** Style */
    public ImageClient style(String style) {
        this.style = style;
        return this;
    }

    @Override
    /** Seed */
    public ImageClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    /** Steps */
    public ImageClient steps(Integer steps) {
        this.steps = steps;
        return this;
    }

    @Override
    /** 引用镜像 */
    public ImageClient referenceImage(byte[] image) {
        throw new UnsupportedOperationException("该服务商不支持参考图");
    }

    @Override
    /** 引用镜像 */
    public ImageClient referenceImage(BufferedImage image) {
        throw new UnsupportedOperationException("该服务商不支持参考图");
    }

    @Override
    /** 镜像strength */
    public ImageClient imageStrength(double strength) {
        throw new UnsupportedOperationException("该服务商不支持参考图强度");
    }

    @Override
    /** control类型 */
    public ImageClient controlType(String controlType) {
        throw new UnsupportedOperationException("该服务商不支持ControlNet");
    }

    @Override
/** Generate */
public BufferedImage generate(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        String actualModel = model != null ? model : "imagen-3.0-generate-001";
        try {
            // 构建请求体
            StringBuilder bodyBuilder = new StringBuilder();
            bodyBuilder.append("{\"instances\":[{\"prompt\":\"")
                    .append(escapeJson(actualPrompt))
                    .append("\"}],\"parameters\":{\"sampleCount\":1");
            if (negativePrompt != null && !negativePrompt.isBlank()) {
                bodyBuilder.append(",\"negativePrompt\":\"")
                        .append(escapeJson(negativePrompt)).append("\"");
            }
            if (width != null && height != null) {
                bodyBuilder.append(",\"imageSize\":{\"width\":")
                        .append(width).append(",\"height\":").append(height).append("}");
            }
            bodyBuilder.append("}}");
            String requestBody = bodyBuilder.toString();
            String url = normalizeBaseUrl() + "/models/" + actualModel + ":predict?key=" + setting.getAppKey();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Google Imagen 图片生成请求失败: " + response.statusCode() + " - " + response.body());
            }
            String body = response.body();
            return parseAndDecodeImage(body);
        } catch (IOException e) {
            throw new RuntimeException("Google Imagen 图片生成请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Google Imagen 图片生成请求被中断", e);
        }
    }

    /**
    * 解析响应并解码图片
    *
    * @param json Google 返回的 JSON 响应
    * @return BufferedImage 对象
    * @throws IOException 解码失败
     */
    @SuppressWarnings("unchecked")
    private BufferedImage parseAndDecodeImage(String json) throws IOException {
        Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(json, Map.class);
        List<Map<String, Object>> predictions = (List<Map<String, Object>>) root.get("predictions");
        if (CollectionUtils.isEmpty(predictions)) {
            throw new RuntimeException("Google Imagen 返回的预测结果为空");
        }
        String base64Data = (String) predictions.getFirst().get("bytesBase64Encoded");
        if (base64Data == null || base64Data.isBlank()) {
            throw new RuntimeException("Google Imagen 返回的图片数据为空");
        }
        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
        ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
        return ImageIO.read(bais);
    }

    @Override
    /** 创建任务 */
    public String createTask(String prompt) {
        throw new UnsupportedOperationException("Google Imagen 不支持异步任务模式，请使用 generate() 方法同步生成");
    }

    @Override
    /** 查询任务 */
    public ImageResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("Google Imagen 不支持异步任务模式");
    }

    @Override
    /** 关闭 */
    public void close() {
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        throw new UnsupportedOperationException("Google Imagen 不支持模型列表查询");
    }

    /**
    * 规范化 API 基础地址
    *
    * @return 规范化后的 URL
     */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = DEFAULT_URL;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
    * 转义 JSON 字符串中的特殊字符
    *
    * @param input 原始字符串
    * @return 转义后的字符串
     */
    private static String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
