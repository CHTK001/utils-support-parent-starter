package com.chua.deeplearning.support.engine;

import ai.djl.translate.Translator;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * DJL 翻译器包装。
 * <p>将 DJL Translator 适配为框架 {@link ITranslator}，并按路径选择引擎。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DjlModelTranslator implements ITranslator<Object, Object>, AutoCloseable {

    /**
     * 模型名称。
     */
    private final String modelName;

    /**
     * 模型工厂。
     */
    private final DjlModelFactory factory;

    /**
     * 构造翻译器（自动推断引擎）。
     *
     * @param modelName     模型名称
     * @param modelPath     模型路径
     * @param djlTranslator DJL Translator
     */
    public DjlModelTranslator(String modelName, Path modelPath, Translator<?, ?> djlTranslator) {
        this(modelName, modelPath, null, djlTranslator);
    }

    /**
     * 构造翻译器。
     *
     * @param modelName     模型名称
     * @param modelPath     模型路径
     * @param engineName    引擎名称
     * @param djlTranslator DJL Translator
     */
    public DjlModelTranslator(String modelName, Path modelPath, String engineName, Translator<?, ?> djlTranslator) {
        this.modelName = modelName;
        this.factory = new DjlModelFactory(modelName, modelPath, engineName, () -> djlTranslator);
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public Object translate(Object input) {
        return factory.predict(input);
    }

    @Override
    public void close() {
        factory.close();
    }
}
