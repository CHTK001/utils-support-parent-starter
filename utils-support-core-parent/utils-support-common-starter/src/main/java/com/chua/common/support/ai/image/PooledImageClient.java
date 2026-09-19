package com.chua.common.support.ai.image;

import com.chua.common.support.pool.AbstractPooledClient;

import java.util.List;
import java.util.function.Supplier;

/**
 * 池化的 {@link ImageClient} 包装器
 *
 * <p>持有一个底层 {@link ImageClient} 工厂, 通过对象池复用底层实例。
 * 适合将任意 SPI 加载的 {@link ImageClient} (如 AlibabaImageClient, OpenAiImageClient 等)
 * 包装为池化客户端, 提升并发吞吐量。
 *
 * <p>使用示例:
 * <pre>{@code
 *   // 单例 (默认)
 *   ImageClient client = new PooledImageClient("openai", "sk-xxx", c -> c.model("dall-e-3"));
 *
 *   // 启用 4 个实例的对象池
 *   client.pool(4);
 *
 *   // 调用方法, 内部会 borrow / return
 *   BufferedImage img = client.generate("a cat");
 * }</pre>
 *
 * <p>所有方法会从池中借出底层客户端执行, 执行完毕自动归还。
 * 单例模式下 borrowClient 始终返回同一实例。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PooledImageClient extends AbstractPooledClient<ImageClient> implements ImageClient {

    /**
     * 服务商名称
     */
    private final String provider;

    /**
     * 供应商配置 (创建底层客户端时使用)
     */
    private final ImageClientSetting setting;

    /**
     * 链式配置回调 (可选, 每次 borrow 后应用最新配置)
     */
    private final java.util.function.Function<ImageClient, ImageClient> chainConfigurer;


    /**
     * 构造方法 (使用 setting 复用 ImageClient.create)
     *
     * @param provider 服务商
     * @param setting  配置
     */
    public PooledImageClient(String provider, ImageClientSetting setting) {
        this(provider, setting, null);
    }


    /**
     * 构造方法 (链式配置)
     *
     * @param provider        服务商
     * @param setting         配置
     * @param chainConfigurer 链式配置回调, 在 borrow 后应用, 可为 null
     */
    public PooledImageClient(String provider, ImageClientSetting setting,
                             java.util.function.Function<ImageClient, ImageClient> chainConfigurer) {
        super(() -> ImageClient.create(setting), null);
        this.provider = provider;
        this.setting = setting;
        this.chainConfigurer = chainConfigurer;
    }


    /**
     * 构造方法 (自定义工厂)
     *
     * @param factory 创建底层 ImageClient 的工厂
     */
    public PooledImageClient(Supplier<ImageClient> factory) {
        super(factory, null);
        this.provider = null;
        this.setting = null;
        this.chainConfigurer = null;
    }


    /**
     * 构造方法 (自定义工厂 + 链式配置)
     *
     * @param factory         创建底层 ImageClient 的工厂
     * @param chainConfigurer 链式配置回调
     */
    public PooledImageClient(Supplier<ImageClient> factory,
                             java.util.function.Function<ImageClient, ImageClient> chainConfigurer) {
        super(factory, chainConfigurer);
        this.provider = null;
        this.setting = null;
        this.chainConfigurer = chainConfigurer;
    }


    @Override
    /**
     * Provider
    */
    public ImageClient provider(String provider) {
        return this;
    }


    @Override
    /**
     * Model
    */
    public ImageClient model(String model) {
        return this;
    }


    @Override
    /**
     * 获取大小
    */
    public ImageClient size(int width, int height) {
        return this;
    }


    @Override
    /**
     * Prompt
    */
    public ImageClient prompt(String prompt) {
        return this;
    }


    @Override
    /**
     * NegativePrompt
    */
    public ImageClient negativePrompt(String negativePrompt) {
        return this;
    }


    @Override
    /**
     * Quality
    */
    public ImageClient quality(String quality) {
        return this;
    }


    @Override
    /**
     * Style
    */
    public ImageClient style(String style) {
        return this;
    }


    @Override
    /**
     * Seed
    */
    public ImageClient seed(Long seed) {
        return this;
    }


    @Override
    /**
     * Steps
    */
    public ImageClient steps(Integer steps) {
        return this;
    }


    @Override
    /**
     * ReferenceImage
    */
    public ImageClient referenceImage(byte[] image) {
        return this;
    }


    @Override
    /**
     * ReferenceImage
    */
    public ImageClient referenceImage(java.awt.image.BufferedImage image) {
        return this;
    }


    @Override
    /**
     * ImageStrength
    */
    public ImageClient imageStrength(double strength) {
        return this;
    }


    @Override
    /**
     * ControlType
    */
    public ImageClient controlType(String controlType) {
        return this;
    }


    @Override
    /**
     * Generate
    */
    public java.awt.image.BufferedImage generate(String prompt) {
        ImageClient inner = borrowClient();
        try {
            applyChain(inner);
            return inner.generate(prompt);
        } finally {
            returnClient(inner);
        }
    }


    @Override
    /**
     * 创建Task
    */
    public String createTask(String prompt) {
        ImageClient inner = borrowClient();
        try {
            applyChain(inner);
            return inner.createTask(prompt);
        } finally {
            returnClient(inner);
        }
    }


    @Override
    /**
     * 查询Task
    */
    public ImageResponse queryTask(String taskId) {
        ImageClient inner = borrowClient();
        try {
            applyChain(inner);
            return inner.queryTask(taskId);
        } finally {
            returnClient(inner);
        }
    }


    @Override
    /**
     * 关闭
    */
    public void close() {
        shutdown();
    }


    @Override
    /**
     * Models
    */
    public List<com.chua.common.support.ai.chat.ModelDefinition> models() {
        ImageClient inner = borrowClient();
        try {
            applyChain(inner);
            return inner.models();
        } finally {
            returnClient(inner);
        }
    }


    @Override
    /**
     * ListModels
    */
    public List<String> listModels() {
        ImageClient inner = borrowClient();
        try {
            applyChain(inner);
            return inner.listModels();
        } finally {
            returnClient(inner);
        }
    }


    /**
     * 应用链式配置到借出的实例
     * @param inner 方法入参 inner
     */
    private void applyChain(ImageClient inner) {
        if (chainConfigurer != null) {
            chainConfigurer.apply(inner);
        }
    }


    /**
     * 获取服务商名称
     *
     * @return provider
     */
    public String getProvider() {
        return provider;
    }


    /**
     * 获取客户端配置
     *
     * @return setting
     */
    public ImageClientSetting getSetting() {
        return setting;
    }
}
