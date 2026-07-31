package com.chua.deeplearning.support.ai.result;

/**
 * 预测结果封装。
 * <p>
 * 持有任意类型结果与原始输入引用，用于统一返回格式。
 * </p>
 *
 * @param <T> 结果类型
 * @author CH
 * @since 4.0.0.42
 */
public class PredictResultObject<T> {

    private final T result;
    private final Object input;

    public PredictResultObject() {
        this(null, null);
    }

    public PredictResultObject(T result, Object input) {
        this.result = result;
        this.input = input;
    }

    public T getResult() {
        return result;
    }

    public Object getInput() {
        return input;
    }

    public static <T> PredictResultObject<T> empty() {
        return new PredictResultObject<>(null, null);
    }

    public static <T> PredictResultObject<T> of(T result, Object input) {
        return new PredictResultObject<>(result, input);
    }
}
