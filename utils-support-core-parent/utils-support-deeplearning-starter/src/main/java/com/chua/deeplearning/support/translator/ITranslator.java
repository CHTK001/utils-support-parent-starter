package com.chua.deeplearning.support.translator;

/**
 * 翻译器接口。
 * <p>将模型原始输入/输出转换为业务对象，每个 ONNX / OpenCV 模型对应一个翻译器实现。</p>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author CH
 * @since 4.0.0.42
 */
public interface ITranslator<I, O> {

    /**
     * 翻译器名称，对应模型标识。
     *
     * @return 名称
     */
    String name();

    /**
     * 执行翻译。
     *
     * @param input 模型输入
     * @return 业务输出
     */
    O translate(I input);
}
