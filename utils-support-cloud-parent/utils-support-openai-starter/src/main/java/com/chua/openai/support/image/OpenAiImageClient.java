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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 打开AI 图片生成客户端。
 *
 * <p>基于 OpenAI DALL-E API 的 {@link ImageClient} 实现，支持 OpenAI 兼容接口的
 * 所有服务商（如 打开AI、silicon流、sense时间 等）。
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
 * }</pre>24, 1024)
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
     * 打开AI 默认 API 地址
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
     * 图片质量（如 "标准"、"hd"），仅 DALL-E 3 支持
     */
    private String quality;

    /**
     * 图片风格（如 "vivid"、"natural"），仅 DALL-E 3 支持
     */
    private String style;

    /**
     * 参考图字节（图生图）。为空表示纯文生图。
     */
    private byte[] referenceImage;

    /**
     * 参考图 MIME 类型，缺省 PNG。
     */
    private String referenceImageMime = "image/png";

    /**
     * 构造 打开AI 图片生成客户端。
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
    /**
     * 模型
    */
    public ImageClient model(String model) {
        this.model = model;
        return this;
    }
    @Override
    /**
     * 获取大小
    */
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }
    @Override
    /**
     * 提示符
    */
    public ImageClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }
    @Override
    /**
     * Quality
    */
    public ImageClient quality(String quality) {
        this.quality = quality;
        return this;
    }
    @Override
    /**
     * Style
    */
    public ImageClient style(String style) {
        this.style = style;
        return this;
    }
    @Override
    /**
     * 引用镜像：以 {@code image} 数组携带（图生图）。
     *
     * <p>OpenAI 兼容渠道（如 Agnes）的图生图在 {@code /images/generations} 的请求体里
     * 直接带 {@code image: [DataURI]}，不是 multipart，也不需要另起 edit 接口。</p>
     */
    public ImageClient referenceImage(byte[] image) {
        this.referenceImage = image == null ? null : image.clone();
        this.referenceImageMime = "image/png";
        return this;
    }
    @Override
    /**
     * 引用镜像：编码为 PNG 后同上。
     */
    public ImageClient referenceImage(BufferedImage image) {
        if (image == null) {
            this.referenceImage = null;
            return this;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            this.referenceImage = out.toByteArray();
            this.referenceImageMime = "image/png";
        } catch (IOException e) {
            throw new IllegalStateException("参考图编码失败: " + e.getMessage(), e);
        }
        return this;
    }
    @Override
    /**
     * 镜像strength
    */
    public ImageClient imageStrength(double strength) { throw new UnsupportedOperationException("该服务商不支持参考图强度"); }
    @Override
    /**
     * control类型
    */
    public ImageClient controlType(String controlType) { throw new UnsupportedOperationException("该服务商不支持ControlNet"); }

    /**
     * 参考图请求载荷：Data URI 数组。
     *
     * <p>OpenAI 兼容的图生图以 {@code image} 数组携带输入图，元素可为公网 URL 或
     * Data URI（Agnes 两种都收）。这里统一编码为 Data URI，避免依赖外链可访问性；
     * 多图合成时按传入顺序排列。</p>
     *
     * @return 载荷列表；无参考图时返回空列表
     */
    private List<String> referenceImagePayload() {
        if (referenceImage == null || referenceImage.length == 0) {
            return List.of();
        }
        return List.of("data:" + referenceImageMime + ";base64,"
                + Base64.getEncoder().encodeToString(referenceImage));
    }

    @Override
    /**
     * Generate
    */
    public BufferedImage generate(String prompt) {
        // 优先使用方法参数，其次使用链式设置的值
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }

 // 构建 打开AI 图片生成请求体
        List<String> imagePayload = referenceImagePayload();
        String requestBody = JsonObject.create()
                .fluentPut("model", model != null ? model : "dall-e-3")
                .fluentPut("prompt", actualPrompt)
                .fluentPut("n", 1)
                .fluentPut("size", buildSize())
                .fluentPut(quality != null && !quality.isBlank(), "quality", quality)
                // 刻意不发 style：多数 OpenAI 兼容渠道（如 Agnes）不认该参数，
                // 会把整个请求判 400「style is not supported」。
                // 风格语义应由调用方拼进 prompt 描述，而不是当作独立入参。
                .fluentPut(!imagePayload.isEmpty(), "image", imagePayload)
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
     * 解析 打开AI 图片生成响应并下载图片。
     *
     * <p>从 JSON 响应中提取图片 URL，然后通过 HTTP GET 下载图片数据，
     * 最后解析为 {@link BufferedImage} 对象。
     *
     * @param json 打开AI 返回的 JSON 响应字符串
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
        String imageUrl = (String) data.getFirst().get("url");
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

 // 解析为 缓冲镜像
        try {
            return ImageIO.read(new ByteArrayInputStream(imgResp.getBody()));
        } catch (Exception e) {
            throw new RuntimeException("解析图片失败", e);
        }
    }

    @Override
    /**
     * 创建任务
    */
    public String createTask(String prompt) {
        throw new UnsupportedOperationException("请使用 generate() 方法同步生成图片");
    }

    @Override
    /**
     * 查询任务
    */
    public ImageResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("不支持异步任务查询，请使用 generate() 方法同步生成");
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
 // 使用 http客户端工厂 创建的 HTTP 客户端由框架自动管理，无需手动关闭
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
