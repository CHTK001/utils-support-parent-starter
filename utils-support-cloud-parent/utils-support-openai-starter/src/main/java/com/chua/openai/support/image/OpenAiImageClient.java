package com.chua.openai.support.image;

import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.ImageResponse;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 图片生成客户端。
 *
 * <p>基于 OpenAI DALL-E API 的 {@link ImageClient} 实现，支持 OpenAI 兼容接口的
 * 所有服务商（如 OpenAI、SiliconFlow、SenseTime 等）。
 *
 * <p>通过 HTTP 协议直接调用 {@code /images/generations} 接口生成图片，
 * 返回的图片 URL 会被自动下载并解析为 {@link BufferedImage}。
 *
 * <p>通过 SPI 机制注册以下别名：
 * <ul>
 *   <li>openai — OpenAI 官方（DALL-E 系列）</li>
 *   <li>siliconflow — 硅基流动</li>
 *   <li>sensetime — 商汤科技</li>
 *   <li>github — GitHub Models</li>
 *   <li>gitee — Gitee AI</li>
 * </ul>
 *
 * <p>调用示例：
 * <pre>{@code
 *   BufferedImage image = ImageClient.create("openai", "sk-xxx")
 *       .model("dall-e-3")
 *       .prompt("一只可爱的猫")
 *       .size(1024, 1024)
 *       .generate();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"openai", "siliconflow", "sensetime", "github", "gitee"})
public class OpenAiImageClient implements ImageClient {

    /**
     * OpenAI 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.openai.com/v1";

    /**
     * 客户端配置
     */
    private final ImageClientSetting setting;

    /**
     * 当前使用的模型名称（如 dall-e-3、dall-e-2）
     */
    private String model;

    /**
     * 生成图片宽度（像素）
     */
    private Integer width;

    /**
     * 生成图片高度（像素）
     */
    private Integer height;

    /**
     * 提示词（未通过方法参数传入时使用此值）
     */
    private String prompt;

    /**
     * 图片质量（如 "standard"、"hd"），仅 DALL-E 3 支持
     */
    private String quality;

    /**
     * 图片风格（如 "vivid"、"natural"），仅 DALL-E 3 支持
     */
    private String style;

    /**
     * 构造 OpenAI 图片生成客户端。
     *
     * @param setting 客户端配置
     */
    public OpenAiImageClient(ImageClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
    }

    @Override
    public ImageClient model(String model) { this.model = model; return this; }
    @Override
    public ImageClient size(int width, int height) { this.width = width; this.height = height; return this; }
    @Override
    public ImageClient prompt(String prompt) { this.prompt = prompt; return this; }
    @Override
    public ImageClient quality(String quality) { this.quality = quality; return this; }
    @Override
    public ImageClient style(String style) { this.style = style; return this; }
    @Override
    public ImageClient referenceImage(byte[] image) { throw new UnsupportedOperationException("该服务商不支持参考图"); }
    @Override
    public ImageClient referenceImage(BufferedImage image) { throw new UnsupportedOperationException("该服务商不支持参考图"); }
    @Override
    public ImageClient imageStrength(double strength) { throw new UnsupportedOperationException("该服务商不支持参考图强度"); }
    @Override
    public ImageClient controlType(String controlType) { throw new UnsupportedOperationException("该服务商不支持ControlNet"); }

    @Override
    public BufferedImage generate(String prompt) {
        // 优先使用方法参数，其次使用链式设置的值
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }

        // 构建 OpenAI 图片生成请求体
        String requestBody = JsonObject.create()
                .fluentPut("model", model != null ? model : "dall-e-3")
                .fluentPut("prompt", actualPrompt)
                .fluentPut("n", 1)
                .fluentPut("size", buildSize())
                .fluentPut(quality != null && !quality.isBlank(), "quality", quality)
                .fluentPut(style != null && !style.isBlank(), "style", style)
                .toJSONString();
        ClientResponse resp = HttpClientFactory.of(normalizeBaseUrl())
                .path("/images/generations")
                .header("Authorization", "Bearer " + setting.getAppKey())
                .json()
                .body(requestBody)
                .connectTimeout(120000)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("图片生成请求失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
        }
        return parseAndDownloadImage(resp.getBodyString());
    }

    /**
     * 解析 OpenAI 图片生成响应并下载图片。
     *
     * <p>从 JSON 响应中提取图片 URL，然后通过 HTTP GET 下载图片数据，
     * 最后解析为 {@link BufferedImage} 对象。
     *
     * @param json OpenAI 返回的 JSON 响应字符串
     * @return 生成的图片
     * @throws RuntimeException 图片数据为空、URL 为空、下载失败或解析失败时抛出
     */
    @SuppressWarnings("unchecked")
    private BufferedImage parseAndDownloadImage(String json) {
        // 解析 JSON 响应，提取图片 URL
        Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(json, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("返回的图片数据为空");
        }
        String imageUrl = (String) data.get(0).get("url");
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new RuntimeException("返回的图片 URL 为空");
        }

        // 下载图片
        ClientResponse imgResp = HttpClientFactory.of(imageUrl)
                .connectTimeout(60000)
                .get();
        if (!imgResp.isSuccess()) {
            throw new RuntimeException("下载图片失败: " + imgResp.getStatusCode());
        }

        // 解析为 BufferedImage
        try {
            return ImageIO.read(new ByteArrayInputStream(imgResp.getBody()));
        } catch (Exception e) {
            throw new RuntimeException("解析图片失败", e);
        }
    }

    @Override
    public String createTask(String prompt) {
        throw new UnsupportedOperationException("请使用 generate() 方法同步生成图片");
    }

    @Override
    public ImageResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("不支持异步任务查询，请使用 generate() 方法同步生成");
    }

    @Override
    public void close() {
        // 使用 HttpClientFactory 创建的 HTTP 客户端由框架自动管理，无需手动关闭
    }

    /**
     * 构建图片尺寸参数。
     *
     * <p>将 width 和 height 拼接为 {@code "宽x高"} 格式的字符串，
     * 如 {@code "1024x1024"}。未设置时默认返回 {@code "1024x1024"}。
     *
     * @return 尺寸字符串
     */
    private String buildSize() {
        int w = width != null ? width : 1024;
        int h = height != null ? height : 1024;
        return w + "x" + h;
    }

    /**
     * 规范化 API 基础地址。
     *
     * <p>移除末尾多余的斜杠，若未配置则使用默认地址。
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
}
