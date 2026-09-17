package com.chua.common.support.ai.feature;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.pool.PooledObjectClient;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
* AI 特征提取客户端接口。
* <p>
* 提供统一的特征向量提取抽象，支持文本与图像两种输入模态，返回浮点向量。
* 实现类通过 SPI 机制按 provider 名称注册，调用方通过工厂方法获取实例。
* </p>
*
* <p>文本特征示例：
* <pre>{@code
*   float[] vector = FeatureClient.create("onnx", "")
*       .model("bge-small-zh")
*       .extract("要提取特征的文本");
* }</pre>
*
* <p>图像特征示例：
* <pre>{@code
*   float[] vector = FeatureClient.create("onnx", "")
*       .model("clip-image-feature")
*       .extractImage(imageBytes);
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public interface FeatureClient extends AutoCloseable, PooledObjectClient<FeatureClient> {

    /**
    * 创建指定 provider 的 AI 特征提取客户端。
    *
    * @param provider AI 服务商名称，如 "onnx"、"pytorch" 等
    * @param apiKey   API 密钥（本地模型可留空）
    * @return FeatureClient 实例
    */
    static FeatureClient create(String provider, String apiKey) {
        return ServiceProvider.of(FeatureClient.class)
                .getNewExtension(provider, FeatureClientSetting.builder()
                        .provider(provider).appKey(apiKey).build());
    }

    /**
    * 通过完整配置创建 AI 特征提取客户端。
    *
    * @param setting 客户端配置，包含 provider、apiKey、baseUrl、model 等
    * @return FeatureClient 实例
    */
    static FeatureClient create(FeatureClientSetting setting) {
        return ServiceProvider.of(FeatureClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
    * 创建指定 provider 和自定义地址的 AI 特征提取客户端。
    *
    * @param provider AI 服务商名称
    * @param apiKey   API 密钥
    * @param baseUrl  自定义 API 地址
    * @return FeatureClient 实例
    */
    static FeatureClient create(String provider, String apiKey, String baseUrl) {
        return ServiceProvider.of(FeatureClient.class)
                .getNewExtension(provider, FeatureClientSetting.builder()
                        .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    /**
    * 设置 AI 服务商。
    *
    * @param provider 服务商名称
    * @return 当前客户端实例，支持链式调用
    */
    default FeatureClient provider(String provider) {
        return this;
    }

    /**
    * 设置模型名称。
    *
    * @param model 模型名称，如 "bge-small-zh"、"clip-image-feature" 等
    * @return 当前客户端实例，支持链式调用
    */
    default FeatureClient model(String model) {
        return this;
    }

    /**
    * 设置输出向量维度。
    *
    * @param dimensions 向量维度
    * @return 当前客户端实例，支持链式调用
    */
    default FeatureClient dimensions(int dimensions) {
        return this;
    }

    /**
    * 提取文本特征向量。
    *
    * @param text 待提取特征的文本
    * @return 浮点数向量
    */
    float[] extract(String text);

    /**
    * 提取图像特征向量。
    *
    * @param imageData 图像字节数据
    * @return 浮点数向量
    */
    default float[] extractImage(byte[] imageData) {
        throw new UnsupportedOperationException("当前 FeatureClient 实现不支持图像特征提取");
    }

    /**
    * 异步提取文本特征向量。
    *
    * @param text 待提取特征的文本
    * @return 异步任务，完成时返回浮点数向量
    */
    default CompletableFuture<float[]> extractAsync(String text) {
        return CompletableFuture.supplyAsync(() -> extract(text));
    }

    /**
    * 获取服务商支持的模型列表。
    *
    * @return 可用模型定义列表
    */
    default List<ModelDefinition> models() {
        return List.of();
    }

    /**
    * 查询当前能力下全部可用模型 ID。
    *
    * <p>基于 {@link #models()} 提取模型 ID 列表，供统一能力清单与前端按能力筛选使用。</p>
    *
    * @return 模型 ID 列表
    */
    default List<String> listModels() {
        List<ModelDefinition> defs = models();
        if (defs == null || defs.isEmpty()) {
            return List.of();
        }
        return defs.stream()
                .filter(d -> d != null && d.getId() != null && !d.getId().isBlank())
                .map(ModelDefinition::getId)
                .toList();
    }

    /**
    * 关闭客户端，释放底层资源。
    */
    @Override
    default void close() {
    }
}
