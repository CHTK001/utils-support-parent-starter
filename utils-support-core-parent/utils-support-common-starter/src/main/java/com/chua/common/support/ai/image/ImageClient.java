package com.chua.common.support.ai.image;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.ServiceProvider;

import java.awt.image.BufferedImage;
import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * AI 图片生成客户端接口
 *
 * <p>提供统一的 AI 图片生成服务抽象，支持同步生成和异步任务两种模式。
 * 实现类通过 SPI 机制按 provider 名称注册，调用方通过工厂方法获取实例。
 *
 * <p>同步生成示例：
 * <pre>{@code
 *   BufferedImage image = ImageClient.create("openai", "sk-xxx")
 *       .model("dall-e-3")
 *       .size(1024, 1024)
 *       .quality("hd")
 *       .generate("一只可爱的猫");
 * }</pre>
 *
 * <p>异步任务示例：
 * <pre>{@code
 *   String taskId = ImageClient.create("midjourney", "sk-xxx")
 *       .model("mj-6")
 *       .size(1024, 1024)
 *       .style("expressive")
 *       .createTask("赛博朋克城市");
 *
 *   // 轮询查询任务结果
 *   ImageResponse resp = client.queryTask(taskId);
 *   if (resp.getStatus() == ImageResponse.Status.SUCCESS) {
 *       byte[] imageBytes = resp.getImageBytes();
 *   }
 * }</pre>
 *
 * @author CH
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public interface ImageClient extends AutoCloseable {

    /**
     * 创建指定 provider 的图片生成客户端
     *
     * @param provider AI 服务商名称，如 "openai"、"midjourney"、"stable-diffusion" 等
     * @param apiKey   API 密钥
     * @return ImageClient 实例
     */
    static ImageClient create(String provider, String apiKey) {
        return ServiceProvider.of(ImageClient.class)
                .getNewExtension(provider, ImageClientSetting.builder()
                        .provider(provider).appKey(apiKey).build());
    }

    /**
     * 通过完整配置创建图片生成客户端
     *
     * @param setting 客户端配置，包含 provider、apiKey、baseUrl、model 等
     * @return ImageClient 实例
     */
    static ImageClient create(ImageClientSetting setting) {
        return ServiceProvider.of(ImageClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
     * 创建指定 provider 和自定义地址的图片生成客户端
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @param baseUrl  自定义 API 基地址
     * @return ImageClient 实例
     */
    static ImageClient create(String provider, String apiKey, String baseUrl) {
        return ServiceProvider.of(ImageClient.class)
                .getNewExtension(provider, ImageClientSetting.builder()
                        .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    /**
     * 设置 AI 服务商
     *
     * @param provider 服务商名称
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称
     *
     * @param model 模型名称，如 "dall-e-3"、"mj-6"、"sd-xl" 等
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient model(String model) {
        return this;
    }

    /**
     * 设置生成图片的尺寸
     *
     * @param width  图片宽度（像素）
     * @param height 图片高度（像素）
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient size(int width, int height) {
        return this;
    }

    /**
     * 设置正向提示词
     *
     * @param prompt 图片描述文本
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient prompt(String prompt) {
        return this;
    }

    /**
     * 设置反向提示词，指定不希望出现在图片中的内容
     *
     * @param negativePrompt 反向提示词
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient negativePrompt(String negativePrompt) {
        return this;
    }

    /**
     * 设置图片质量
     *
     * @param quality 质量等级，如 "standard"、"hd" 等
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient quality(String quality) {
        return this;
    }

    /**
     * 设置图片风格
     *
     * @param style 风格描述，如 "vivid"、"natural"、"expressive" 等
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient style(String style) {
        return this;
    }

    /**
     * 设置随机种子
     *
     * @param seed 随机种子值，固定种子可保证多次生成结果可复现
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient seed(Long seed) {
        return this;
    }

    /**
     * 设置推理步数
     *
     * @param steps 推理步数，步数越高图片细节越丰富但耗时更长
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient steps(Integer steps) {
        return this;
    }

    /**
     * 设置参考图（字节数组）
     *
     * <p>用于图生图（img2img）场景，以参考图为基础进行生成。
     * 需配合 {@link #imageStrength(double)} 控制参考图影响程度。
     *
     * @param image 参考图字节数据
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient referenceImage(byte[] image) {
        return this;
    }

    /**
     * 设置参考图（BufferedImage）
     *
     * <p>用于图生图（img2img）场景，以参考图为基础进行生成。
     *
     * @param image 参考图对象
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient referenceImage(BufferedImage image) {
        return this;
    }

    /**
     * 设置参考图影响强度
     *
     * <p>控制参考图对生成结果的影响程度，取值范围 0.0 ~ 1.0。
     * 值越接近 1.0 表示越遵循参考图，接近 0.0 则越自由。
     * 通常用于图生图（img2img）和 ControlNet 场景。
     *
     * @param strength 影响强度
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient imageStrength(double strength) {
        return this;
    }

    /**
     * 设置 ControlNet 类型
     *
     * <p>指定 ControlNet 预处理类型，用于精确控制生成结构。
     * 常见类型：canny（边缘检测）、depth（深度图）、pose（姿态）、
     * scribble（涂鸦）、mlsd（直线检测）、normal（法线贴图）等。
     *
     * @param controlType ControlNet 类型名称
     * @return 当前客户端实例，支持链式调用
     */
    default ImageClient controlType(String controlType) {
        return this;
    }

    /**
     * 同步生成图片
     *
     * <p>发送请求并等待服务端返回完整的图片数据。
     *
     * @param prompt 图片描述提示词
     * @return 生成的图片对象
     */
    BufferedImage generate(String prompt);

    /**
     * 同步生成图片（使用已配置的 prompt）
     *
     * @return 生成的图片对象
     */
    default BufferedImage generate() {
        return generate(null);
    }

    /**
     * 创建图片生成任务（异步模式）
     *
     * <p>提交任务后立即返回，不等待任务完成。需配合 {@link #queryTask(String)} 轮询结果。
     *
     * @param prompt 图片描述提示词
     * @return 任务 ID，用于后续查询任务状态和结果
     */
    String createTask(String prompt);

    /**
     * 查询图片生成任务状态和结果
     *
     * @param taskId 任务 ID，由 {@link #createTask(String)} 返回
     * @return 任务状态及结果，包含进度、图片数据等信息
     */
    ImageResponse queryTask(String taskId);

    /**
     * 关闭客户端，释放底层资源
     */
    @Override
    default void close() {
    }

    /**
     * 获取服务商支持的模型列表
     *
     * <p>调用 {@code GET {baseUrl}/v1/models} 接口查询可用的图片生成模型。
     * 默认返回空列表，子类可按需覆写。
     *
     * @return 可用模型 ID 列表
     */
    default List<ModelDefinition> models() {
        return List.of();
    }
}