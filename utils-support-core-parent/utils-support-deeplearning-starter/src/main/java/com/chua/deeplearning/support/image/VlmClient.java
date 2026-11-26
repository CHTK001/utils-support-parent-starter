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

    /**
     * 指令式视觉推理：按 {@link VlmRequest} 指定的任务与文本输入执行推理，
     * 返回结构化结果（框/点/掩码 + 原始响应文本）。
     *
     * <p>本方法是 {@link #understand(byte[], UnderstandTask)} 的并列能力：
     * 后者只承载 Florence-2 的固定前缀任务与纯文本输出，前者承载
     * 「检测 / 指代 / 计数 / 分割 / 抠图 / OCR 定位」等需要文本输入的指令式任务，
     * 以及框、点、掩码等结构化输出。
     *
     * <p>默认实现抛出 {@link UnsupportedOperationException}，以保证
     * 既有实现（如 Florence-2）在不改造的前提下继续满足契约；
     * 支持结构化推理的实现必须覆写本方法。
     *
     * @param request 推理请求
     * @return 结构化推理结果
     * @throws UnsupportedOperationException 当前实现不支持指令式推理
     * @since 4.0.0.51
     */
    default VlmResult infer(VlmRequest request) {
        throw new UnsupportedOperationException(
                getClass().getName() + " 未实现指令式视觉推理（VlmClient#infer）");
    }
}
