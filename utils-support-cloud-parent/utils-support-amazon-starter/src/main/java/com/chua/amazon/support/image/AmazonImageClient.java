package com.chua.amazon.support.image;

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
 * Amazon Bedrock 图片生成客户端
 *
 * <p>基于 AWS Bedrock Runtime InvokeModel 的 {@link ImageClient} 实现，
 * 通过 HTTP 协议调用 Bedrock 上的 Stable Diffusion / Titan 等模型进行图片生成。
 *
 * @author CH
 * @since 2026/07/15
 */
@Slf4j
@Spi({"amazon"})
public class AmazonImageClient implements ImageClient {

    /**
     * Amazon Bedrock 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://bedrock-runtime.us-east-1.amazonaws.com";

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
     * 构造 Amazon Bedrock 图片生成客户端
     *
     * @param setting 客户端配置
     */
    public AmazonImageClient(ImageClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public ImageClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    @Override
    public ImageClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    public ImageClient negativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
        return this;
    }

    @Override
    public ImageClient quality(String quality) {
        this.quality = quality;
        return this;
    }

    @Override
    public ImageClient style(String style) {
        this.style = style;
        return this;
    }

    @Override
    public ImageClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    public ImageClient steps(Integer steps) {
        this.steps = steps;
        return this;
    }

    @Override
    public ImageClient referenceImage(byte[] image) {
        throw new UnsupportedOperationException("该服务商不支持参考图");
    }

    @Override
    public ImageClient referenceImage(BufferedImage image) {
        throw new UnsupportedOperationException("该服务商不支持参考图");
    }

    @Override
    public ImageClient imageStrength(double strength) {
        throw new UnsupportedOperationException("该服务商不支持参考图强度");
    }

    @Override
    public ImageClient controlType(String controlType) {
        throw new UnsupportedOperationException("该服务商不支持ControlNet");
    }

    @Override
public BufferedImage generate(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        String actualModel = model != null ? model : "amazon.titan-image-generator-v1";
        int w = width != null ? width : 1024;
        int h = height != null ? height : 1024;
        String q = quality != null ? quality : "standard";
        try {
            // 构建请求体
            StringBuilder bodyBuilder = new StringBuilder();
            bodyBuilder.append("{\"textToImageParams\":{\"text\":\"")
                    .append(escapeJson(actualPrompt)).append("\"");
            if (negativePrompt != null && !negativePrompt.isBlank()) {
                bodyBuilder.append(",\"negativeText\":\"")
                        .append(escapeJson(negativePrompt)).append("\"");
            }
            bodyBuilder.append("},\"taskType\":\"TEXT_IMAGE\"")
                    .append(",\"imageGenerationConfig\":{\"numberOfImages\":1")
                    .append(",\"quality\":\"").append(q).append("\"")
                    .append(",\"width\":").append(w)
                    .append(",\"height\":").append(h);
            if (seed != null) {
                bodyBuilder.append(",\"seed\":").append(seed);
            }
            bodyBuilder.append("}}");
            String requestBody = bodyBuilder.toString();
            String url = normalizeBaseUrl() + "/model/" + actualModel + "/invoke";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + setting.getAppKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Amazon Bedrock 图片生成请求失败: " + response.statusCode() + " - " + response.body());
            }
            String body = response.body();
            return parseAndDecodeImage(body);
        } catch (IOException e) {
            throw new RuntimeException("Amazon Bedrock 图片生成请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Amazon Bedrock 图片生成请求被中断", e);
        }
    }

    /**
     * 解析响应并解码图片
     *
     * @param json Amazon Bedrock 返回的 JSON 响应
     * @return BufferedImage 对象
     * @throws IOException 解码失败
     */
    @SuppressWarnings("unchecked")
    private BufferedImage parseAndDecodeImage(String json) throws IOException {
        Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(json, Map.class);
        List<String> images = (List<String>) root.get("images");
        if (CollectionUtils.isEmpty(images)) {
            throw new RuntimeException("Amazon Bedrock 返回的图片数据为空");
        }
        String base64Data = images.get(0);
        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
        ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
        return ImageIO.read(bais);
    }

    @Override
    public String createTask(String prompt) {
        throw new UnsupportedOperationException("Amazon Bedrock 不支持异步任务模式，请使用 generate() 方法同步生成");
    }

    @Override
    public ImageResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("Amazon Bedrock 不支持异步任务模式");
    }

    @Override
    public void close() {
    }

    @Override
    public List<ModelDefinition> models() {
        throw new UnsupportedOperationException("Amazon Bedrock 不支持模型列表查询");
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
