package com.chua.datasync.agent.support.exception;

/**
 * 数据同步异常基类。
 * <p>
 * 包含错误码和详细信息，便于快速定位问题。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DataSyncAgentException extends RuntimeException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final DataSyncErrorCode errorCode;

    /**
     * 错误参数
     */
    private final Object[] args;

    /**
     * 构造同步异常。
     *
     * @param message 错误消息
     */
    public DataSyncAgentException(String message) {
        super(message);
        this.errorCode = DataSyncErrorCode.UNKNOWN;
        this.args = null;
    }

    /**
     * 构造同步异常。
     *
     * @param errorCode 错误码
     */
    public DataSyncAgentException(DataSyncErrorCode errorCode) {
        super(errorCode.format());
        this.errorCode = errorCode;
        this.args = null;
    }

    /**
     * 构造同步异常。
     *
     * @param errorCode 错误码
     * @param args 错误消息参数
     */
    public DataSyncAgentException(DataSyncErrorCode errorCode, Object... args) {
        super(errorCode.format(args));
        this.errorCode = errorCode;
        this.args = args;
    }

    /**
     * 构造同步异常（带 cause）。
     *
     * @param errorCode 错误码
     * @param cause 原因
     */
    public DataSyncAgentException(DataSyncErrorCode errorCode, Throwable cause) {
        super(errorCode.format(), cause);
        this.errorCode = errorCode;
        this.args = null;
    }

    /**
      * 构造同步异常（带 cause 和 参数）。
     *
     * @param errorCode 错误码
     * @param cause 原因
     * @param args 错误消息参数
     */
    public DataSyncAgentException(DataSyncErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode.format(args), cause);
        this.errorCode = errorCode;
        this.args = args;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public DataSyncErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 获取错误参数。
     *
     * @return 错误参数
     */
    public Object[] getArgs() {
        return args;
    }

    /**
     * 获取完整错误码字符串。
     *
     * @return DSYNC-{code} 格式
     */
    public String getErrorCodeString() {
        return errorCode.codeString();
    }

    @Override
    /** 转为字符串 */
    public String toString() {
        return errorCode.codeString() + " " + getMessage();
    }
}