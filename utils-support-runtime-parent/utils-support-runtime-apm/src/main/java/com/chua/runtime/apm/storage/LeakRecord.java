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

    /** 内部 row id */
    /** ID */
    private long id;

    /** 唯一 handleId */
    /** HandleID */
    private String handleId;

    /** 句柄类型名（如 {@code java/io/FileInputStream}） */
    /** Kind */
    private String kind;

    /** 名称 / 描述（路径 / URL） */
    /** 名称 */
    private String name;

    /** 持有线程名 */
    /** 线程 */
    private String thread;

    /** 创建时间（毫秒） */
    /** 创建时间AT */
    private long createdAt;

    /** 关闭时间（毫秒），未关闭为 0 */
    /** ClosedAT */
    private long closedAt;

    /** 创建时的栈追踪（多行字符串） */
    /** 栈跟踪 */
    private String stackTrace;

    /** 当前是否仍然泄漏 */
    public boolean isActive() {
        return closedAt == 0L;
    }

    /** 持续时长（毫秒） */
    public long getAgeMillis(long now) {
        return closedAt > 0 ? closedAt - createdAt : now - createdAt;
    }
}