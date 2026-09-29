package com.chua.openai.support.video;

import com.chua.common.support.ai.video.VideoClient;
import com.chua.common.support.ai.video.VideoClientSetting;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

/**
 * Agnes（{@code apihub.agnes-ai.cn} / {@code apihub.agnes-ai.com}）的视频生成客户端。
 *
 * <p><b>为什么单独一个实现</b>：Agnes 的<em>创建</em>端点是标准的
 * {@code POST /v1/videos}，但<em>轮询</em>端点不是标准的 {@code /v1/videos/{id}}，
 * 而是站点根路径下的 {@code /agnesapi?video_id=..&model_name=..}，且强制要求带
 * {@code model_name}（不带时只有 {@code mode=text} 的任务能查）。
 * 这种「半兼容」形态放进 {@link OpenAiVideoClient} 里做运行时回退，会让标准实现替
 * 非标准实现背锅，还会把 401/500 误判成「路径不对」而掩盖真实错误，
 * 因此独立成类，共享逻辑下沉到 {@link AbstractOpenAiVideosClient}。</p>
 *
 * <p><b>参考图走公网 URL</b>：Agnes 的 {@code first_frame} / {@code images} 收的是
 * <em>公网可访问的图片地址</em>，不接受字节也不接受 data URI。因此本实现走
 * {@link #referenceImageUrl(String)}，由调用方先把参考图落到文件存储服务再取限时链接
 * （见 {@code spring-support-filesystem-starter} 的 {@code /ot} 一次性/限时访问链接）。
 * 字节入参 {@link #referenceImage(byte[])} 直接拒绝，避免静默丢弃用户参考图后
 * 照样生成并计费。</p>
 *
 * <p>模型：{@code agnes-video-2.5-flash}（文档写作 {@code agnes-video-2.5}，
 * 实际可用的以模型管理中登记的为准）。</p>
 *
 * <p>时长限 {@code "4"}~{@code "12"} 秒；分辨率用 {@code 720P|1080P|1K|2K} 档位
 * 搭配 {@code 21:9|16:9|4:3|1:1|3:4|9:16} 宽高比，由基类按像素换算。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("agnes")
public class AgnesVideoClient extends AbstractOpenAiVideosClient {

    /**
     * Agnes 默认 API 地址。
     */
    private static final String DEFAULT_URL = "https://apihub.agnes-ai.cn/v1";

    /**
     * 参考图的公网可访问地址（图生视频用）。
     */
    private String referenceImageUrl;

    /**
     * 构造 Agnes 视频生成客户端。
     *
     * @param setting 客户端配置，含 provider、appKey、baseUrl
     */
    public AgnesVideoClient(VideoClientSetting setting) {
        super(setting);
        this.referenceImageUrl = setting.getReferenceImageUrl();
    }

    @Override
    String videoMode() {
        // 有公网参考图才走 keyframe；协议里 mode 与媒体字段强绑定，无图时必须退回 text
        return referenceImageUrl == null || referenceImageUrl.isBlank() ? "text" : "keyframe";
    }

    @Override
    JsonObject buildRequestBody(String model, String prompt, String mode, int seconds,
            String tier, String aspectRatio, Long seed) {
        boolean keyframe = "keyframe".equals(mode)
                && referenceImageUrl != null && !referenceImageUrl.isBlank();
        return JsonObject.create()
                .fluentPut("model", model)
                .fluentPut("prompt", prompt)
                // mode 是必填项，且与参考图字段强绑定：传 keyframe 却缺图会被判 400
                .fluentPut("mode", keyframe ? "keyframe" : "text")
                .fluentPut("seconds", String.valueOf(seconds))
                .fluentPut("size", tier)
                .fluentPut("aspect_ratio", aspectRatio)
                .fluentPut(keyframe, "first_frame", referenceImageUrl)
                .fluentPut(seed != null, "seed", seed);
    }

    @Override
    /**
     * 暂不支持字节入参的参考图。
     *
     * <p>Agnes 只收公网 URL。调用方应改用 {@link #referenceImageUrl(String)}，
     * 由文件存储服务把字节落盘后换成限时链接。</p>
     *
     * @param image 参考图字节
     * @return 不会正常返回
     * @throws UnsupportedOperationException 始终抛出
     */
    public VideoClient referenceImage(byte[] image) {
        throw new UnsupportedOperationException(
                "Agnes 图生视频需要公网可访问的参考图地址，请改用 referenceImageUrl(String)");
    }

    @Override
    /**
     * 暂不支持参考图强度。
     *
     * @param strength 影响强度
     * @return 不会正常返回
     * @throws UnsupportedOperationException 始终抛出
     */
    public VideoClient imageStrength(double strength) {
        throw new UnsupportedOperationException("Agnes 不支持参考图强度");
    }

    /**
     * 设置参考图的公网可访问地址。
     *
     * @param url 公网可访问的图片地址
     * @return 当前客户端（链式）
     */
    @Override
    public VideoClient referenceImageUrl(String url) {
        this.referenceImageUrl = url;
        return this;
    }

    @Override
    /**
     * 声明本实现支持 URL 形态的参考图。
     *
     * @return 恒为 true
     */
    public boolean supportsReferenceImageUrl() {
        return true;
    }

    @Override
    /**
     * 按 Agnes 站点根路径轮询。
     *
     * <p>端点形如 {@code GET https://apihub.agnes-ai.cn/agnesapi?video_id=..&model_name=..}，
     * <b>不在 {@code /v1} 之下</b>，故不能复用 baseUrl，要取站点根。</p>
     *
     * @param taskId 视频 ID
     * @return 原始响应
     */
    ClientResponse fetchTask(String taskId) {
        StringBuilder url = new StringBuilder(siteOrigin())
                .append("/agnesapi?video_id=").append(encode(taskId));
        String model = currentModel();
        if (model != null && !model.isBlank()) {
            // 非 text 模式的任务必须带 model_name，否则查不到
            url.append("&model_name=").append(encode(model));
        }
        return HttpClientFactory.of(url.toString())
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .connectTimeout(60000)
                .get();
    }

    /**
     * 取站点根地址（scheme + host），去掉版本段。
     *
     * @return 站点根地址；URL 非法时退回 baseUrl 本身
     */
    private String siteOrigin() {
        String base = settingBaseUrl();
        try {
            URI uri = URI.create(base);
            if (uri.getScheme() != null && uri.getAuthority() != null) {
                return uri.getScheme() + "://" + uri.getAuthority();
            }
        } catch (IllegalArgumentException e) {
            log.warn("解析 Agnes API 站点根失败，直接用 baseUrl: {}", base);
        }
        return base;
    }

    /**
     * 取基础地址，未配置时用 Agnes 默认值。
     *
     * @return 去掉末尾斜杠的 baseUrl
     */
    private String settingBaseUrl() {
        String url = normalizeBaseUrl();
        // normalizeBaseUrl 在 baseUrl 为空时回落到 OpenAI 官方地址；Agnes 要用自己的默认站点
        return "https://api.openai.com/v1".equals(url) ? DEFAULT_URL : url;
    }
}
