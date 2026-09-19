package com.chua.common.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;

/**
 * ZIP 压缩文件系统 SPI 实现。
 *
 * <p>通过 SPI 机制注册为 {@code "zip"} / {@code "archive"} 类型的文件系统实现。
 * 支持链式创建压缩包和读取/提取压缩包。</p>
 *
 * <p>写操作示例：</p>
 * <pre>{@code
 * FileSystem zip = FileSystem.create("zip");
 * zip.write(new File("output.zip"))
 *    .addFile("dir/a.txt", new File("a.txt"))
 *    .addStream("dir/b.txt", inputStream)
 *    .addBytes("dir/c.txt", bytes)
 *    .finish();
 * }</pre>
 *
 * <p>读操作示例：</p>
 * <pre>{@code
 * // 全部提取
 * zip.read(new File("input.zip")).extractAll(targetDir);
 *
 * // 指定文件提取
 * zip.read(new File("input.zip")).extract("a.txt", "b.txt", targetDir);
 *
 * // 列出所有条目
 * List<String> entries = zip.read(new File("input.zip")).listEntries();
 *
 * // 分卷压缩写入
 * zip.write(new File("output.zip"))
 *    .splitSize(1024 * 1024 * 100) // 100MB 分卷
 *    .addFile("large-file.bin", new File("large-file.bin"))
 *    .finish();
 *
 * // 分卷压缩读取（自动检测分卷文件）
 * zip.read(new File("output.zip"))
 *    .split() // 启用分卷读取模式
 *    .extractAll(targetDir);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@Spi({"zip"})
public class ZipFileSystem implements FileSystem {

    @Override
    /** 获取Type */
    public String getType() {
        return "zip";
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        return new ZipReadBuilder(file);
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        return new ZipWriteBuilder(file);
    }

    /**
    * ZIP 文件读取构建器。
    *
    * @since 1.0.0
    */
    public static class ZipReadBuilder extends ReadBuilder {

        /** 是否启用分卷读取模式 */
        private boolean splitMode = false;

        ZipReadBuilder(File file) {
            super(file);
        }

        /**
        * 启用分卷读取模式。
        * <p>启用后将自动检测同目录下的分卷文件（.z01, .z02 等）并合并读取。</p>
        *
        * @return 当前构建器
        */
        public ZipReadBuilder split() {
            this.splitMode = true;
            return this;
        }

        /**
         * 列出压缩包中所有条目名称。
         *
         * @return 条目名称列表
         */
        public List<String> listEntries() {
            List<String> entries = new ArrayList<>();
            if (splitMode) {
                try (InputStream mergedInputStream = createMergedInputStream()) {
                    try (ZipInputStream zis = new ZipInputStream(mergedInputStream)) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            entries.add(entry.getName());
                            zis.closeEntry();
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                try (ZipFile zipFile = new ZipFile(file, StandardCharsets.UTF_8)) {
                    Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
                    while (enumeration.hasMoreElements()) {
                        entries.add(enumeration.nextElement().getName());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return entries;
        }

        /**
         * 将压缩包全部内容提取到目标目录。
         *
         * @param targetDir 目标目录
         */
        public void extractAll(File targetDir) {
            extract(targetDir);
        }

        /**
         * 创建合并的输入流，用于分卷读取。
         *
         * @return 合并后的输入流
         * @throws IOException IO 异常
         */
        private InputStream createMergedInputStream() throws IOException {
            List<File> splitFiles = findSplitFiles();
            if (splitFiles.isEmpty()) {
                // 没有找到分卷文件，直接返回原文件的输入流
                return new FileInputStream(file);
            }
            return new MergedInputStream(splitFiles);
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
                return splitFiles;
            }

            String baseName = file.getName();
            String baseNameWithoutExt = baseName;
            int lastDot = baseName.lastIndexOf('.');
            if (lastDot > 0) {
                baseNameWithoutExt = baseName.substring(0, lastDot);
            }

            // 查找 .z01, .z02, ... 等分卷文件
            String prefix = baseNameWithoutExt + ".z";
            File[] files = parentDir.listFiles((dir, name) ->
                    name.startsWith(prefix) &&
                    name.length() >= prefix.length() + 1);

            if (files != null) {
                // 按分卷编号排序
                List<File> sortedFiles = new ArrayList<>();
                for (File f : files) {
                    String name = f.getName();
                    String ext = name.substring(prefix.length());
                    if (ext.matches("\\d+")) {
                        sortedFiles.add(f);
                    }
                }
                splitFiles.addAll(sortedFiles);
            }

            // 最后添加主 .zip 文件
            splitFiles.add(file);
            return splitFiles;
        }

        /**
         * 合并多个分卷文件的输入流。
         */
        private static class MergedInputStream extends InputStream {
            private final List<File> files;
            private int currentIndex = 0;
            private FileInputStream currentStream;

            MergedInputStream(List<File> files) throws FileNotFoundException {
                this.files = files;
                if (!files.isEmpty()) {
                    this.currentStream = new FileInputStream(files.getFirst());
                }
            }

            @Override
            public int read() throws IOException {
                if (currentStream == null) {
                    return -1;
                }
                int b = currentStream.read();
                if (b == -1) {
                    // 当前文件读完，切换到下一个文件
                    currentStream.close();
                    currentIndex++;
                    if (currentIndex < files.size()) {
                        currentStream = new FileInputStream(files.get(currentIndex));
                        return currentStream.read();
                    }
                    return -1;
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (currentStream == null) {
                    return -1;
                }
                int bytesRead = currentStream.read(b, off, len);
                if (bytesRead == -1) {
                    // 当前文件读完，切换到下一个文件
                    currentStream.close();
                    currentIndex++;
                    if (currentIndex < files.size()) {
                        currentStream = new FileInputStream(files.get(currentIndex));
                        // 递归读取，但先检查新文件是否能读取数据
                        int result = currentStream.read(b, off, len);
                        return result == -1 ? read(b, off, len) : result;
                    }
                    return -1;
                }
                return bytesRead;
            }

            @Override
            public void close() throws IOException {
                if (currentStream != null) {
                    currentStream.close();
                }
            }
        }

        /**
         * 将压缩包中指定条目提取到目标目录。
         *
         * @param entryName 要提取的条目名称
         * @param targetDir 目标目录
         */
        public void extract(String entryName, File targetDir) {
            extract(targetDir, entryName);
        }

        /**
         * 将压缩包中指定一个或多个条目提取到目标目录。
         *
         * @param targetDir  目标目录
         * @param entryNames 要提取的条目名称（不限数量）
         */
        public void extract(File targetDir, String... entryNames) {
            if (splitMode) {
                extractSplit(targetDir, entryNames);
            } else {
                extractNormal(targetDir, entryNames);
            }
        }

        /**
         * 分卷模式下提取文件。
         *
         * @param targetDir  目标目录
         * @param entryNames 要提取的条目名称
         */
        private void extractSplit(File targetDir, String... entryNames) {
            try (InputStream mergedInputStream = createMergedInputStream();
                 ZipInputStream zis = new ZipInputStream(mergedInputStream)) {
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }

                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
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
                        throw new IOException("ZIP entry outside target: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (OutputStream os = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                os.write(buffer, 0, len);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * 普通模式下提取文件。
         *
         * @param targetDir  目标目录
         * @param entryNames 要提取的条目名称
         */
        private void extractNormal(File targetDir, String... entryNames) {
            try (ZipFile zipFile = new ZipFile(file, StandardCharsets.UTF_8)) {
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }

                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();

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
                        throw new IOException("ZIP entry outside target: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (InputStream is = zipFile.getInputStream(entry);
                             OutputStream os = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = is.read(buffer)) > 0) {
                                os.write(buffer, 0, len);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * 读取压缩包中指定文件的内容为字符串。
         *
         * @param entryName 条目名称
         * @return 文件内容字符串
         * @throws UncheckedIOException 如果 IO 异常
         */
        public String readEntry(String entryName) {
            if (splitMode) {
                return readEntrySplit(entryName);
            }
            try (ZipFile zipFile = new ZipFile(file, StandardCharsets.UTF_8)) {
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null) {
                    return null;
                }
                try (InputStream is = zipFile.getInputStream(entry);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    return baos.toString(StandardCharsets.UTF_8.name());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * 分卷模式下读取指定条目的内容。
         *
         * @param entryName 条目名称
         * @return 文件内容字符串
         */
        private String readEntrySplit(String entryName) {
            try (InputStream mergedInputStream = createMergedInputStream();
                 ZipInputStream zis = new ZipInputStream(mergedInputStream)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().equals(entryName)) {
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                baos.write(buffer, 0, len);
                            }
                            return baos.toString(StandardCharsets.UTF_8.name());
                        }
                    }
                    zis.closeEntry();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        }

        @Override
        /** 读取 */
        public Object read() {
            return listEntries();
        }

        @Override
        /** AsString */
        public String asString() {
            return String.join("\n", listEntries());
        }
    }

    /**
     * ZIP 文件写入构建器。
     *
     * <p>支持链式调用添加文件、流、字节数组到压缩包。</p>
     *
     * @since 1.0.0
     */
    public static class ZipWriteBuilder extends WriteBuilder {

        /** ZIP 条目列表 */
        private final List<ZipEntryData> entries = new ArrayList<>();

        /** 压缩级别（0~9，-1 为默认） */
        private int compressionLevel = Deflater.DEFAULT_COMPRESSION;

        /** 分卷大小（字节），0 表示不分卷 */
        private long splitSize = 0;

        ZipWriteBuilder(File file) {
            super(file);
        }

        /**
        * 设置压缩级别。
        *
        * @param level 压缩级别（0=不压缩, 1=BEST_SPEED, 9=BEST_COMPRESSION, -1=默认）
        * @return 当前构建器
        */
        public ZipWriteBuilder compressionLevel(int level) {
            this.compressionLevel = level;
            return this;
        }

        /**
         * 设置分卷大小。
         *
         * @param size 每个分卷的最大字节数
         * @return 当前构建器
         */
        public ZipWriteBuilder splitSize(long size) {
            this.splitSize = size;
            return this;
        }

        /**
         * 添加文件到压缩包。
         *
         * @param entryName 压缩包内的条目名称（路径）
         * @param source    源文件
         * @return 当前构建器
         */
        public ZipWriteBuilder addFile(String entryName, File source) {
            entries.add(new ZipEntryData(entryName, source));
            return this;
        }

        /**
         * 添加输入流到压缩包。
         *
         * @param entryName 压缩包内的条目名称（路径）
         * @param in        输入流（读取后会自动关闭）
         * @return 当前构建器
         */
        public ZipWriteBuilder addStream(String entryName, InputStream in) {
            entries.add(new ZipEntryData(entryName, in));
            return this;
        }

        /**
         * 添加字节数组到压缩包。
         *
         * @param entryName 压缩包内的条目名称（路径）
         * @param bytes     字节数组内容
         * @return 当前构建器
         */
        public ZipWriteBuilder addBytes(String entryName, byte[] bytes) {
            entries.add(new ZipEntryData(entryName, bytes));
            return this;
        }

        @Override
        /** Finish */
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
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
                zos.setLevel(compressionLevel);

                for (ZipEntryData entryData : entries) {
                    ZipEntry entry = new ZipEntry(entryData.getEntryName());
                    entry.setTime(System.currentTimeMillis());
                    zos.putNextEntry(entry);

                    if (entryData.getSource() != null) {
                        writeFile(zos, entryData.getSource());
                    } else if (entryData.getInputStream() != null) {
                        writeStream(zos, entryData.getInputStream());
                    } else if (entryData.getBytes() != null) {
                        zos.write(entryData.getBytes());
                    }

                    zos.closeEntry();
                }

                zos.flush();
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
                // 先写入临时文件
                tempFile = File.createTempFile("zip-split-", ".zip", file.getParentFile());
                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempFile))) {
                    zos.setLevel(compressionLevel);

                    for (ZipEntryData entryData : entries) {
                        ZipEntry entry = new ZipEntry(entryData.getEntryName());
                        entry.setTime(System.currentTimeMillis());
                        zos.putNextEntry(entry);

                        if (entryData.getSource() != null) {
                            writeFile(zos, entryData.getSource());
                        } else if (entryData.getInputStream() != null) {
                            writeStream(zos, entryData.getInputStream());
                        } else if (entryData.getBytes() != null) {
                            zos.write(entryData.getBytes());
                        }

                        zos.closeEntry();
                    }
                    zos.flush();
                }

                // 分割临时文件为多个分卷
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
         * <p>分割后的文件命名规则：
         * <ul>
         *     <li>第一个分卷：.z01</li>
         *     <li>第二个分卷：.z02</li>
         *     <li>...</li>
         *     <li>最后一个分卷（包含中央目录）：.zip</li>
         * </ul>
         *
         *
         * @param sourceFile 源文件（完整的 ZIP 文件）
         * @param outputFile 目标文件名（.zip 结尾）
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
                // 文件未超过分卷大小，直接复制为目标文件
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
                            // 关闭当前分卷
                            if (currentFos != null) {
                                currentFos.close();
                                currentFos = null;
                            }

                            // 计算剩余字节数（使用累加计数器而非 fis.available()）
                            long bytesRemaining = fileLength - totalBytesRead;
                            boolean isLastPart = bytesRemaining <= maxSize;

                            // 创建新分卷文件
                            File partFile;
                            if (isLastPart) {
                                // 最后一个分卷使用 .zip 扩展名
                                partFile = outputFile;
                            } else {
                                String partExtension = String.format(".z%02d", partNumber);
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

        /** 写入File */
        private void writeFile(ZipOutputStream zos, File file) throws IOException {
            try (FileInputStream fis = new FileInputStream(file)) {
                writeStream(zos, fis);
            }
        }

        /** 写入Stream */
        private void writeStream(ZipOutputStream zos, InputStream in) throws IOException {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
            in.close();
        }

        @Override
        /** 写入 */
        public ZipWriteBuilder write(Object data) {
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

        private static class ZipEntryData {
            /** 条目名称 */
            private final String entryName;
            /**
             * 数据源
             */
            private final File source;
            /** 输入流 */
            private final InputStream inputStream;
            /** 字节数组 */
            private final byte[] bytes;

            ZipEntryData(String entryName, File source) {
                this.entryName = entryName;
                this.source = source;
                this.inputStream = null;
                this.bytes = null;
            }

            ZipEntryData(String entryName, InputStream inputStream) {
                this.entryName = entryName;
                this.source = null;
                this.inputStream = inputStream;
                this.bytes = null;
            }

            ZipEntryData(String entryName, byte[] bytes) {
                this.entryName = entryName;
                this.source = null;
                this.inputStream = null;
                this.bytes = bytes;
            }

            String getEntryName() {
                return entryName;
            }

            File getSource() {
                return source;
            }

            InputStream getInputStream() {
                return inputStream;
            }

            byte[] getBytes() {
                return bytes;
            }
        }
    }
}
