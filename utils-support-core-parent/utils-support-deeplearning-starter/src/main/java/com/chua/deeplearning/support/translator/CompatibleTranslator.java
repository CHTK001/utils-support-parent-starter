package com.chua.deeplearning.support.translator;

/**
 * ONNX 翻译器接口（旧版兼容）。
 * <p>此接口保留旧版命名，与 Translator 实现通过此类来兼容新版架构。
 * 实现类应改继承 {@code com.chua.deeplearning.support.onnx.AbstractOnnxTranslator}。</p>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author CH
 * @since 4.0.0.42
 */
@Deprecated
public interface CompatibleTranslator<I, O> extends ITranslator<I, O> {

    /**
     * 获取旧版翻译器实例（用于反射和适配）。
     *
     * @return 翻译器实例
     */
    Object getDelegate();
}
