package com.chua.runtime.apm.handler;

import lombok.Builder;
import lombok.Data;

/**
* 日志条目 — 由 日志处理器 在收到 SLF4J/JUL/日志4j 等日志框架插桩事件后构造并收集。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
public class LogEntry {

    /**
    * 时间戳（毫秒）
     */
    private long timestamp;

    /**
    * 日志级别（信息 / 调试 / WARN / 错误 / 追踪）
     */
    private String level;

    /**
    * 日志记录器 名称（点分隔格式，如 {@code com.example.biz.BusinessLogger}）
     */
    private String logger;

    /**
    * 日志消息内容
     */
    private String message;

    /**
    * 调用方类名（点分隔格式）
     */
    private String className;

    /**
    * 调用方方法名
     */
    private String methodName;

    /**
    * 关联异常（可空）
     */
    private Throwable throwable;
}
