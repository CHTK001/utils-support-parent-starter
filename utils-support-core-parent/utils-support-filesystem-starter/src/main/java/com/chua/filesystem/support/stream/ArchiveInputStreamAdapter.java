package com.chua.filesystem.support.stream;

import com.chua.common.support.io.file.stream.ArchiveEntry;
import com.chua.common.support.io.file.stream.ArchiveInputStream;

import java.io.IOException;
import javax.annotation.Nullable;

/**
* Arch Linux Linux输入流适配器
* <p>
* 将commons-compress的Arch Linux Linux输入流适配为项目接口
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class ArchiveInputStreamAdapter implements ArchiveInputStream {

    /**
    * 原始Arch Linux Linux输入流（commons-compress）
    */
    private final org.apache.commons.compress.archivers.ArchiveInputStream<? extends org.apache.commons.compress.archivers.ArchiveEntry> delegate;

    /**
    * 构造函数
    *
    * @param delegate commons-compress的Arch Linux Linux Linux输入流
    */
    public ArchiveInputStreamAdapter(org.apache.commons.compress.archivers.ArchiveInputStream<? extends org.apache.commons.compress.archivers.ArchiveEntry> delegate) {
        this.delegate = delegate;
    }

    @Override
    @Nullable
    /**
    * 获取下一个entry
    *
    * @return 获取下一个entry的结果
    */
    public ArchiveEntry getNextEntry() throws IOException {
        var entry = delegate.getNextEntry();
        if (entry == null) {
            return null;
        }
        return new ArchiveEntryAdapter(entry);
    }

    @Override
    /** 读取 */
    public int read() throws IOException {
        return delegate.read();
    }

    @Override
    /** 读取 */
    public int read(byte[] b) throws IOException {
        return delegate.read(b);
    }

    @Override
    /** 读取 */
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
    }

    @Override
    /** 跳过 */
    public long skip(long n) throws IOException {
        return delegate.skip(n);
    }

    @Override
    /** 可用 */
    public int available() throws IOException {
        return delegate.available();
    }

    @Override
    /** 标记 */
    public void mark(int readlimit) {
        delegate.mark(readlimit);
    }

    @Override
    /** 重置 */
    public void reset() throws IOException {
        delegate.reset();
    }

    @Override
    /** 标记支持 */
    public boolean markSupported() {
        return delegate.markSupported();
    }

    @Override
    /** 关闭 */
    public void close() throws IOException {
        delegate.close();
    }
}
