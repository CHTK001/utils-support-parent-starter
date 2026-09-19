package com.chua.deeplearning.support.dl4j;

/**
 * 训练状态枚举。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum TrainStatus {

    /**
     * 等待开始。
     */
    PENDING,

    /**
     * 训练中。
     */
    RUNNING,

    /**
     * 训练完成。
     */
    SUCCESS,

    /**
     * 训练失败。
     */
    FAILED,

    /**
     * 已取消。
     */
    CANCELLED
}
