package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.translator.ITranslator;
/**
 */

public abstract class AbstractOnnxTranslator<I, O> implements ITranslator<I, O> {
    /** 模型名称 */
    private final String modelName;
    /**
     * 创建 AbstractOnnxTranslator 实例
     * @param modelName modelName
     */
    protected AbstractOnnxTranslator(String modelName) { this.modelName = modelName; }
    @Override public String name() { return modelName; }
    @Override public O translate(I input) { return runInference(input); }
    /** 运行Inference */
    protected abstract O runInference(I input);
}