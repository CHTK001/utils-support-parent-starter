package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * SafeTensor 引擎实现。
 *
 * <p>提供模型实例化和 LLM 翻译能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SafeTensorIdentificationEngine implements IdentificationEngine {

    @Override
    public <T> T get(String modelId, Class<T> target) {
        return null;
    }

    @Override
    public <T> T get(Class<T> target) {
        return null;
    }

    @Override
    public void register(TranslatorModelDefinition definition) {
    }

    @Override
    public List<ModelDefinition> getModels() {
        return new ArrayList<>();
    }

    @Override
    public List<TranslatorModelDefinition> getTranslatorModels() {
        return new ArrayList<>();
    }

    @Override
    public void close() {
    }
}