package com.chua.hprof.support.model;

import com.chua.common.support.spi.annotations.Spi;
import lombok.Getter;
import lombok.Setter;

/**
 * 堆转储对象行模型。
 *
 * <p>表示从 hprof 文件中解析出的单个对象（或对象分组），
 * 供类直方图、支配树与引用链视图使用。
 * {@code @Spi} 注解把它标记为标准的 hprof SPI 记录
 * 类型，便于下游转换器按名称定位。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("hprof")
@Getter
@Setter
public class HprofObject {

    /**
    * Java class name, e.g. {@code java.util.HashMap}
    */
    private String className;

    /**
    * 该类在堆中的实例数量
    */
    private long instanceCount;

    /**
    * Shallow (direct) size in bytes
    */
    private long shallowSize;

    /**
    * Retained size in bytes
    */
    private long retainedSize;

    /**
    * Object identifier in the heap dump
    */
    private long objectId;

    /**
    * 该对象属于 GC root 时的根描述
    */
    private String gcRoot;

    /**
    * Reference chain to a GC root, e.g. {@code static OrderCache.cache}
    */
    private String refChain;

    /**
    * Human-readable retained size string, e.g. {@code 1.2GB}
    */
    private String retainedSizeText;

    /**
    * Default constructor
    */
    public HprofObject() {
    }

    /**
    * Create an HprofObject instance.
    *
    * @param className class name
    * @param instanceCount instance count
    * @param shallowSize shallow size in bytes
    * @param retainedSize retained size in bytes
    */
    public HprofObject(String className, long instanceCount, long shallowSize, long retainedSize) {
        this.className = className;
        this.instanceCount = instanceCount;
        this.shallowSize = shallowSize;
        this.retainedSize = retainedSize;
        this.retainedSizeText = HprofObject.formatSize(retainedSize);
    }

    /**
    * Format a byte count as a human readable size string.
    *
    * @param bytes byte count
    * @return formatted string like {@code 1.2GB}, {@code 340MB}, {@code 512KB}, {@code 128B}
    */
    public static String formatSize(long bytes) {
        if (bytes <= 0L) {
            return "0B";
        }
        if (bytes < 1024L) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format("%.1fKB", kb).replace(".0", "");
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format("%.1fMB", mb).replace(".0", "");
        }
        double gb = mb / 1024.0;
        return String.format("%.1fGB", gb).replace(".0", "");
    }
}
