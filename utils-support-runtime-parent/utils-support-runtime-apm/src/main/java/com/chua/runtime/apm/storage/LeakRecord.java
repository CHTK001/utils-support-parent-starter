package com.chua.runtime.apm.storage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 句柄泄漏事件 — 持久化用扁平 record。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeakRecord {

    /**
     * 内部 row 标识
    */
    private long id;

    /**
     * 唯一 处理id
    */
    private String handleId;

    /**
     * 句柄类型名（如 {@code java/io/FileInputStream}）
    */
    private String kind;

    /**
     * 名称 / 描述（路径 / URL）
    */
    private String name;

    /**
     * 持有线程名
    */
    private String thread;

    /**
     * 创建时间（毫秒）
    */
    private long createdAt;

    /**
     * 关闭时间（毫秒），未关闭为 0
    */
    private long closedAt;

    /**
     * 创建时的栈追踪（多行字符串）
    */
    private String stackTrace;

    /**
     * 当前是否仍然泄漏
     *
     * @return 是否活跃的结果
     */
    public boolean isActive() {
        return closedAt == 0L;
    }

    /**
     * 持续时长（毫秒）
     *
     * @param now now
     * @return 获取agemillis的结果
     */
    public long getAgeMillis(long now) {
        return closedAt > 0 ? closedAt - createdAt : now - createdAt;
    }
}