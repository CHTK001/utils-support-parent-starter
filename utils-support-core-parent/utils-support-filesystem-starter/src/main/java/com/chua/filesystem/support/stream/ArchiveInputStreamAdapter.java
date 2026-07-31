package com.chua.filesystem.support.stream;

import com.chua.common.support.io.file.stream.ArchiveEntry;
import com.chua.common.support.io.file.stream.ArchiveInputStream;

import java.io.IOException;
import javax.annotation.Nullable;

/**
 * ArchiveInputStream适配器
 * <p>
 * 将commons-compress的ArchiveInputStream适配为项目接口
 * </p>
 *
 * @author CH
 */
public class ArchiveInputStreamAdapter implements ArchiveInputStream {

    /**
     * 原始ArchiveInputStream（commons-compress）
     */
    private final org.apache.commons.compress.archivers.ArchiveInputStream<? extends org.apache.commons.compress.archivers.ArchiveEntry> delegate;

    /**
     * 构造函数
     *
     * @param delegate commons-compress的ArchiveInputStream
     */
    public ArchiveInputStreamAdapter(org.apache.commons.compress.archivers.ArchiveInputStream<? extends org.apache.commons.compress.archivers.ArchiveEntry> delegate) {
        this.delegate = delegate;
    }

    @Override
    @Nullable
    public ArchiveEntry getNextEntry() throws IOException {
        var entry = delegate.getNextEntry();
        if (entry == null) {
            return null;
        }
        return new ArchiveEntryAdapter(entry);
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return delegate.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return delegate.skip(n);
    }

    @Override
    public int available() throws IOException {
        return delegate.available();
    }

    @Override
    public void mark(int readlimit) {
        delegate.mark(readlimit);
    }

    @Override
    public void reset() throws IOException {
        delegate.reset();
    }

    @Override
    public boolean markSupported() {
        return delegate.markSupported();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
