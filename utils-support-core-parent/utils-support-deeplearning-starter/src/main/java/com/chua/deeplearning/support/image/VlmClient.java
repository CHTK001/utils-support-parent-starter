package com.chua.deeplearning.support.image;

import com.chua.common.support.spi.ServiceProvider;

/**
 * 视觉语言模型（VLM）客户端 SPI 接口。
 *
 * <p>通过 {@link ServiceProvider} 扩展点支持多种 VLM 实现（如 Florence-2、Qwen-VL 等），
 * 统一调用方式：{@code VlmClient.create(name).model(modelName).understand(imageData, task)}。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VlmClient {

    /**
     * 创建 VLM 客户端。
     *
     * @param name 扩展名，对应 服务提供者 注册的实现
     * @return VLM 客户端实例
     */
    static VlmClient create(String name) {
        return ServiceProvider.of(VlmClient.class).getNewExtension(name);
    }

    /**
     * 指定模型名称。
     *
     * @param model 模型标识（如 florence2、通义千问-vl）
     * @return 当前客户端实例（链式调用）
     */
    VlmClient model(String model);

    /**
     * 对图像进行理解。
     *
     * @param imageData 图像字节数据
     * @param task      理解任务类型
     * @return 理解结果
     */
    UnderstandResult understand(byte[] imageData, UnderstandTask task);
}
