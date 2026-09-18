package com.chua.deeplearning.support.engine;

import com.chua.deeplearning.support.translator.TranslatorModelDefinition;

import java.util.List;

/**
* 批量模型提供者接口。
* <p>用于一次注册多个模型的场景，{@link AbstractIdentificationEngine} 会优先调用 {@link #getAll()} 注册所有模型。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface BulkModelProvider extends ModelProvider {

    /**
    * 获取所有模型定义。
    *
    * @return 模型定义列表
    */
    List<TranslatorModelDefinition> getAll();
}
