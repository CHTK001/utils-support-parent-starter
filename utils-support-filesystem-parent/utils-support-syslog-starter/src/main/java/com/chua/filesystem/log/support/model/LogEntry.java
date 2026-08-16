package com.chua.filesystem.log.support.model;

import javax.annotation.Nonnull;

/**
 * 系统日志条目
 *
 * @param timestamp 时间戳 (ISO-8601)
 * @param level     日志级别
 * @param source    日志源 (如 System, Application, Security, journald)
 * @param message   日志消息内容
 * @param provider  提供者名称 (windows, linux, macos)
 * @param rawData   原始数据 (平台特定的原生格式)
 *
 * @author CH
 * @since 4.0.0.42
 */
public record LogEntry(
        @Nonnull String timestamp,
        @Nonnull LogLevel level,
        @Nonnull String source,
        @Nonnull String message,
        @Nonnull String provider,
        String rawData
) {
}
