package com.chua.metrics.support;

import lombok.Data;

/**
 * 内存插槽指标数据模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class MemorySlot {
    /**
     * 插槽索引（从 0 开始）
     */
    private int slot;

    /**
     * 内存总容量（字节）
     */
    private long total;

    /**
     * 已用内存容量（字节）
     */
    private long used;

    /**
     * 可用内存容量（字节）
     */
    private long available;

    /**
     * 内存类型（如 DDR4、DDR5 等）
     */
    private String memoryType;
}