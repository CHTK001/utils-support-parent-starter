// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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
     * @param sevenZFile commons-compress的sevenz文件
     * @return 返回值
    */
    SevenZArchiveInputStreamAdapter(SevenZFile sevenZFile) {
        this.sevenZFile = sevenZFile;
    }

    /**
     * 获取下一个entry
    /**
     * 获取下一个entry
     * @return 获取下一个entry的结果
     * @throws IOException 异常
    */
    public ArchiveEntry getNextEntry() throws IOException {
        currentEntry = sevenZFile.getNextEntry();
        if (currentEntry == null) {
            return null;
        }
        return new ArchiveEntryAdapter(currentEntry);
    }

    /**
     * 读取
    */
    @Override
    public int read() throws IOException {
        if (currentEntry == null) {
            return -1;
        }
        return sevenZFile.read();
    }

    /**
     * 读取
    */
    @Override
    public int read(byte[] b) throws IOException {
        if (currentEntry == null) {
            return -1;
        }
        return sevenZFile.read(b);
    }

    /**
     * 读取
    */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (currentEntry == null) {
            return -1;
        }
        return sevenZFile.read(b, off, len);
    }

    /**
     * 跳过
    */
    @Override
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

    /**
     * 可用
    */
    @Override
    public int available() throws IOException {
        if (currentEntry == null) {
            return 0;
        }
        long remaining = currentEntry.getSize();
        return (int) Math.min(remaining, Integer.MAX_VALUE);
    }

    /**
     * 标记
    */
    @Override
    public void mark(int readlimit) {
        // 7Z格式不支持标记
    }

    /**
     * 重置
    */
    @Override
    public void reset() throws IOException {
        throw new IOException("7Z格式不支持重置操作");
    }

    /**
     * 标记支持
    */
    @Override
    public boolean markSupported() {
        return false;
    }

    /**
     * 关闭
    */
    @Override
    public void close() throws IOException {
        sevenZFile.close();
    }
}