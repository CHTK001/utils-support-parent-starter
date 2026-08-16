package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveEntry;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * GZ/GZIP格式归档输入流提供者
 * <p>
 * 基于commons-compress实现，优先级高于common模块的实现。
 * 注意：GZ是单文件压缩格式，不是归档格式，所以只包含一个条目。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"gz", "gzip"})
public class GzipCompressArchiveInputStream implements CompressArchiveInputStream {

    /**
     * 判断是否Support
     * @param file file
     * @return isSupport
     */
    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        var fileName = file.getName().toLowerCase();
        return (fileName.endsWith(".gz") || fileName.endsWith(".gzip"))
                && !fileName.endsWith(".tar.gz")
                && !fileName.endsWith(".tgz");
    }

    /**
     * 创建InputStream
     * @param inputStream inputStream
     * @param file file
     * @param char char
     * @param password password
     * @return createInputStream
     */
    @Override
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        // GZ 格式不支持密码
        if (password != null) {
            throw new UnsupportedOperationException("GZ 格式不支持密码保护");
        }
        // GZ格式是单文件压缩，需要特殊处理
        var gzipInputStream = new GzipCompressorInputStream(inputStream);
        return new GzipArchiveInputStreamAdapter(gzipInputStream, file);
    }

    /**
     * 获取FormatName
     * @return getFormatName
     */
    @Override
    public String getFormatName() {
        return "gz";
    }

    /**
     * GZIP归档输入流适配器
     * <p>
     * 将commons-compress的GzipCompressorInputStream适配为ArchiveInputStream
     * GZ格式是单文件压缩，所以只包含一个条目
     * </p>
     */
    private static class GzipArchiveInputStreamAdapter implements ArchiveInputStream {

        /**
         * 原始GzipCompressorInputStream
         */
        private final GzipCompressorInputStream gzipInputStream;

        /**
         * 文件对象（用于获取条目名称）
         */
        private final File file;

        /**
         * 当前条目（GZ格式只有一个条目）
         */
        private ArchiveEntry currentEntry;

        /**
         * 是否已读取条目
         */
        private boolean entryRead = false;

        /**
         * 构造GZIP归档输入流适配器
         *
         * @param gzipInputStream commons-compress的GzipCompressorInputStream
         * @param file            压缩文件
         */
        GzipArchiveInputStreamAdapter(GzipCompressorInputStream gzipInputStream, File file) {
            this.gzipInputStream = gzipInputStream;
            this.file = file;
        }

        /**
         * 获取NextEntry
         * @return getNextEntry
         */
        @Override
        @Nullable
        public ArchiveEntry getNextEntry() throws IOException {
            if (entryRead) {
                currentEntry = null;
                return null;
            }
            entryRead = true;
            // GZ格式只有一个条目，使用文件名（去掉.gz后缀）作为条目名
            var entryName = file.getName();
            if (entryName.toLowerCase().endsWith(".gz")) {
                entryName = entryName.substring(0, entryName.length() - 3);
            } else if (entryName.toLowerCase().endsWith(".gzip")) {
                entryName = entryName.substring(0, entryName.length() - 5);
            }
            currentEntry = new GzipEntryAdapter(entryName);
            return currentEntry;
        }

        /**
         * 读取
         * @return read
         */
        @Override
        public int read() throws IOException {
            return gzipInputStream.read();
        }

        /**
         * 读取
         * @param b b
         * @return read
         */
        @Override
        public int read(byte[] b) throws IOException {
            return gzipInputStream.read(b);
        }

        /**
         * 读取
         * @param b b
         * @param off off
         * @param len len
         * @return read
         */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return gzipInputStream.read(b, off, len);
        }

        /**
         * 获取skip
         * @param n n
         * @return skip
         */
        @Override
        public long skip(long n) throws IOException {
            return gzipInputStream.skip(n);
        }

        /**
         * 获取available
         * @return available
         */
        @Override
        public int available() throws IOException {
            return gzipInputStream.available();
        }

        /**
         * mark
         * @param readlimit readlimit
         */
        @Override
        public void mark(int readlimit) {
            gzipInputStream.mark(readlimit);
        }

        /**
         * 重置
         */
        @Override
        public void reset() throws IOException {
            gzipInputStream.reset();
        }

        /**
         * 获取markSupported
         * @return markSupported
         */
        @Override
        public boolean markSupported() {
            return gzipInputStream.markSupported();
        }

        /**
         * 关闭
         */
        @Override
        public void close() throws IOException {
            gzipInputStream.close();
        }
    }

    /**
     * GZ条目适配器
     * <p>
     * GZ格式是单文件压缩，所以条目大小未知
     * </p>
     */
    private static class GzipEntryAdapter implements ArchiveEntry {

        private final String name;

        GzipEntryAdapter(String name) {
            this.name = name;
        }

        /**
         * 获取Name
         * @return getName
         */
        @Override
        public String getName() {
            return name;
        }

        /**
         * 判断是否Directory
         * @return isDirectory
         */
        @Override
        public boolean isDirectory() {
            return false;
        }

        /**
         * 获取Size
         * @return getSize
         */
@Override
        public long getSize() {
            return -1;
        }
    }
}
