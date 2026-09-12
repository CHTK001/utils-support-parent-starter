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
* 基于commons-compress实现，优先级高于通用模块的实现。
* 注意：GZ是单文件压缩格式，不是归档格式，所以只包含一个条目。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"gz", "gzip"})
public class GzipCompressArchiveInputStream implements CompressArchiveInputStream {

    /**
    * 判断是否支持
    * @param file 文件
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
    * 创建输入流
    * @param inputStream 输入流
    * @param file 文件
    * @param char char
    * @param password 密码
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
    * 获取格式化名称
    * @return getFormatName
     */
    @Override
    public String getFormatName() {
        return "gz";
    }

    /**
    * GZIP归档输入流适配器
    * <p>
    * 将commons-compress的gzipcompressor输入流适配为Arch Linux Linux输入流
    * GZ格式是单文件压缩，所以只包含一个条目
    * </p>
    * @author CH
    * @since 4.0.0
     */
    private static class GzipArchiveInputStreamAdapter implements ArchiveInputStream {

        /**
        * 原始gzipcompressor输入流
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
        * @param gzipInputStream commons-compress的gzipcompressor输入流
        * @param file            压缩文件
         */
        GzipArchiveInputStreamAdapter(GzipCompressorInputStream gzipInputStream, File file) {
            this.gzipInputStream = gzipInputStream;
            this.file = file;
        }

        /**
        * 获取下一个entry
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
        * 获取跳过
        * @param n n
        * @return skip
         */
        @Override
        public long skip(long n) throws IOException {
            return gzipInputStream.skip(n);
        }

        /**
        * 获取可用
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
        * 获取mark支持
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
    * @author CH
    * @since 4.0.0
     */
    private static class GzipEntryAdapter implements ArchiveEntry {

        /** 名称 */
        private final String name;

        GzipEntryAdapter(String name) {
            this.name = name;
        }

        /**
        * 获取名称
        * @return getName
         */
        @Override
        public String getName() {
            return name;
        }

        /**
        * 判断是否目录
        * @return isDirectory
         */
        @Override
        public boolean isDirectory() {
            return false;
        }

        /**
        * 获取大小
        * @return getSize
         */
@Override
        public long getSize() {
            return -1;
        }
    }
}
