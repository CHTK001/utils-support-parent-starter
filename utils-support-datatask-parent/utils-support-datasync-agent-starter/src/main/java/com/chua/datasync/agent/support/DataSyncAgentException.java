package com.chua.datasync.agent.support;

/**
* 数据同步 Agent 异常。
*
* @author CH
* @since 4.0.0.42
 */
public class DataSyncAgentException extends RuntimeException {

    /**
    * 构造异常。
    *
    * @param message 异常消息
    */
    public DataSyncAgentException(String message) {
        super(message);
    }

    /**
    * 构造异常。
    *
    * @param message 异常消息
    * @param cause 原始异常
    */
    public DataSyncAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
