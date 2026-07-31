package com.chua.common.support.lang.directory;

/**
 * 目录监听事件类型枚举。
 * <p>定义文件目录变更的四种事件类型：创建、修改、删除、溢出，以及通配类型 ALL_KIND。</p>
 *
 * @author CH
 * @since 2024/12/12
 */
public enum WatcherEvent {

    /**
     * 文件或目录创建事件
     */
    CREATE,

    /**
     * 文件或目录内容修改事件
     */
    MODIFY,

    /**
     * 文件或目录删除事件
     */
    DELETE,

    /**
     * WatchEvent 溢出事件，通常表示事件丢失
     */
    OVERFLOW,

    /**
     * 通配类型，表示监听所有事件
     */
    ALL_KIND
}
