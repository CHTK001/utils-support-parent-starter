package com.chua.hunyuan.support.image;

import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.ImageResponse;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
* 腾讯混元生图图片生成客户端
*
* <p>基于腾讯混元大模型生图 API 的 {@link ImageClient} 实现，通过 HTTP 协议
* 调用混元生图接口。该接口返回的图片数据可能为 基础64 编码或 URL，
* 实现中自动识别并处理两种格式。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi({"tencent-hunyuan", "tencent"})
public class HunyuanImageClient implements ImageClient {

    /**
    * 腾讯混元默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.hunyuan.cloud.tencent.com/v1";

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
    * 当前风格
     */
    private String style;

    /**
    * 当前推理步数
     */
    private Integer steps;

    /**
    * 构造腾讯混元生图客户端
    *
    * @param setting 客户端配置
     */
    public HunyuanImageClient(ImageClientSetting setting) {
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
    /** Style */
    public ImageClient style(String style) {
        this.style = style;
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
        String actualModel = model != null ? model : "hunyuan-pro";
        try {
            String requestBody = "{\"model\":\"" + actualModel
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                    + escapeJson(actualPrompt) + "\"}]"
                    + ",\"stream\":false,\"max_tokens\":2048}";
            String url = normalizeBaseUrl() + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + setting.getAppKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("混元生图请求失败: " + response.statusCode() + " - " + response.body());
            }
            String body = response.body();
            return parseAndDownloadImage(body);
        } catch (IOException e) {
            throw new RuntimeException("混元生图请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("混元生图请求被中断", e);
        }
    }

    /**
    * 解析响应并下载图片
    *
    * <p>混元生图接口返回的 content 可能是图片 URL 或 base64 编码数据，自动识别并处理。
    *
    * @param json 混元返回的 JSON 响应
    * @return BufferedImage 对象
    * @throws IOException 下载或解析失败
     */
    @SuppressWarnings("unchecked")
    private BufferedImage parseAndDownloadImage(String json) throws IOException {
        Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(json, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("混元生图返回的结果为空");
        }
        Map<String, Object> message = (Map<String, Object>) choices.getFirst().get("message");
        if (message == null) {
            throw new RuntimeException("混元生图返回的 message 为空");
        }
        String content = (String) message.get("content");
        if (content == null || content.isBlank()) {
            throw new RuntimeException("混元生图返回的 content 为空");
        }
 // 判断 内容 是 基础64 还是 URL
        if (content.startsWith("data:image") || content.startsWith("/9j/") || content.startsWith("iVBOR")) {
 // 基础64 格式
            String base64Data = content;
            if (base64Data.contains(",")) {
                base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
            }
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        }
        // URL 格式
        HttpRequest imageRequest = HttpRequest.newBuilder()
                .uri(URI.create(content))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> imageResponse = httpClient.send(imageRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
            return ImageIO.read(imageResponse.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载图片被中断", e);
        }
    }

    @Override
    /** 创建任务 */
    public String createTask(String prompt) {
        throw new UnsupportedOperationException("腾讯混元生图不支持异步任务模式，请使用 generate() 方法同步生成");
    }

    @Override
    /** 查询任务 */
    public ImageResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("腾讯混元生图不支持异步任务模式");
    }

    @Override
    /** 关闭 */
    public void close() {
    }

    /**
    * 构建图片尺寸字符串
    *
    * @return 如 "1024x1024"
     */
    private String buildSize() {
        int w = width != null ? width : 1024;
        int h = height != null ? height : 1024;
        return w + "x" + h;
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
