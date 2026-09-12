package com.chua.deeplearning.support.model;

/**
* 预测结果接口。
* <p>所有模型预测结果的统一抽象。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface PredictResult {

    /**
    * 是否预测成功。
    *
    * @return true 成功
     */
    boolean isSuccess();

    /**
    * 模型名称。
    *
    * @return 模型标识
     */
    String getModelName();

    /**
    * 预测耗时。
    *
    * @return 毫秒
     */
    long getCostMs();

    /**
    * 获取原始结果。
    *
    * @param <T> 类型
    * @return 原始结果对象
     */
    <T> T getRaw();
}
