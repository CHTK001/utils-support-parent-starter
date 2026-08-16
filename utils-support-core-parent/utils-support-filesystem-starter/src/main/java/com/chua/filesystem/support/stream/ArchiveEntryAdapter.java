package com.chua.filesystem.support.stream;

import com.chua.common.support.io.file.stream.ArchiveEntry;

import javax.annotation.Nonnull;

/**
 * ArchiveEntry适配器
 * <p>
 * 将commons-compress的ArchiveEntry适配为项目接口
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ArchiveEntryAdapter implements ArchiveEntry {

    /**
     * 原始ArchiveEntry（commons-compress）
     */
    private final org.apache.commons.compress.archivers.ArchiveEntry delegate;

    /**
     * 构造函数
     *
     * @param delegate commons-compress的ArchiveEntry
     */
    public ArchiveEntryAdapter(org.apache.commons.compress.archivers.ArchiveEntry delegate) {
        this.delegate = delegate;
    }

    @Override
    @Nonnull
    public String getName() {
        return delegate.getName();
    }

    @Override
    public boolean isDirectory() {
        return delegate.isDirectory();
    }

    @Override
    public long getSize() {
        return delegate.getSize();
    }
}
