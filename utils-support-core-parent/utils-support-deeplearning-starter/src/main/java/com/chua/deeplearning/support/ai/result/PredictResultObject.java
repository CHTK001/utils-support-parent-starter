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

    /**
    * 结果数据
    */
    private final T result;

    /**
    * 原始输入
    */
    private final Object input;

    /**
    * 创建空的预测结果封装。
    */
    public PredictResultObject() {
        this(null, null);
    }

    /**
    * 创建预测结果封装。
    *
    * @param result 结果数据
    * @param input  原始输入
    */
    public PredictResultObject(T result, Object input) {
        this.result = result;
        this.input = input;
    }

    /**
    * 获取结果数据。
    *
    * @return 结果数据
    */
    public T getResult() {
        return result;
    }

    /**
    * 获取原始输入。
    *
    * @return 原始输入
    */
    public Object getInput() {
        return input;
    }

    /**
    * 创建空的预测结果封装。
    *
    * @param <T> 结果类型
    * @return 空封装
    */
    public static <T> PredictResultObject<T> empty() {
        return new PredictResultObject<>(null, null);
    }

    /**
    * 创建预测结果封装。
    *
    * @param result 结果数据
    * @param input  原始输入
    * @param <T>    结果类型
    * @return 结果封装
    */
    public static <T> PredictResultObject<T> of(T result, Object input) {
        return new PredictResultObject<>(result, input);
    }
}
