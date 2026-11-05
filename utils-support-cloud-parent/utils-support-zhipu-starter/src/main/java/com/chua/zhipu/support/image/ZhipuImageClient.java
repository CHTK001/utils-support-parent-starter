// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.zhipu.support.image;

import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.ImageResponse;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 智谱 cogview 图片生成客户端
 *
 * <p>基于智谱 CogView API 的 {@link ImageClient} 实现，通过 HTTP 协议
 * 调用 cogview 系列模型的图片生成接口，兼容 打开AI 格式。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"zhipu", "glm"})
public class ZhipuImageClient implements ImageClient {

    /**
     * 智谱 cogview 默认 API 地址
     */
    private static final String DEFAULT_URL = "https://open.bigmodel.cn/api/paas/v4";

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
     * 当前质量等级
     */
    private String quality;

    /**
     * 当前风格
     */
    private String style;

    /**
     * 构造智谱 cogview 图片生成客户端
     *
     * @param setting 客户端配置
     */
    public ZhipuImageClient(ImageClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
    }

    /**
     * 模型
    */
    @Override
    public ImageClient model(String model) {
        this.model = model;
        return this;
    }

    /**
     * 获取大小
    */
    @Override
    public ImageClient size(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * 提示符
    */
    @Override
    public ImageClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    /**
     * Quality
    */
    @Override
    public ImageClient quality(String quality) {
        this.quality = quality;
        return this;
    }

    /**
     * Style
    */
    @Override
    public ImageClient style(String style) {
        this.style = style;
        return this;
    }

    /**
     * 引用镜像
    */
    @Override
    public ImageClient referenceImage(byte[] image) {
        throw new UnsupportedOperationException("该服务商不支持参考图");
    }

    /**
     * 引用镜像
    */
    @Override
    public ImageClient referenceImage(BufferedImage image) {
        throw new UnsupportedOperationException("该服务商不支持参考图");
    }

    /**
     * 镜像strength
    */
    @Override
    public ImageClient imageStrength(double strength) {
        throw new UnsupportedOperationException("该服务商不支持参考图强度");
    }

    /**
     * control类型
    */
    @Override
    public ImageClient controlType(String controlType) {
        throw new UnsupportedOperationException("该服务商不支持ControlNet");
    }

/**
 * Generate
*/
    @Override
public BufferedImage generate(String prompt) {
        String actualPrompt = prompt != null ? prompt : this.prompt;
        if (actualPrompt == null || actualPrompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        String actualModel = model != null ? model : "cogview-3";
        String sizeStr = buildSize();
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("{\"model\":\"").append(actualModel)
                .append("\",\"prompt\":\"").append(escapeJson(actualPrompt))
                .append("\",\"n\":1")
                .append(",\"size\":\"").append(sizeStr).append("\"");
        if (quality != null && !quality.isBlank()) {
            bodyBuilder.append(",\"quality\":\"").append(quality).append("\"");
        }
        if (style != null && !style.isBlank()) {
            bodyBuilder.append(",\"style\":\"").append(style).append("\"");
        }
        bodyBuilder.append("}");
        String requestBody = bodyBuilder.toString();
        ClientResponse response = HttpClientFactory.of(normalizeBaseUrl())
                .path("/images/generations")
                .header("Authorization", "Bearer " + setting.getAppKey())
                .json()
                .connectTimeout(30000)
                .readTimeout(120000)
                .body(requestBody)
                .post();
        if (response.getStatusCode() != 200) {
            throw new RuntimeException("智谱 CogView 图片生成请求失败: " + response.getStatusCode()
                    + " - " + response.getBodyString());
        }
        try {
            return parseAndDownloadImage(response.getBodyString());
        } catch (IOException e) {
            throw new RuntimeException("智谱 CogView 图片生成请求失败", e);
        }
    }

    /**
     * 解析响应并下载图片
     *
     * @param json 智谱返回的 JSON 响应
     * @return BufferedImage 对象
     * @throws IOException 下载或解析失败
     */
    @SuppressWarnings("unchecked")
    private BufferedImage parseAndDownloadImage(String json) throws IOException {
        Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(json, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
        if (CollectionUtils.isEmpty(data)) {
            throw new RuntimeException("智谱 CogView 返回的图片数据为空");
        }
        String imageUrl = (String) data.getFirst().get("url");
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new RuntimeException("智谱 CogView 返回的图片 URL 为空");
        }
        ClientResponse imageResponse = HttpClientFactory.of(imageUrl)
                .connectTimeout(30000)
                .readTimeout(60000)
                .get();
        if (!imageResponse.isSuccess()) {
            throw new IOException("下载智谱图片失败: HTTP " + imageResponse.getStatusCode());
        }
        byte[] bytes = imageResponse.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new IOException("下载智谱图片失败: 响应体为空");
        }
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        }
    }

    /**
     * 创建任务
    */
    @Override
    public String createTask(String prompt) {
        throw new UnsupportedOperationException("智谱 CogView 不支持异步任务模式，请使用 generate() 方法同步生成");
    }

    /**
     * 查询任务
    */
    @Override
    public ImageResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("智谱 CogView 不支持异步任务模式");
    }

    /**
     * 关闭
    */
    @Override
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