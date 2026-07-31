package com.chua.oshi.support;

import lombok.Data;

/**
 * 内存信息模型类。
 * <p>
 * 用于封装系统内存的总量、已用量、空闲量及使用率等关键指标。
 * 所有容量单位为字节（Byte），使用率为 [0.0, 1.0] 的小数。
 *
 * @author CH
 */
@Data
public class Mem {

    /**
     * 内存总容量（单位：字节）。
     */
    private long total;

    /**
     * 内存已使用量（单位：字节）。
     */
    private long used;

    /**
     * 内存可用/空闲量（单位：字节）。
     */
    private long free;

    /**
     * 内存使用率，取值范围 [0.0, 1.0]。
     * 例如：0.75 表示使用了 75% 的内存。
     */
    private double usage;
}
