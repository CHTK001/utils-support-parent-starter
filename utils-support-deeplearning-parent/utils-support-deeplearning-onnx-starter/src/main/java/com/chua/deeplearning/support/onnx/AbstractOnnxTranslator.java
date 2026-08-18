package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.translator.ITranslator;
/**
 * @author CH
 */

public abstract class AbstractOnnxTranslator<I, O> implements ITranslator<I, O> {
    /** 模型名称 */
    /** 模型名称 */
    private final String modelName;
    protected AbstractOnnxTranslator(String modelName) { this.modelName = modelName; }
    @Override public String name() { return modelName; }
    @Override public O translate(I input) { return runInference(input); }
    protected abstract O runInference(I input);
}