package com.chua.filesystem.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

import java.io.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel;

/**
 * 7z 压缩文件系统 SPI 实现。
 *
 * <p>通过 SPI 机制注册为 {@code "7z"} 类型的文件系统实现。
 * 基于 Apache Commons Compress 提供 7z 格式的读写支持。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("7z")
public class SevenZFileSystem implements FileSystem {

    @Override
    /** 获取类型 */
    public String getType() {
        return "7z";
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        return new SevenZReadBuilder(file);
    }

    @Override
    /**
     * 写入
     *
     * @param file 文件
     * @return 写入的结果
     * @author CH
     * @since 4.0.0
     */
    public WriteBuilder write(File file) {
        return new SevenZWriteBuilder(file);
    }

    public static class SevenZReadBuilder extends ReadBuilder {

        /** 是否启用分卷读取模式 */
        private boolean splitMode = false;

        SevenZReadBuilder(File file) {
            super(file);
        }

        /**
         * 启用分卷读取模式。
         * <p>启用后将自动检测同目录下的分卷文件（.7z.001, .7z.002 等）并合并读取。</p>
         *
         * @return 当前构建器
         */
        public SevenZReadBuilder split() {
            this.splitMode = true;
            return this;
        }

        /**
         * 查找同目录下的分卷文件。
         *
         * @return 分卷文件列表（按顺序排列）
         */
        private List<File> findSplitFiles() {
            List<File> splitFiles = new ArrayList<>();
            File parentDir = file.getParentFile();
            if (parentDir == null || !parentDir.exists()) {
                if (file.exists()) {
                    splitFiles.add(file);
                }
                return splitFiles;
            }

            String baseName = file.getName();
            String baseNameWithoutExt = baseName;
            int lastDot = baseName.lastIndexOf('.');
            if (lastDot > 0) {
                baseNameWithoutExt = baseName.substring(0, lastDot);
            }

            // 查找 .7z.001, .7z.002, ... 等分卷文件
            String prefix = baseNameWithoutExt + ".";
            File[] files = parentDir.listFiles((dir, name) ->
                    name.startsWith(prefix) &&
                    name.length() >= prefix.length() + 1 &&
                    !name.equals(baseName));

            if (files != null) {
                List<File> sortedFiles = new ArrayList<>();
                for (File f : files) {
                    String name = f.getName();
                    String ext = name.substring(prefix.length());
                    if (ext.matches("\\d+")) {
                        sortedFiles.add(f);
                    }
                }
                sortedFiles.sort((f1, f2) -> {
                    String num1 = f1.getName().substring(prefix.length());
                    String num2 = f2.getName().substring(prefix.length());
                    return Integer.compare(Integer.parseInt(num1), Integer.parseInt(num2));
                });
                splitFiles.addAll(sortedFiles);
            }

            if (file.exists()) {
                splitFiles.add(file);
            }
            return splitFiles;
        }

        /**
          * 创建 sevenz文件，自动处理分卷模式。
         *
         * @return SevenZFile 实例
         * @throws IOException IO 异常
         */
        private SevenZFile openSevenZFile() throws IOException {
            if (!splitMode) {
                return new SevenZFile(file);
            }

            List<File> splitFiles = findSplitFiles();
            if (splitFiles.size() <= 1) {
                return new SevenZFile(file);
            }

 // 使用 multi读取onlyseekablebyte通道 合并分卷文件
            List<SeekableByteChannel> channels = new ArrayList<>();
            for (File f : splitFiles) {
                channels.add(Files.newByteChannel(f.toPath(), StandardOpenOption.READ));
            }
            MultiReadOnlySeekableByteChannel mergedChannel =
                    new MultiReadOnlySeekableByteChannel(channels);
            return new SevenZFile(mergedChannel);
        }

        /**
         * 列表entries
         *
         * @return 列表entries的结果
         */
        public List<String> listEntries() {
            List<String> entries = new ArrayList<>();
            try (SevenZFile szFile = openSevenZFile()) {
                SevenZArchiveEntry entry;
                while ((entry = szFile.getNextEntry()) != null) {
                    entries.add(entry.getName());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return entries;
        }

        /**
         * extract全部
         *
         * @param targetDir Targetdir
         */
        public void extractAll(File targetDir) {
            extract(targetDir);
        }

        /**
         * Extract
         *
         * @param entryName entry名称
         * @param targetDir Targetdir
         */
        public void extract(String entryName, File targetDir) {
            extract(targetDir, entryName);
        }

        /**
         * Extract
         *
         * @param targetDir Targetdir
         * @param entryNames entry名称
         */
        public void extract(File targetDir, String... entryNames) {
            try (SevenZFile szFile = openSevenZFile()) {
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
                SevenZArchiveEntry entry;
                while ((entry = szFile.getNextEntry()) != null) {
                    if (entryNames != null && entryNames.length > 0) {
                        boolean matched = false;
                        for (String name : entryNames) {
                            if (entry.getName().equals(name) || entry.getName().startsWith(name + "/")) {
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) {
                            continue;
                        }
                    }

                    File outFile = new File(targetDir, entry.getName());
                    if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                        throw new IOException("7z entry outside target: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (OutputStream os = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = szFile.read(buffer)) > 0) {
                                os.write(buffer, 0, len);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        /** 读取 */
        public Object read() {
            return listEntries();
        }

        /**
         * 读取压缩包中指定文件的内容为字符串。
         *
         * @param entryName 条目名称
         * @return 文件内容字符串，若条目不存在返回 空
         * @throws UncheckedIOException 如果 IO 异常
         */
        public String readEntry(String entryName) {
            try (SevenZFile szFile = openSevenZFile()) {
                SevenZArchiveEntry entry;
                while ((entry = szFile.getNextEntry()) != null) {
                    if (entry.getName().equals(entryName)) {
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = szFile.read(buffer)) > 0) {
                                baos.write(buffer, 0, len);
                            }
                            return baos.toString(StandardCharsets.UTF_8.name());
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        }

        @Override
        /**
         * as字符串
         *
         * @return as字符串的结果
         * @author CH
         * @since 4.0.0
         */
        public String asString() {
            return String.join("\n", listEntries());
        }
    }

    public static class SevenZWriteBuilder extends WriteBuilder {

        /** Entries */
        private final List<EntryData> entries = new ArrayList<>();

        /** 压缩方法（默认 LZMA2） */
        private SevenZMethod compressionMethod = SevenZMethod.LZMA2;

        /** 压缩级别（-1 表示默认，具体含义随方法而异） */
        private int compressionLevel = -1;

        /** 分卷大小（字节），0 表示不分卷 */
        private long splitSize = 0;

        SevenZWriteBuilder(File file) {
            super(file);
        }

        /**
         * 设置分卷大小。
         *
         * @param size 每个分卷的最大字节数
         * @return 当前构建器
         */
        public SevenZWriteBuilder splitSize(long size) {
            this.splitSize = size;
            return this;
        }

        /**
         * 设置压缩方法。
         *
         * @param method 压缩方法（LZMA2、副本、DEFLATE、BZIP2 等）
         * @return 当前构建器
         */
        public SevenZWriteBuilder compressionMethod(SevenZMethod method) {
            this.compressionMethod = method;
            return this;
        }

        /**
         * 设置压缩级别。
         *
         * @param level 压缩级别（LZMA2: 0~9, DEFLATE: 0~9, -1 表示方法默认值）
         * @return 当前构建器
         */
        public SevenZWriteBuilder compressionLevel(int level) {
            this.compressionLevel = level;
            return this;
        }

        /**
         * 添加文件
         *
         * @param entryName entry名称
         * @param source 源
         * @return 添加文件的结果
         */
        public SevenZWriteBuilder addFile(String entryName, File source) {
            entries.add(new EntryData(entryName, source));
            return this;
        }

        /**
         * 添加流
         *
         * @param entryName entry名称
         * @param in 入
         * @return 添加流的结果
         */
        public SevenZWriteBuilder addStream(String entryName, InputStream in) {
            entries.add(new EntryData(entryName, in));
            return this;
        }

        /**
         * 添加Bytes
         *
         * @param entryName entry名称
         * @param bytes bytes
         * @return 添加bytes的结果
         */
        public SevenZWriteBuilder addBytes(String entryName, byte[] bytes) {
            entries.add(new EntryData(entryName, bytes));
            return this;
        }

        @Override
        /** 饰面 */
        public void finish() {
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            if (splitSize > 0) {
                finishSplit();
            } else {
                finishNormal();
            }
        }

        /**
         * 普通模式完成写入。
         */
        private void finishNormal() {
            try (SevenZOutputFile szOut = createOutputFile()) {
                for (EntryData ed : entries) {
                    SevenZArchiveEntry entry = new SevenZArchiveEntry();
                    entry.setName(ed.getEntryName());
                    if (ed.getBytes() != null) {
                        entry.setSize(ed.getBytes().length);
                    }
                    szOut.putArchiveEntry(entry);

                    if (ed.getSource() != null) {
                        writeFile(szOut, ed.getSource());
                    } else if (ed.getInputStream() != null) {
                        writeStream(szOut, ed.getInputStream());
                    } else if (ed.getBytes() != null) {
                        szOut.write(ed.getBytes());
                    }

                    szOut.closeArchiveEntry();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * 分卷模式完成写入。
         * <p>先写入临时文件，然后根据 splitSize 分割成多个分卷文件。</p>
         */
        private void finishSplit() {
            File tempFile = null;
            try {
                tempFile = File.createTempFile("7z-split-", ".7z", file.getParentFile());
                try (SevenZOutputFile szOut = createOutputFile(tempFile)) {
                    for (EntryData ed : entries) {
                        SevenZArchiveEntry entry = new SevenZArchiveEntry();
                        entry.setName(ed.getEntryName());
                        if (ed.getBytes() != null) {
                            entry.setSize(ed.getBytes().length);
                        }
                        szOut.putArchiveEntry(entry);

                        if (ed.getSource() != null) {
                            writeFile(szOut, ed.getSource());
                        } else if (ed.getInputStream() != null) {
                            writeStream(szOut, ed.getInputStream());
                        } else if (ed.getBytes() != null) {
                            szOut.write(ed.getBytes());
                        }

                        szOut.closeArchiveEntry();
                    }
                }

                splitFile(tempFile, file, splitSize);

            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }

        /**
         * 将文件分割成多个分卷。
         *
         * @param sourceFile 源文件
         * @param outputFile 输出文件名
         * @param maxSize    每个分卷的最大字节数
         * @throws IOException IO 异常
         */
        private void splitFile(File sourceFile, File outputFile, long maxSize) throws IOException {
            String baseName = outputFile.getName();
            String baseNameWithoutExt = baseName;
            int lastDot = baseName.lastIndexOf('.');
            if (lastDot > 0) {
                baseNameWithoutExt = baseName.substring(0, lastDot);
            }

            long fileLength = sourceFile.length();
            if (fileLength <= maxSize) {
                copyFile(sourceFile, outputFile);
                return;
            }

            try (FileInputStream fis = new FileInputStream(sourceFile)) {
                byte[] buffer = new byte[8192];
                int partNumber = 1;
                long bytesWrittenInPart = 0;
                long totalBytesRead = 0;
                FileOutputStream currentFos = null;

                try {
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        if (currentFos == null || bytesWrittenInPart >= maxSize) {
                            if (currentFos != null) {
                                currentFos.close();
                                currentFos = null;
                            }

                            long bytesRemaining = fileLength - totalBytesRead;
                            boolean isLastPart = bytesRemaining <= maxSize;

                            File partFile;
                            if (isLastPart) {
                                partFile = outputFile;
                            } else {
                                String partExtension = String.format(".%03d", partNumber);
                                partFile = new File(outputFile.getParent(),
                                        baseNameWithoutExt + partExtension);
                            }
                            currentFos = new FileOutputStream(partFile);
                            bytesWrittenInPart = 0;
                        }

                        currentFos.write(buffer, 0, bytesRead);
                        bytesWrittenInPart += bytesRead;
                        totalBytesRead += bytesRead;
                    }
                } finally {
                    if (currentFos != null) {
                        currentFos.close();
                    }
                }
            }
        }

        /**
         * 复制文件。
         *
         * @param source 源文件
         * @param target 目标文件
         * @throws IOException IO 异常
         */
        private void copyFile(File source, File target) throws IOException {
            try (FileInputStream fis = new FileInputStream(source);
                 FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            }
        }

        /**
          * 根据配置的压缩方法和级别创建 sevenz输出文件。
         * @return 创建输出文件的结果
         */
        private SevenZOutputFile createOutputFile() throws IOException {
            return createOutputFile(file);
        }

        /**
          * 根据配置的压缩方法和级别创建 sevenz输出文件。
         *
         * @param outputFile 输出文件
         * @return SevenZOutputFile 实例
         * @throws IOException IO 异常
         */
        private SevenZOutputFile createOutputFile(File outputFile) throws IOException {
            SevenZOutputFile szOut = new SevenZOutputFile(outputFile);
            if (compressionMethod != null) {
                szOut.setContentMethods(
                        Collections.singletonList(
                                compressionLevel >= 0
                                        ? new SevenZMethodConfiguration(compressionMethod, compressionLevel)
                                        : new SevenZMethodConfiguration(compressionMethod)));
            }
            return szOut;
        }

        /**
         * 写入文件
         *
         * @param out 出
         * @param file 文件
         */
        private void writeFile(SevenZOutputFile out, File file) throws IOException {
            try (FileInputStream fis = new FileInputStream(file)) {
                writeStream(out, fis);
            }
        }

        /**
         * 写入流
         *
         * @param out 出
         * @param in 入
         */
        private void writeStream(SevenZOutputFile out, InputStream in) throws IOException {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            in.close();
        }

        @Override
        /**
         * 写入
         *
         * @param data 数据
         * @return 写入的结果
         * @author CH
         * @since 4.0.0
         */
        public SevenZWriteBuilder write(Object data) {
            if (data instanceof File) {
                addFile(((File) data).getName(), (File) data);
            } else if (data instanceof byte[]) {
                addBytes(String.valueOf(data), (byte[]) data);
            } else if (data instanceof InputStream) {
                addStream(String.valueOf(data), (InputStream) data);
            } else if (data != null) {
                addBytes(String.valueOf(data), data.toString().getBytes(StandardCharsets.UTF_8));
            }
            return this;
        }

        private static class EntryData {
            final String entryName; final File source; // entry名称
            final InputStream inputStream; final byte[] bytes; // 输入流

            EntryData(String entryName, File source) {
                this.entryName = entryName; this.source = source;
                this.inputStream = null; this.bytes = null;
            }
            EntryData(String entryName, InputStream inputStream) {
                this.entryName = entryName; this.source = null;
                this.inputStream = inputStream; this.bytes = null;
            }
            EntryData(String entryName, byte[] bytes) {
                this.entryName = entryName; this.source = null;
                this.inputStream = null; this.bytes = bytes;
            }
            String getEntryName() { return entryName; }
            File getSource() { return source; }
            InputStream getInputStream() { return inputStream; }
            byte[] getBytes() { return bytes; }
        }
    }
}
