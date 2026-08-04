package com.chua.common.support.task.flow;

/**
 * 流程编排异常。
 *
 * <p>流程定义校验、节点执行、实例运行过程中的业务异常统一使用本类型，
 * 便于调用方区分流程相关错误与其他运行时异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FlowException extends RuntimeException {

    /**
     * 构造流程编排异常。
     *
     * @param message 异常描述信息
     */
    public FlowException(String message) {
        super(message);
    }

    /**
     * 构造流程编排异常。
     *
     * @param message 异常描述信息
     * @param cause   根因异常
     */
    public FlowException(String message, Throwable cause) {
        super(message, cause);
    }
}
