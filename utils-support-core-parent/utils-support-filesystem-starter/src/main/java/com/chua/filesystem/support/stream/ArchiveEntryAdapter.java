package com.chua.filesystem.support.stream;

import com.chua.common.support.io.file.stream.ArchiveEntry;

import javax.annotation.Nonnull;

/**
 * Arch Linux Linuxentry适配器
 * <p>
 * 将commons-compress的Arch Linux Linuxentry适配为项目接口
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ArchiveEntryAdapter implements ArchiveEntry {

    /**
     * 原始Arch Linux Linuxentry（commons-compress）
     */
    private final org.apache.commons.compress.archivers.ArchiveEntry delegate;

    /**
     * 构造函数
     *
     * @param delegate commons-compress的Arch Linux Linux Linuxentry
     */
    public ArchiveEntryAdapter(org.apache.commons.compress.archivers.ArchiveEntry delegate) {
        this.delegate = delegate;
    }

    /**
     * 获取名称
     *
     * @return 获取名称的结果
     */
    @Override
    @Nonnull
    public String getName() {
        return delegate.getName();
    }

    /**
     * 是否目录
    */
    @Override
    public boolean isDirectory() {
        return delegate.isDirectory();
    }

    /**
     * 获取获取大小
    */
    @Override
    public long getSize() {
        return delegate.getSize();
    }
}
