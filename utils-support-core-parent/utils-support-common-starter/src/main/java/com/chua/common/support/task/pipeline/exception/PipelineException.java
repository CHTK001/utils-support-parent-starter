package com.chua.common.support.task.pipeline.exception;


/**
 * 流水线异常。
 *
 * <p>流水线执行过程中发生的异常封装，携带节点 ID 和流水线 ID 等上下文信息，
 * 便于快速定位问题发生的具体位置。</p>
 *
 * <p>上下文字段说明：</p>
 * <ul>
 *   <li><strong>nodeId</strong> — 异常发生时的节点 ID，可能为 null（如流水线级别异常）</li>
 *   <li><strong>pipelineId</strong> — 异常发生时的流水线 ID，可能为 null</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PipelineException extends RuntimeException {

    /**
     * 异常发生时的节点 ID
     */
    private final String nodeId;

    /**
     * 异常发生时的流水线 ID
     */
    private final String pipelineId;

    /**
     * 构造流水线异常。
     *
     * @param message 异常描述
     */
    public PipelineException(String message) {
        this(message, null, null, null);
    }

    /**
     * 构造流水线异常。
     *
     * @param message 异常描述
     * @param cause   原始异常
     */
    public PipelineException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    /**
     * 构造带上下文信息的流水线异常。
     *
     * @param message    异常描述
     * @param nodeId     异常发生时的节点 ID
     * @param pipelineId 异常发生时的流水线 ID
     */
    public PipelineException(String message, String nodeId, String pipelineId) {
        this(message, nodeId, pipelineId, null);
    }

    /**
     * 构造带上下文信息和原始异常的流水线异常。
     *
     * @param message    异常描述
     * @param nodeId     异常发生时的节点 ID
     * @param pipelineId 异常发生时的流水线 ID
     * @param cause      原始异常
     */
    public PipelineException(String message, String nodeId, String pipelineId, Throwable cause) {
        super(formatMessage(message, nodeId, pipelineId), cause);
        this.nodeId = nodeId;
        this.pipelineId = pipelineId;
    }

    /**
     * 获取异常发生时的节点 ID。
     *
     * @return 节点 ID，可能为 null
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 获取异常发生时的流水线 ID。
     *
     * @return 流水线 ID，可能为 null
     */
    public String getPipelineId() {
        return pipelineId;
    }

    /**
     * 格式化异常消息，附带上下文信息。
     *
     * @param message    原始消息
     * @param nodeId     节点 ID
     * @param pipelineId 流水线 ID
     * @return 格式化后的消息
     */
    private static String formatMessage(String message, String nodeId, String pipelineId) {
        if (nodeId == null && pipelineId == null) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message);
        if (pipelineId != null) {
            sb.append(" [pipeline=").append(pipelineId).append("]");
        }
        if (nodeId != null) {
            sb.append(" [node=").append(nodeId).append("]");
        }
        return sb.toString();
    }

    @Override
    /** ToString */
    public String toString() {
        String s = getClass().getName();
        String message = getLocalizedMessage();
        StringBuilder sb = new StringBuilder(s);
        if (message != null) {
            sb.append(": ").append(message);
        }
        return sb.toString();
    }
}
