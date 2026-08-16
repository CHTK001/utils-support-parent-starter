package com.chua.deeplearning.support.engine;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;

import java.util.List;

/**
 * 识别引擎接口。
 * <p>管理所有 AI 模型的生命周期与查找，支持通过 SPI 自动发现 {@link TranslatorModelDefinition}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface IdentificationEngine {

    /**
     * 获取所有可用模型列表。
     *
     * @return 模型定义列表
     */
    List<ModelDefinition> getModels();

    /**
     * 获取所有翻译器模型列表。
     *
     * @return 翻译器模型定义列表
     */
    List<TranslatorModelDefinition> getTranslatorModels();

    /**
     * 按名称和目标类型获取模型实例。
     *
     * @param name   模型名称
     * @param target 目标类型
     * @param <T>    泛型
     * @return 模型实例
     */
    <T> T get(String name, Class<T> target);

    /**
     * 按目标类型获取模型实例。
     *
     * @param target 目标类型
     * @param <T>    泛型
     * @return 模型实例
     */
    <T> T get(Class<T> target);

    /**
     * 按能力接口查询所有模型名称。
     *
     * <p>如传 {@code ImageDetector.class} 返回所有图像检测模型 ID，
     * 传 {@code FeatureExtractor.class} 返回所有特征提取模型 ID。
     * 模型注册时声明的能力接口经 {@code ModelDefinition.capabilities} 标签化，
     * 这里按能力标签过滤。</p>
     *
     * @param capabilityInterface 能力接口（可为 null，返回全部）
     * @return 模型名称列表
     */
    List<String> getModelNamesByCapability(Class<?> capabilityInterface);

    /**
     * 按能力标签查询所有模型名称。
     *
     * @param capability 能力标签（如 {@code detect} / {@code feature} / {@code classify}）
     * @return 模型名称列表
     */
    List<String> getModelNamesByCapability(String capability);

    /**
     * 注册翻译器模型。
     *
     * @param definition 模型定义
     */
    void register(TranslatorModelDefinition definition);

    /**
     * 创建识别引擎实例。
     * <p>通过 SPI 自动发现已注册的引擎实现（如 ONNX），若不存在则返回默认匿名引擎。</p>
     *
     * @return 识别引擎实例
     */
    static IdentificationEngine create() {
        return AbstractIdentificationEngine.getInstance();
    }

    /**
     * 释放所有模型资源。
     */
    void close();

    /**
     * 创建指定名称的识别引擎实例。
     *
     * @param name 引擎名称
     * @return 识别引擎实例
     */
    static IdentificationEngine of(String name) {
        return AbstractIdentificationEngine.getInstance();
    }
}
