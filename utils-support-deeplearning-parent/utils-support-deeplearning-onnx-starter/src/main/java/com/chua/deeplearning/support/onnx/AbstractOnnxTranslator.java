package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.translator.ITranslator;
/**
 * @author CH
 * @since 4.0.0
 */

public abstract class AbstractOnnxTranslator<I, O> implements ITranslator<I, O> {
    /** 模型名称 */
    private final String modelName;
    /**
    * 创建 抽象onnxtranslator 实例
    * @param modelName 模型名称
    */
    protected AbstractOnnxTranslator(String modelName) { this.modelName = modelName; }
    @Override public String name() { return modelName; }
    @Override public O translate(I input) { return runInference(input); }
    /** 运行推理 */
    protected abstract O runInference(I input);
}
