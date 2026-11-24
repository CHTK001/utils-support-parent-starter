package com.chua.common.support.ai.agent;

/**
 * 工具并行执行失败异常。
 *
 * <p>包装并行批次中首个失败任务的异常；调用方可借此整体降级
 * （如退回串行执行或把失败结果回填给模型）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class AgentToolExecutionException extends RuntimeException {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 说明
     * @param cause   首个失败任务的异常
     */
    public AgentToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
