package com.chua.runtime.apm.storage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 日志事件 — 持久化用扁平 record。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogRecord {

    /**
     * 标识
     */
    private long id;

    /**
     * 日志时间戳（毫秒）
    */
    private long timestamp;

    /**
     * 日志级别（信息/WARN/错误/调试）
    */
    private String level;

    /**
     * 日志记录器 名（业务 日志记录器）
    */
    private String logger;

    /**
     * 触发日志的类名
    */
    private String className;

    /**
     * 触发日志的方法名
    */
    private String methodName;

    /**
     * 日志消息
    */
    private String message;

    /**
     * 关联 追踪id
    */
    private String traceId;
}