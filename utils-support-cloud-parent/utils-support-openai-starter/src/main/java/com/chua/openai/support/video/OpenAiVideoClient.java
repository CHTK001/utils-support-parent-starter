package com.chua.openai.support.video;

import com.chua.common.support.ai.video.VideoClient;
import com.chua.common.support.ai.video.VideoClientSetting;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;

/**
 * OpenAI 官方 Videos 协议的 AI 视频生成客户端。
 *
 * <p>严格只实现 OpenAI 标准端点，<b>不做任何非标准兜底</b>：</p>
 * <ol>
 *   <li>创建：{@code POST {baseUrl}/videos}，请求体
 *       {@code {model, prompt, mode, seconds, size, aspect_ratio, seed}}，
 *       响应含 {@code id / task_id / video_id}；</li>
 *   <li>轮询：{@code GET {baseUrl}/videos/{id}}，响应含
 *       {@code status(queued|in_progress|completed|failed)}、{@code progress}、
 *       终态下的 {@code url}。</li>
 * </ol>
 *
 * <p>协议形状相同的聚合渠道（OpenRouter、SiliconFlow 等）直接复用本实现；
 * 轮询端点被挪到别处的渠道（如 Agnes）走各自的 {@link VideoClient} 子类，
 * 不在本类里以运行时回退的方式兜住——那会让标准实现为非标准实现背锅，
 * 并把 401/500 这类真实错误误判成「路径不对」而掩盖掉。</p>
 *
 * <p>创建侧、分辨率换算、提示词合并等共用逻辑见 {@link AbstractOpenAiVideosClient}。</p>
 *
 * <p>调用示例：
 * <pre>{@code
 *   String taskId = VideoClient.create("openai", "sk-xxx")
 *       .model("sora-2")
 *       .size(1280, 720)
 *       .duration(5)
 *       .createTask("一只博美在草地上奔跑");
 *
 *   VideoResponse resp = client.queryTask(taskId);
 *   if (resp.getStatus() == VideoResponse.Status.SUCCESS) {
 *       String url = resp.getVideoUrl();
 *   }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("openai")
public class OpenAiVideoClient extends AbstractOpenAiVideosClient {

    /**
     * 构造 OpenAI 视频生成客户端。
     *
     * @param setting 客户端配置，含 provider、appKey、baseUrl
     */
    public OpenAiVideoClient(VideoClientSetting setting) {
        super(setting);
    }

    /**
     * 降级用的非标准实现，懒创建
     */
    private volatile AgnesVideoClient degraded;

    @Override
    /**
     * 按 OpenAI 标准路径轮询，404 时降级到非标准实现。
     *
     * <p>降级的意义：不少聚合站（如 Agnes）在库里的 {@code platform} 就是
     * {@code openai}，与 {@code OpenAiImageClient} 走同一条路由，系统无从区分
     * 「真 OpenAI」与「OpenAI 兼容但轮询端点非标准」。而它们对图像都是靠
     * {@code baseUrl} 直通来适配的，视频不该例外。</p>
     *
     * <p>因此这里不靠 platform 判别，而是<b>先打标准路径</b>；仅当返回 404
     * （该渠道未实现标准路径）时，才把轮询委托给 {@link AgnesVideoClient} 这个
     * 独立实现。判据用 404 而不是「非 2xx」是刻意的：401/403 是密钥问题、
     * 429 是限流、5xx 是服务端故障，这些必须原样暴露，降级会把它们掩盖成
     * 「路径不对」，排障时最难查的就是这种。</p>
     *
     * @param taskId 任务标识
     * @return 原始响应
     */
    ClientResponse fetchTask(String taskId) {
        ClientResponse standard = HttpClientFactory.of(normalizeBaseUrl() + "/videos/" + encode(taskId))
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .connectTimeout(60000)
                .get();
        if (standard.getStatusCode() != 404) {
            return standard;
        }
        return fallback().fetchTask(taskId);
    }

    /**
     * 取降级用的非标准实现，按需创建并复用。
     *
     * @return Agnes 视频客户端
     */
    private AgnesVideoClient fallback() {
        AgnesVideoClient cached = degraded;
        if (cached != null) {
            return cached;
        }
        VideoClientSetting target = VideoClientSetting.builder()
                .provider("agnes")
                .appKey(currentSetting().getAppKey())
                .baseUrl(currentSetting().getBaseUrl())
                .model(currentSetting().getModel())
                .build();
        cached = new AgnesVideoClient(target);
        degraded = cached;
        return cached;
    }

    @Override
    /**
     * 暂不支持图生视频。
     *
     * @param image 参考图字节
     * @return 不会正常返回
     * @throws UnsupportedOperationException 始终抛出
     */
    public VideoClient referenceImage(byte[] image) {
        throw new UnsupportedOperationException(
                "该协议的参考图需要公网可访问的地址，当前暂不支持图生视频，请改用文生视频");
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
        throw new UnsupportedOperationException("该渠道不支持参考图强度");
    }
}
