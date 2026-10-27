package com.chua.filesystem.support.stream;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.io.file.stream.ArchiveEntry;
import com.chua.common.support.io.file.stream.ArchiveInputStream;
import com.chua.common.support.io.file.stream.CompressArchiveInputStream;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * RAR格式归档输入流提供者
 * <p>
 * 基于Junrar库实现RAR格式的读取。
 * 注意：RAR格式需要文件对象，不支持从输入流直接读取。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("rar")
public class RarCompressArchiveInputStream implements CompressArchiveInputStream {

    /**
     * 是否支持
    */
    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        var fileName = file.getName().toLowerCase();
        return fileName.endsWith(".rar");
    }

    /**
     * 创建输入流
    */
    @Override
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
 // RAR格式需要文件对象，不能使用输入流
        if (file == null) {
            throw new IOException("RAR格式需要File对象，不能使用InputStream");
        }
        return new RarArchiveInputStreamAdapter(file, password);
    }

    /**
     * 获取格式化名称
    */
    @Override
    public String getFormatName() {
        return "rar";
    }

    /**
     * RAR归档输入流适配器
     * @author CH
     * @since 4.0.0
     */
    private static class RarArchiveInputStreamAdapter implements ArchiveInputStream {
        /**
         * Archive
        */
        private final Archive archive;
        /**
         * 当前entry
        */
        private FileHeader currentEntry;
        /**
         * Entries
        */
        private final java.util.List<FileHeader> entries;
        /**
         * 当前索引
        */
        private int currentIndex = 0;
        /**
         * 当前entry流
        */
        private java.io.ByteArrayInputStream currentEntryStream;
        /**
         * 当前entry位置
        */
        private int currentEntryPosition = 0;

        RarArchiveInputStreamAdapter(File file, @Nullable char[] password) throws IOException {
            try {
                this.archive = new Archive(file);
                this.entries = archive.getFileHeaders();
                this.currentIndex = 0;
            } catch (Exception e) {
                log.error("[filesystem-stream] [创建]创建RAR归档输入流失败: {}", file.getAbsolutePath(), e);
                throw new IOException("创建RAR归档输入流失败: " + file.getAbsolutePath(), e);
            }
        }

        /**
         * 获取下一个entry
         *
         * @return 获取下一个entry的结果
         */
        @Override
        @Nullable
        public ArchiveEntry getNextEntry() throws IOException {
            // 关闭当前条目流
            if (currentEntryStream != null) {
                try {
                    currentEntryStream.close();
                } catch (Exception e) {
                    // 忽略关闭异常
                }
                currentEntryStream = null;
            }
            currentEntryPosition = 0;

            // 查找下一个文件条目（跳过目录）
            while (currentIndex < entries.size()) {
                currentEntry = entries.get(currentIndex++);
                if (!currentEntry.isDirectory()) {
                    // 提取文件内容到内存
                    try (var outputStream = new ByteArrayOutputStream()) {
                        archive.extractFile(currentEntry, outputStream);
                        var bytes = outputStream.toByteArray();
                        currentEntryStream = new ByteArrayInputStream(bytes);
                        return new RarArchiveEntryAdapter(currentEntry);
                    } catch (Exception e) {
                        log.warn("[filesystem-stream] [读取]提取文件失败: {}", currentEntry.getFileName(), e);
                        // 继续下一个条目
                        continue;
                    }
                }
            }

            currentEntry = null;
            return null;
        }

        /**
         * 读取
        */
        @Override
        public int read() throws IOException {
            if (currentEntryStream == null) {
                return -1;
            }
            currentEntryPosition++;
            return currentEntryStream.read();
        }

        /**
         * 读取
        */
        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        /**
         * 读取
        */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (currentEntryStream == null) {
                return -1;
            }
            if (off < 0 || len < 0 || off + len > b.length) {
                throw new IndexOutOfBoundsException("数组越界");
            }
            int result = currentEntryStream.read(b, off, len);
            if (result > 0) {
                currentEntryPosition += result;
            }
            return result;
        }

        /**
         * 跳过
        */
        @Override
        public long skip(long n) throws IOException {
            if (currentEntryStream == null) {
                return 0;
            }
            long skipped = currentEntryStream.skip(n);
            currentEntryPosition += (int) skipped;
            return skipped;
        }

        /**
         * 可用
        */
        @Override
        public int available() throws IOException {
            if (currentEntryStream == null) {
                return 0;
            }
            return currentEntryStream.available();
        }

        /**
         * 标记
        */
        @Override
        public void mark(int readlimit) {
            if (currentEntryStream != null) {
                currentEntryStream.mark(readlimit);
            }
        }

        /**
         * 重置
        */
        @Override
        public void reset() throws IOException {
            if (currentEntryStream == null) {
                throw new IOException("没有可重置的流");
            }
            currentEntryStream.reset();
            currentEntryPosition = 0;
        }

        /**
         * 标记支持
        */
        @Override
        public boolean markSupported() {
            return currentEntryStream != null && currentEntryStream.markSupported();
        }

        /**
         * 关闭
        */
        @Override
        public void close() throws IOException {
            if (currentEntryStream != null) {
                try {
                    currentEntryStream.close();
                } catch (Exception e) {
                    // 忽略关闭异常
                }
                currentEntryStream = null;
            }
            if (archive != null) {
                try {
                    archive.close();
                } catch (Exception e) {
                    log.warn("[filesystem-stream] [关闭]关闭RAR归档失败", e);
                }
            }
        }
    }

    /**
     * RAR归档条目适配器
     * @author CH
     * @since 4.0.0
     */
    private static class RarArchiveEntryAdapter implements ArchiveEntry {
        /**
         * 文件头部
        */
        private final FileHeader fileHeader;

        RarArchiveEntryAdapter(FileHeader fileHeader) {
            this.fileHeader = fileHeader;
        }

        /**
         * 获取名称
        */
        @Override
        public String getName() {
            return fileHeader.getFileName();
        }

        /**
         * 是否目录
        */
        @Override
        public boolean isDirectory() {
            return fileHeader.isDirectory();
        }

        /**
         * 获取获取大小
        */
        @Override
        public long getSize() {
            return fileHeader.getUnpSize();
        }
    }
}
