package com.chua.hprof.support.model;

import lombok.Getter;

/**
 * Heap dump class histogram entry.
 *
 * <p>Aggregated per-class statistics: instance count, shallow and retained
 * sizes. This is the primary table consumed by the markdown report renderer
 * (class name / instance count / memory used).</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public class HprofHistogramRow {

    /**
    * Simple class name (without package), e.g. {@code HashMap}
    */
    private final String simpleName;

    /**
    * Fully qualified class name, e.g. {@code java.util.HashMap}
    */
    private final String className;

    /**
    * Instance count in the heap
    */
    private final long instanceCount;

    /**
    * Shallow size in bytes
    */
    private final long shallowSize;

    /**
    * Retained size in bytes
    */
    private final long retainedSize;

    /**
    * Human readable retained size, e.g. {@code 1.2 GB}
    */
    private final String retainedSizeText;

    /**
    * Create an HprofHistogramRow instance.
    *
    * @param className fully qualified class name
    * @param instanceCount instance count
    * @param shallowSize shallow size in bytes
    * @param retainedSize retained size in bytes
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
    * Create an HprofHistogramRow instance with an explicit class name.
    *
    * @param simpleName simple class name
    * @param className fully qualified class name
    * @param instanceCount instance count
    * @param shallowSize shallow size in bytes
    * @param retainedSize retained size in bytes
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
    * Extract the simple name from a fully qualified class name.
    *
    * @param className fully qualified class name
    * @return the part after the last dot, or the whole name when no dot
    */
    private static String simpleNameOf(String className) {
        if (className == null) {
            return "";
        }
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }
}
