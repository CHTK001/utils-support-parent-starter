package com.chua.common.support.task.pipeline.exception;


/**
 * 流水线异常。
 *
 * <p>流水线执行过程中发生的异常封装。
 * 在节点执行失败、循环检测、节点不存在等场景下抛出。</p>
 *
 * @author CH
 */
public class PipelineException extends RuntimeException {

    /**
     * 构造流水线异常。
     *
     * @param message 异常描述
     */
    public PipelineException(String message) {
        super(message);
    }

    /**
     * 构造流水线异常。
     *
     * @param message 异常描述
     * @param cause   原始异常
     */
    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
