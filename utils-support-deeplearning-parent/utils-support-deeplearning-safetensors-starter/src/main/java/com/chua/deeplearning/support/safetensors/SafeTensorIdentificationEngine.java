package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

/**
 * safetensor 引擎实现。
 *
 * <p>提供模型实例化和 LLM 翻译能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SafeTensorIdentificationEngine implements IdentificationEngine {

    /**
     * 获取
    */
    @Override
    public <T> T get(String modelId, Class<T> target) {
        return null;
    }

    /**
     * 获取
    */
    @Override
    public <T> T get(Class<T> target) {
        return null;
    }

    /**
     * 注册
    */
    @Override
    public void register(TranslatorModelDefinition definition) {
    }

    /**
     * 获取模型
    */
    @Override
    public List<ModelDefinition> getModels() {
        return Wraps.list();
    }

    /**
     * 获取translator模型
    */
    @Override
    public List<TranslatorModelDefinition> getTranslatorModels() {
        return Wraps.list();
    }

    /**
     * 按能力接口查询模型名称（Safetensors 引擎暂无注册模型，返回空列表）
    */
    @Override
    public List<String> getModelNamesByCapability(Class<?> capabilityInterface) {
        return Wraps.list();
    }

    /**
     * 按能力标签查询模型名称（Safetensors 引擎暂无注册模型，返回空列表）
    */
    @Override
    public List<String> getModelNamesByCapability(String capability) {
        return Wraps.list();
    }

    /**
     * 关闭
    */
    @Override
    public void close() {
    }
}