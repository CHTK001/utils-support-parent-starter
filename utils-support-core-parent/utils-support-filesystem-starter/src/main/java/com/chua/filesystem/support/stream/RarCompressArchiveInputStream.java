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
 * 注意：RAR格式需要File对象，不支持从InputStream直接读取。
 * </p>
 *
 * @author CH
 */
@Slf4j
@Spi("rar")
public class RarCompressArchiveInputStream implements CompressArchiveInputStream {

    @Override
    public boolean isSupport(File file) {
        if (file == null) {
            return false;
        }
        var fileName = file.getName().toLowerCase();
        return fileName.endsWith(".rar");
    }

    @Override
    public ArchiveInputStream createInputStream(InputStream inputStream, File file, @Nullable char[] password) throws IOException {
        // RAR格式需要File对象，不能使用InputStream
        if (file == null) {
            throw new IOException("RAR格式需要File对象，不能使用InputStream");
        }
        return new RarArchiveInputStreamAdapter(file, password);
    }

    @Override
    public String getFormatName() {
        return "rar";
    }

    /**
     * RAR归档输入流适配器
     */
    private static class RarArchiveInputStreamAdapter implements ArchiveInputStream {
        private final Archive archive;
        private FileHeader currentEntry;
        private final java.util.List<FileHeader> entries;
        private int currentIndex = 0;
        private java.io.ByteArrayInputStream currentEntryStream;
        private int currentEntryPosition = 0;

        RarArchiveInputStreamAdapter(File file, @Nullable char[] password) throws IOException {
            try {
                this.archive = new Archive(file);
                this.entries = archive.getFileHeaders();
                this.currentIndex = 0;
            } catch (Exception e) {
                log.error("[RAR归档][创建]创建RAR归档输入流失败: {}", file.getAbsolutePath(), e);
                throw new IOException("创建RAR归档输入流失败: " + file.getAbsolutePath(), e);
            }
        }

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
                        log.warn("[RAR归档][读取]提取文件失败: {}", currentEntry.getFileName(), e);
                        // 继续下一个条目
                        continue;
                    }
                }
            }

            currentEntry = null;
            return null;
        }

        @Override
        public int read() throws IOException {
            if (currentEntryStream == null) {
                return -1;
            }
            currentEntryPosition++;
            return currentEntryStream.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

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

        @Override
        public long skip(long n) throws IOException {
            if (currentEntryStream == null) {
                return 0;
            }
            long skipped = currentEntryStream.skip(n);
            currentEntryPosition += (int) skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            if (currentEntryStream == null) {
                return 0;
            }
            return currentEntryStream.available();
        }

        @Override
        public void mark(int readlimit) {
            if (currentEntryStream != null) {
                currentEntryStream.mark(readlimit);
            }
        }

        @Override
        public void reset() throws IOException {
            if (currentEntryStream == null) {
                throw new IOException("没有可重置的流");
            }
            currentEntryStream.reset();
            currentEntryPosition = 0;
        }

        @Override
        public boolean markSupported() {
            return currentEntryStream != null && currentEntryStream.markSupported();
        }

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
                    log.warn("[RAR归档][关闭]关闭RAR归档失败", e);
                }
            }
        }
    }

    /**
     * RAR归档条目适配器
     */
    private static class RarArchiveEntryAdapter implements ArchiveEntry {
        private final FileHeader fileHeader;

        RarArchiveEntryAdapter(FileHeader fileHeader) {
            this.fileHeader = fileHeader;
        }

        @Override
        public String getName() {
            return fileHeader.getFileName();
        }

        @Override
        public boolean isDirectory() {
            return fileHeader.isDirectory();
        }

        @Override
        public long getSize() {
            return fileHeader.getUnpSize();
        }
    }
}
