package com.chua.filesystem.support.stream;

import com.chua.common.support.io.file.stream.ArchiveEntry;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.IOException;
import javax.annotation.Nullable;

/**
* 7Z归档输入流适配器
* <p>
* 将commons-compress的sevenz文件适配为项目接口
* </p>
*
* @author CH
* @since 4.0.0.42
 */
class SevenZArchiveInputStreamAdapter implements ArchiveInputStream {

    /**
    * 原始sevenz文件
     */
    private final SevenZFile sevenZFile;

    /**
    * 当前条目
     */
    private SevenZArchiveEntry currentEntry;

    /**
    * 构造7Z归档输入流适配器
    *
    * @param sevenZFile commons-compress的sevenz文件
     */
    SevenZArchiveInputStreamAdapter(SevenZFile sevenZFile) {
        this.sevenZFile = sevenZFile;
    }

    @Override
    @Nullable
    /**
    * 获取下一个entry
    *
    * @return 获取下一个entry的结果
     */
    public ArchiveEntry getNextEntry() throws IOException {
        currentEntry = sevenZFile.getNextEntry();
        if (currentEntry == null) {
            return null;
        }
        return new ArchiveEntryAdapter(currentEntry);
    }

    @Override
    /** 读取 */
    public int read() throws IOException {
        if (currentEntry == null) {
            return -1;
        }
        return sevenZFile.read();
    }

    @Override
    /** 读取 */
    public int read(byte[] b) throws IOException {
        if (currentEntry == null) {
            return -1;
        }
        return sevenZFile.read(b);
    }

    @Override
    /** 读取 */
    public int read(byte[] b, int off, int len) throws IOException {
        if (currentEntry == null) {
            return -1;
        }
        return sevenZFile.read(b, off, len);
    }

    @Override
    /** 跳过 */
    public long skip(long n) throws IOException {
        if (currentEntry == null) {
            return 0;
        }
        if (n <= 0) {
            return 0;
        }
        long remaining = n;
        var buffer = new byte[(int) Math.min(8192, n)];
        while (remaining > 0) {
            int read = sevenZFile.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read <= 0) {
                break;
            }
            remaining -= read;
        }
        return n - remaining;
    }

    @Override
    /** 可用 */
    public int available() throws IOException {
        if (currentEntry == null) {
            return 0;
        }
        long remaining = currentEntry.getSize();
        return (int) Math.min(remaining, Integer.MAX_VALUE);
    }

    @Override
    /** 标记 */
    public void mark(int readlimit) {
        // 7Z格式不支持标记
    }

    @Override
    /** 重置 */
    public void reset() throws IOException {
        throw new IOException("7Z格式不支持重置操作");
    }

    @Override
    /** 标记支持 */
    public boolean markSupported() {
        return false;
    }

    @Override
    /** 关闭 */
    public void close() throws IOException {
        sevenZFile.close();
    }
}
