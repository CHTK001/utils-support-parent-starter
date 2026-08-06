package com.chua.runtime.apm.handler;

import lombok.Builder;
import lombok.Data;

/**
 * 日志条目。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class LogEntry {

    /**
     * 时间戳
     */
    private long timestamp;

    /**
     * 日志级别
     */
    private String level;

    /**
     * Logger 名称
     */
    private String logger;

    /**
     * 日志消息
     */
    private String message;

    /**
     * 类名
     */
    private String className;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 异常
     */
    private Throwable throwable;
}
