package com.chua.deeplearning.support.translator;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.model.PredictResult;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
* 翻译器模型定义。
* <p>描述一个可加载 AI 模型的完整信息，包括翻译器实例、模型大小、配置等。
* 各模型实现通过 SPI 注册此类实例，由 {@link com.chua.deeplearning.support.engine.IdentificationEngine} 自动发现。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Getter
@Builder
public class TranslatorModelDefinition {

    /**
    * 模型基本定义
    */
    private final ModelDefinition modelDefinition;

    /**
    * 翻译器实例
    */
    private final ITranslator<?, ?> translator;

    /**
    * 模型文件大小（字节）
    */
    private final long modelSize;

    /**
    * 模型配置参数
    */
    private final Map<String, Object> config;

    /**
    * 获取模型名称。
    *
    * @return 名称
    */
    public String getName() {
        return modelDefinition != null ? modelDefinition.getName() : null;
    }

    /**
    * 获取模型提供方。
    *
    * @return 提供方
    */
    public String getProvider() {
        return modelDefinition != null ? modelDefinition.getProvider() : null;
    }

    /**
    * 获取模型文件路径。
    *
    * @return 路径
    */
    public String modelPath() {
        if (config != null) {
            return (String) config.get("path");
        }
        return null;
    }
}
