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

    private long id;

    /** 日志时间戳（毫秒） */
    private long timestamp;

    /** 日志级别（INFO/WARN/ERROR/DEBUG） */
    private String level;

    /** logger 名（业务 logger） */
    private String logger;

    /** 触发日志的类名 */
    private String className;

    /** 触发日志的方法名 */
    private String methodName;

    /** 日志消息 */
    private String message;

    /** 关联 traceId */
    private String traceId;
}