// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.mosi.support.image;

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
 * MOSI（MOSS 开放平台）文生图客户端
 *
 * <p>基于 MOSS Image API（{@code https://api.mosi.cn}）的 {@link ImageClient} 实现，
 * 通过 HTTP 协议调用 {@code POST /v1/images/generations} 文生图接口，
 * 支持 {@code moss-image-base-1.0} 与 {@code moss-image-sft-1.0} 两个模型。
 *
 * <p>请求参数约束：
 * <ul>
 *     <li>{@code prompt} 必填，最多 4000 字符；</li>
 *     <li>{@code negative_prompt} 可选，最多 4000 字符；</li>
 *     <li>{@code size} 格式为 {@code 宽x高}，每边 256–2048 且为 16 的倍数，
 *         总像素不超过 2097152，默认 {@code 1024x1024}；</li>
 *     <li>{@code n} 仅支持 1；</li>
 *     <li>{@code seed} 范围 0–2^53−1；</li>
 *     <li>{@code num_inference_steps} 范围 1–100；</li>
 *     <li>{@code guidance_scale} 范围 0–10；</li>
 *     <li>{@code response_format} 仅支持 {@code url}，不支持 {@code b64_json}；</li>
 *     <li>{@code prompt_enhance} 为 true 时会在同一任务内先增强提示词再生图；</li>
 *     <li>{@code output_watermark} 省略时默认开启可见水印。</li>
 * </ul>
 *
 * <p>生成成功时返回一张 PNG 图片 URL，本客户端会自动下载并解码为
 * {@link BufferedImage}。由于生成过程可能耗时较长（官方建议请求超时不低于 900 秒），
 * 默认请求超时设置为 900 秒。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"mosi", "moss"})
public class MossImageClient implements ImageClient {

    /**
     * MOSS 平台默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.mosi.cn";

    /**
     * 默认模型 ID
     */
    private static final String DEFAULT_MODEL = "moss-image-base-1.0";

    /**
     * 默认图片宽度
     */
    private static final int DEFAULT_WIDTH = 1024;

    /**
     * 默认图片高度
     */
    private static final int DEFAULT_HEIGHT = 1024;

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
     * 当前随机种子
     */
    private Long seed;

    /**
     * 当前推理步数
     */
    private Integer steps;

    /**
     * 是否增强提示词
     */
    private Boolean promptEnhance;

    /**
     * 引导系数
     */
    private Double guidanceScale;

    /**
     * 是否输出可见水印，省略时默认开启
     */
    private Boolean watermarkEnabled;

    /**
     * 构造 MOSI 平台文生图客户端
     *
     * @param setting 客户端配置
     */
    public MossImageClient(ImageClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.width = setting.getWidth();
        this.height = setting.getHeight();
        this.prompt = setting.getPrompt();
        this.negativePrompt = setting.getNegativePrompt();
        this.seed = setting.getSeed();
        this.steps = setting.getSteps();
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
     * negative提示符
     */
    @Override
    public ImageClient negativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
        return this;
    }

    /**
     * 设置是否开启提示词增强
     *
     * @param enhance true 表示先增强提示词再生图，false 表示直接使用原始提示词
     * @return 当前客户端实例，支持链式调用
     */
    public ImageClient promptEnhance(boolean enhance) {
        this.promptEnhance = enhance;
        return this;
    }

    /**
     * Seed
     */
    @Override
    public ImageClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    /**
     * Steps
     */
    @Override
    public ImageClient steps(Integer steps) {
        this.steps = steps;
        return this;
    }

    /**
     * 设置引导系数
     *
     * @param guidanceScale 引导系数，取值范围 0–10，显式传入 0 有效
     * @return 当前客户端实例，支持链式调用
     */
    public ImageClient guidanceScale(double guidanceScale) {
        this.guidanceScale = guidanceScale;
        return this;
    }

    /**
     * 设置是否输出可见水印
     *
     * @param enabled true 表示输出可见水印，false 表示关闭；省略时平台默认开启
     * @return 当前客户端实例，支持链式调用
     */
    public ImageClient watermark(boolean enabled) {
        this.watermarkEnabled = enabled;
        return this;
    }

    /**
     * Quality（MOSI 平台暂不支持该参数，忽略并保持链式调用兼容）
     */
    @Override
    public ImageClient quality(String quality) {
        return this;
    }

    /**
     * Style（MOSI 平台暂不支持该参数，忽略并保持链式调用兼容）
     */
    @Override
    public ImageClient style(String style) {
        return this;
    }

    /**
     * 引用镜像
     */
    @Override
    public ImageClient referenceImage(byte[] image) {
        throw new UnsupportedOperationException("MOSI 文生图接口不支持参考图");
    }

    /**
     * 引用镜像
     */
    @Override
    public ImageClient referenceImage(BufferedImage image) {
        throw new UnsupportedOperationException("MOSI 文生图接口不支持参考图");
    }

    /**
     * 镜像strength
     */
    @Override
    public ImageClient imageStrength(double strength) {
        throw new UnsupportedOperationException("MOSI 文生图接口不支持参考图强度");
    }

    /**
     * control类型
     */
    @Override
    public ImageClient controlType(String controlType) {
        throw new UnsupportedOperationException("MOSI 文生图接口不支持ControlNet");
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
        if (actualPrompt.length() > 4000) {
            throw new IllegalArgumentException("提示词超过 4000 字符上限");
        }
        String actualModel = model != null ? model : DEFAULT_MODEL;
        String sizeStr = buildSize();
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("{\"model\":\"").append(actualModel)
                .append("\",\"prompt\":\"").append(escapeJson(actualPrompt))
                .append("\",\"size\":\"").append(sizeStr).append("\"")
                .append(",\"n\":1")
                .append(",\"response_format\":\"url\"");
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            if (negativePrompt.length() > 4000) {
                throw new IllegalArgumentException("反向提示词超过 4000 字符上限");
            }
            bodyBuilder.append(",\"negative_prompt\":\"").append(escapeJson(negativePrompt)).append("\"");
        }
        if (promptEnhance != null) {
            bodyBuilder.append(",\"prompt_enhance\":").append(promptEnhance);
        }
        if (seed != null) {
            if (seed < 0 || seed > 9007199254740991L) {
                throw new IllegalArgumentException("seed 取值范围为 0–2^53−1");
            }
            bodyBuilder.append(",\"seed\":").append(seed);
        }
        if (steps != null) {
            if (steps < 1 || steps > 100) {
                throw new IllegalArgumentException("推理步数取值范围为 1–100");
            }
            bodyBuilder.append(",\"num_inference_steps\":").append(steps);
        }
        if (guidanceScale != null) {
            if (guidanceScale < 0 || guidanceScale > 10) {
                throw new IllegalArgumentException("引导系数取值范围为 0–10");
            }
            bodyBuilder.append(",\"guidance_scale\":").append(guidanceScale);
        }
        if (watermarkEnabled != null) {
            bodyBuilder.append(",\"output_watermark\":{\"enabled\":").append(watermarkEnabled).append("}");
        }
        bodyBuilder.append("}");
        String requestBody = bodyBuilder.toString();
        String url = normalizeBaseUrl() + "/v1/images/generations";
        log.debug("MOSI 文生图请求: url={}, model={}, size={}", url, actualModel, sizeStr);
        ClientResponse response = HttpClientFactory.of(url)
                .header("Authorization", "Bearer " + setting.getAppKey())
                .json()
                .connectTimeout(30000)
                .readTimeout(900000)
                .body(requestBody)
                .post();
        if (response.getStatusCode() != 200) {
            throw new RuntimeException("MOSI 文生图请求失败: " + response.getStatusCode()
                    + " - " + response.getBodyString());
        }
        try {
            return parseAndDownloadImage(response.getBodyString());
        } catch (IOException e) {
            throw new RuntimeException("MOSI 文生图请求失败", e);
        }
    }

    /**
     * 解析响应并下载图片
     *
     * @param json MOSI 平台返回的 JSON 响应
     * @return BufferedImage 对象
     * @throws IOException 下载或解析失败
     */
    @SuppressWarnings("unchecked")
    private BufferedImage parseAndDownloadImage(String json) throws IOException {
        Map<String, Object> root = com.chua.common.support.lang.json.Json.fromJson(json, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
        if (CollectionUtils.isEmpty(data)) {
            throw new RuntimeException("MOSI 返回的图片数据为空");
        }
        String imageUrl = (String) data.getFirst().get("url");
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new RuntimeException("MOSI 返回的图片 URL 为空");
        }
        ClientResponse imageResponse = HttpClientFactory.of(imageUrl)
                .connectTimeout(30000)
                .readTimeout(120000)
                .get();
        if (!imageResponse.isSuccess()) {
            throw new IOException("MOSI 图片下载失败: " + imageResponse.getStatusCode());
        }
        byte[] bytes = imageResponse.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new IOException("MOSI 图片下载失败: 响应体为空");
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
        throw new UnsupportedOperationException("MOSI 文生图接口不支持异步任务模式，请使用 generate() 方法同步生成");
    }

    /**
     * 查询任务
     */
    @Override
    public ImageResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("MOSI 文生图接口不支持异步任务模式");
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
        int w = width != null ? width : DEFAULT_WIDTH;
        int h = height != null ? height : DEFAULT_HEIGHT;
        validateSize(w, h);
        return w + "x" + h;
    }

    /**
     * 校验尺寸是否满足 MOSI 平台约束
     *
     * @param w 宽度
     * @param h 高度
     */
    private static void validateSize(int w, int h) {
        if (w < 256 || w > 2048 || h < 256 || h > 2048) {
            throw new IllegalArgumentException("图片尺寸每边须为 256–2048 像素");
        }
        if (w % 16 != 0 || h % 16 != 0) {
            throw new IllegalArgumentException("图片尺寸每边须为 16 的倍数");
        }
        if ((long) w * h > 2097152L) {
            throw new IllegalArgumentException("图片总像素不得超过 2097152");
        }
    }

    /**
     * 规范化 API 基础地址
     *
     * <p>本类的请求路径自带 {@code /v1}（{@code /v1/images/generations}），
     * 而供应商模板里的 {@code baseUrl} 按 OpenAI 风格填到了 {@code /v1}
     * （{@code https://api.mosi.cn/v1}）。两者直接拼接会得到
     * {@code /v1/v1/images/generations} —— 上游判 404。
     *
     * <p>这里统一剥掉结尾的 {@code /v1}，让 {@link #generate(String)} 里的
     * 路径拼接始终只出现一次 {@code /v1}；用户填 {@code https://api.mosi.cn}
     * 或 {@code https://api.mosi.cn/v1} 都能正常工作。</p>
     *
     * @return 规范化后的 URL（不含结尾 /v1）
     */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = DEFAULT_URL;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - "/v1".length());
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