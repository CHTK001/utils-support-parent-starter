package com.chua.common.support.ai.video;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;

/**
 * AI 视频生成客户端接口
 *
 * <p>提供统一的 AI 视频生成服务抽象。与 {@code ImageClient} 不同，
 * 视频生成通常耗时较长，本接口仅提供异步任务模式。
 *
 * <p>使用示例：
 * <pre>{@code
 *   // 创建客户端并提交视频生成任务
 *   String taskId = VideoClient.create("openai", "sk-xxx")
 *       .model("sora")
 *       .size(1920, 1080)
 *       .duration(10)
 *       .style("cinematic")
 *       .createTask("一只在草原上奔跑的狼");
 *
 *   // 轮询查询任务结果
 *   VideoResponse resp = client.queryTask(taskId);
 *   if (resp.getStatus() == VideoResponse.Status.SUCCESS) {
 *       String videoUrl = resp.getVideoUrl();
 *   }
 * }</pre>
 *
 * @author CH
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public interface VideoClient extends AutoCloseable {

    /**
     * 创建指定 provider 的视频生成客户端
     *
     * @param provider AI 服务商名称，如 "openai"、"runway" 等
     * @param apiKey   API 密钥
     * @return VideoClient 实例
     */
    static VideoClient create(String provider, String apiKey) {
        return ServiceProvider.of(VideoClient.class)
                .getNewExtension(provider, VideoClientSetting.builder()
                        .provider(provider).appKey(apiKey).build());
    }

    /**
     * 通过完整配置创建视频生成客户端
     *
     * @param setting 客户端配置，包含 provider、apiKey、baseUrl、model 等
     * @return VideoClient 实例
     */
    static VideoClient create(VideoClientSetting setting) {
        return ServiceProvider.of(VideoClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
     * 创建指定 provider 和自定义地址的视频生成客户端
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @param baseUrl  自定义 API 基地址
     * @return VideoClient 实例
     */
    static VideoClient create(String provider, String apiKey, String baseUrl) {
        return ServiceProvider.of(VideoClient.class)
                .getNewExtension(provider, VideoClientSetting.builder()
                        .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    /**
     * 设置 AI 服务商
     *
     * @param provider 服务商名称
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称
     *
     * @param model 模型名称，如 "sora"、"gen-2" 等
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient model(String model) {
        return this;
    }

    /**
     * 设置生成视频的分辨率
     *
     * @param width  视频宽度（像素）
     * @param height 视频高度（像素）
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient size(int width, int height) {
        return this;
    }

    /**
     * 设置正向提示词
     *
     * @param prompt 视频内容描述文本
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient prompt(String prompt) {
        return this;
    }

    /**
     * 设置反向提示词，指定不希望出现在视频中的内容
     *
     * @param negativePrompt 反向提示词
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient negativePrompt(String negativePrompt) {
        return this;
    }

    /**
     * 设置视频时长（秒）
     *
     * @param duration 视频时长，单位为秒
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient duration(Integer duration) {
        return this;
    }

    /**
     * 设置视频质量
     *
     * @param quality 质量等级
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient quality(String quality) {
        return this;
    }

    /**
     * 设置视频风格
     *
     * @param style 风格描述，如 "cinematic"、"anime"、"realistic" 等
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient style(String style) {
        return this;
    }

    /**
     * 设置随机种子
     *
     * @param seed 随机种子值，固定种子可保证多次生成结果可复现
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient seed(Long seed) {
        return this;
    }

    /**
     * 设置参考图（字节数组）
     *
     * <p>用于图生视频（img2vid）场景，以参考图为基础生成视频。
     *
     * @param image 参考图字节数据
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient referenceImage(byte[] image) {
        return this;
    }

    /**
     * 设置参考图影响强度
     *
     * <p>控制参考图对生成结果的影响程度，取值范围 0.0 ~ 1.0。
     *
     * @param strength 影响强度
     * @return 当前客户端实例，支持链式调用
     */
    default VideoClient imageStrength(double strength) {
        return this;
    }

    /**
     * 创建视频生成任务（异步模式）
     *
     * <p>提交任务后立即返回任务 ID，不等待任务完成。需配合 {@link #queryTask(String)} 轮询结果。
     *
     * @param prompt 视频内容描述提示词
     * @return 任务 ID，用于后续查询任务状态和结果
     */
    String createTask(String prompt);

    /**
     * 查询视频生成任务状态和结果
     *
     * @param taskId 任务 ID，由 {@link #createTask(String)} 返回
     * @return 任务状态及结果，包含进度、视频 URL 等信息
     */
    VideoResponse queryTask(String taskId);

    /**
     * 关闭客户端，释放底层资源
     */
    @Override
    default void close() {
    }

    /**
     * 获取服务商支持的模型列表
     *
     * <p>调用 {@code GET {baseUrl}/v1/models} 接口查询可用的视频生成模型。
     * 默认返回空列表，子类可按需覆写。
     *
     * @return 可用模型 ID 列表
     */
    default List<ModelDefinition> models() {
        return List.of();
    }
}