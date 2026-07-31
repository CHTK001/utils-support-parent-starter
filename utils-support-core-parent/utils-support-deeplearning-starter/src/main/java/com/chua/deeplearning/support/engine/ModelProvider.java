package com.chua.deeplearning.support.engine;

import com.chua.deeplearning.support.translator.TranslatorModelDefinition;

/**
 * 模型提供者 SPI 接口。
 * <p>各模型实现（ONNX / OpenCV）通过此接口注册 {@link TranslatorModelDefinition}，
 * 由 {@link IdentificationEngine} 自动发现并加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@FunctionalInterface
public interface ModelProvider {

    /**
     * 获取模型定义。
     *
     * @return 模型定义
     */
    TranslatorModelDefinition getDefinition();
}
