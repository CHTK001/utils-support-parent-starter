package com.chua.common.support.lang.directory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 目录事件观察者，承载单个文件变更事件的上下文信息。
 * <p>通过 Builder 模式构建，包含事件路径、触发文件、时间戳、文件大小等元数据。</p>
 *
 * @author CH
 * @since 2024/12/12
 */
@Data
@Builder
public class EventObserver {

    /**
     * 被监听的父目录路径
     */
    private String currentPath;

    /**
     * 触发事件的文件或目录名称（相对路径，不含父目录）
     */
    private String triggerFile;

    /**
     * 事件源对象，可为文件路径字符串或其他自定义来源
     */
    private Object source;

    /**
     * 事件发生时间戳，默认当前时间
     */
    @Builder.Default
    /** 时间戳 */
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
    * 事件类型：CREATE / MODIFY / DELETE / OVERFLOW
    */
    private WatcherEvent eventType;

    /**
     * 触发事件的文件大小（字节），仅 CREATE 和 MODIFY 时有值
     */
    private Long fileSize;

    /**
     * 是否为目录事件
     */
    @Builder.Default
    /** Directory */
    private boolean directory = false;

    /**
    * 获取事件文件的完整路径。
    * <p>将 {@link #currentPath} 和 {@link #triggerFile} 拼接为完整路径，自动处理分隔符。</p>
    *
    * @return 完整路径，若 currentPath 或 triggerFile 为空则返回 null
    */
    public String getFullPath() {
        if (currentPath == null || triggerFile == null) {
            return null;
        }
        String sep = currentPath.endsWith("/") || currentPath.endsWith("\\") ? "" : "/";
        return currentPath + sep + triggerFile;
    }
}
