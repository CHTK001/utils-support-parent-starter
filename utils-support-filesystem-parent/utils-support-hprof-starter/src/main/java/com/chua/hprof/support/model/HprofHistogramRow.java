package com.chua.hprof.support.model;

import lombok.Getter;

/**
 * 堆转储类直方图条目。
 *
 * <p>按类聚合的统计信息：实例数、浅堆大小与保留堆大小。这是 Markdown
 * 报告渲染器使用的主表（类名 / 实例数 / 内存占用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public class HprofHistogramRow {

    /**
    * 简单类名（不含包名），例如 {@code HashMap}
    */
    private final String simpleName;

    /**
    * 全限定类名，例如 {@code java.util.HashMap}
    */
    private final String className;

    /**
    * 堆中的实例数
    */
    private final long instanceCount;

    /**
    * 浅堆大小（字节）
    */
    private final long shallowSize;

    /**
    * 保留堆大小（字节）
    */
    private final long retainedSize;

    /**
    * 可读形式的保留堆大小，例如 {@code 1.2 GB}
    */
    private final String retainedSizeText;

    /**
    * 创建 HprofHistogramRow 实例。
    *
    * @param className    全限定类名
    * @param instanceCount 实例数
    * @param shallowSize  浅堆大小（字节）
    * @param retainedSize 保留堆大小（字节）
    */
    public HprofHistogramRow(String className, long instanceCount, long shallowSize, long retainedSize) {
        this.className = className;
        this.simpleName = simpleNameOf(className);
        this.instanceCount = instanceCount;
        this.shallowSize = shallowSize;
        this.retainedSize = retainedSize;
        this.retainedSizeText = HprofObject.formatSize(retainedSize) + " ";
    }

    /**
    * 创建显式指定类名的 HprofHistogramRow 实例。
    *
    * @param simpleName   简单类名
    * @param className    全限定类名
    * @param instanceCount 实例数
    * @param shallowSize  浅堆大小（字节）
    * @param retainedSize 保留堆大小（字节）
    */
    public HprofHistogramRow(String simpleName, String className,
                             long instanceCount, long shallowSize, long retainedSize) {
        this.simpleName = simpleName;
        this.className = className;
        this.instanceCount = instanceCount;
        this.shallowSize = shallowSize;
        this.retainedSize = retainedSize;
        this.retainedSizeText = HprofObject.formatSize(retainedSize);
    }

    /**
    * 从全限定类名中截取简单类名。
    *
    * @param className 全限定类名
    * @return 最后一个点号之后的部分；没有点号时返回整个名称
    */
    private static String simpleNameOf(String className) {
        if (className == null) {
            return "";
        }
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }
}
