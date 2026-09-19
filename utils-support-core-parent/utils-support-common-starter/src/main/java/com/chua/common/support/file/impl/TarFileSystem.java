package com.chua.common.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.file.tar.TarEntry;
import com.chua.common.support.file.tar.TarHeader;
import com.chua.common.support.file.tar.TarInputStream;
import com.chua.common.support.file.tar.TarOutputStream;
import com.chua.common.support.spi.annotations.Spi;
import lombok.Getter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * TAR 归档文件系统 SPI 实现。
 *
 * <p>通过 SPI 机制注册为 {@code "tar"} 类型的文件系统实现。基于纯 Java 实现
 * （{@code com.chua.common.support.file.tar}），不依赖任何第三方 tar 库。</p>
 *
 * <p>特性：
 * <ul>
 *     <li>支持链式创建 tar 包（文件 / 输入流 / 字节数组三种数据源）</li>
 *     <li>支持读取条目列表、提取全部 / 部分条目、按名称读取内容</li>
 *     <li>写入时自动写入 EOF 块，读取时自动跳过填充字节</li>
 *     <li>支持分卷压缩（.tar.gz.01, .tar.gz.02, ...）</li>
 * </ul>
 *
 * <h2>写操作示例</h2>
 * <pre>{@code
 * FileSystem tar = FileSystem.create("tar");
 * tar.write(new File("output.tar"))
 *    .addFile("dir/a.txt", new File("a.txt"))
 *    .addStream("dir/b.txt", inputStream)
 *    .addBytes("dir/c.txt", bytes)
 *    .finish();
 * }</pre>
 *
 * <h2>读操作示例</h2>
 * <pre>{@code
 * // 全部提取
 * tar.read(new File("input.tar")).extractAll(targetDir);
 *
 * // 指定文件提取
 * tar.read(new File("input.tar")).extract("dir/a.txt", targetDir);
 *
 * // 列出所有条目
 * List<String> entries = tar.read(new File("input.tar")).listEntries();
 *
 * // 分卷压缩写入（.tar.gz 分卷）
 * tar.write(new File("output.tar.gz"))
 *    .gz()
 *    .splitSize(1024 * 1024 * 100) // 100MB 分卷
 *    .addFile("large-file.bin", new File("large-file.bin"))
 *    .finish();
 * // Creates: output.tar.gz.01, output.tar.gz.02, ...
 *
 * // 分卷压缩读取（自动检测分卷文件）
 * tar.read(new File("output.tar.gz"))
 *    .gz()
 *    .split() // 启用分卷读取模式
 *    .extractAll(targetDir);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("tar")
public class TarFileSystem implements FileSystem {

    /**
     * 读写缓冲区的字节数
     */
    private static final int BUFFER_SIZE = 8192;

    /**
     * 一次性内存块大小（用于读取流式数据为字节数组）
     */
    private static final int STREAM_BUFFER_SIZE = 8192;

    /**
     * 归档条目名分隔符
     */
    private static final String ENTRY_NAME_SEPARATOR = "/";

    /**
     * 提取操作错误信息：条目路径在目标目录之外，防止 Zip Slip 攻击
     */
    private static final String ERROR_ENTRY_OUTSIDE_TARGET = "TAR entry outside target: ";

    @Override
    /** 获取Type */
    public String getType() {
        return "tar";
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        return new TarReadBuilder(file);
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        return new TarWriteBuilder(file);
    }

    /**
    * TAR 文件读取构建器。
    *
    * @since 1.0.0
    */
    public static class TarReadBuilder extends ReadBuilder {

        /** 是否启用 GZIP 解包 */
        private boolean gzipEnabled;

        /** 是否启用分卷读取模式 */
        private boolean splitMode = false;

        TarReadBuilder(File file) {
            super(file);
        }

        /**
         * 启用分卷读取模式。
         * <p>启用后将自动检测同目录下的分卷文件并合并读取。</p>
         *
         * @return 当前构建器
         */
        public TarReadBuilder split() {
            this.splitMode = true;
            return this;
        }

        /**
         * 启用 GZIP 解包（读取 .tar.gz 文件时使用）。
         *
         * @return 当前构建器
         */
        public TarReadBuilder gz() {
            this.gzipEnabled = true;
            return this;
        }

        /**
         * 创建输入流，自动判断是否使用 GZIP 解包。
         */
        private InputStream openInput() throws IOException {
            InputStream is;
            if (splitMode) {
                is = createMergedInputStream();
            } else {
                is = new BufferedInputStream(new FileInputStream(file));
            }
            if (gzipEnabled) {
                return new GZIPInputStream(is);
            }
            return is;
        }

        /**
         * 创建合并的输入流，用于分卷读取。
         *
         * @return 合并后的输入流
         * @throws IOException IO 异常
         */
        private InputStream createMergedInputStream() throws IOException {
            List<File> splitFiles = findSplitFiles();
            if (splitFiles.size() <= 1) {
                return new BufferedInputStream(new FileInputStream(file));
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
                    currentStream.close();
                    currentIndex++;
                    if (currentIndex < files.size()) {
                        currentStream = new FileInputStream(files.get(currentIndex));
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

        public List<String> listEntries() {
            List<String> entries = new ArrayList<>();
            try (TarInputStream tis = new TarInputStream(openInput())) {
                TarEntry entry;
                while ((entry = tis.getNextEntry()) != null) {
                    entries.add(entry.getName());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return entries;
        }

        public void extractAll(File targetDir) {
            extract(targetDir);
        }

        public void extract(String entryName, File targetDir) {
            extract(targetDir, entryName);
        }

        public void extract(File targetDir, String... entryNames) {
            try (TarInputStream tis = new TarInputStream(openInput())) {
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
                String canonicalTarget = targetDir.getCanonicalPath();

                TarEntry entry;
                while ((entry = tis.getNextEntry()) != null) {
                    if (entryNames != null && entryNames.length > 0) {
                        boolean matched = false;
                        for (String name : entryNames) {
                            String prefix = name + ENTRY_NAME_SEPARATOR;
                            if (entry.getName().equals(name) || entry.getName().startsWith(prefix)) {
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) {
                            continue;
                        }
                    }

                    File outFile = new File(targetDir, entry.getName());
                    if (!outFile.getCanonicalPath().startsWith(canonicalTarget)) {
                        throw new IOException(ERROR_ENTRY_OUTSIDE_TARGET + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                        continue;
                    }

                    outFile.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = tis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public String readEntry(String entryName) {
            try (TarInputStream tis = new TarInputStream(openInput())) {
                TarEntry entry;
                while ((entry = tis.getNextEntry()) != null) {
                    if (entry.getName().equals(entryName)) {
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int len;
                            while ((len = tis.read(buffer)) > 0) {
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
        public Object read() {
            return listEntries();
        }

        @Override
        public String asString() {
            return String.join("\n", listEntries());
        }
    }

    /**
     * TAR 文件写入构建器。
     *
     * @since 1.0.0
     */
    public static class TarWriteBuilder extends WriteBuilder {

        private final List<TarEntryData> entries = new ArrayList<>();
        private boolean gzipEnabled;
        private int gzipLevel = Deflater.DEFAULT_COMPRESSION;
        private long splitSize = 0;

        TarWriteBuilder(File file) {
            super(file);
        }

        public TarWriteBuilder gz() {
            this.gzipEnabled = true;
            return this;
        }

        public TarWriteBuilder gz(int level) {
            this.gzipEnabled = true;
            this.gzipLevel = level;
            return this;
        }

        public TarWriteBuilder splitSize(long size) {
            this.splitSize = size;
            return this;
        }

        public TarWriteBuilder addFile(String entryName, File source) {
            entries.add(new TarEntryData(entryName, source));
            return this;
        }

        public TarWriteBuilder addStream(String entryName, InputStream in) {
            entries.add(new TarEntryData(entryName, drain(in)));
            return this;
        }

        public TarWriteBuilder addBytes(String entryName, byte[] bytes) {
            entries.add(new TarEntryData(entryName, bytes));
            return this;
        }

        @Override
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

        private void finishNormal() {
            try (OutputStream fos = new FileOutputStream(file);
                 OutputStream bos = new BufferedOutputStream(fos);
                 OutputStream gzos = gzipEnabled ? new GZIPOutputStream(bos) {{
                         def.setLevel(gzipLevel);
                     }} : bos;
                 TarOutputStream tos = new TarOutputStream(gzos)) {

                for (TarEntryData entryData : entries) {
                    TarEntry entry = entryData.toTarEntry();
                    if (entry == null) {
                        continue;
                    }
                    tos.putNextEntry(entry);

                    if (entryData.getSource() != null) {
                        writeFile(tos, entryData.getSource());
                    } else if (entryData.getBytes() != null) {
                        tos.write(entryData.getBytes());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void finishSplit() {
            File tempFile = null;
            try {
                tempFile = File.createTempFile("tar-split-", ".tar", file.getParentFile());
                try (OutputStream fos = new FileOutputStream(tempFile);
                     OutputStream bos = new BufferedOutputStream(fos);
                     OutputStream gzos = gzipEnabled ? new GZIPOutputStream(bos) {{
                             def.setLevel(gzipLevel);
                         }} : bos;
                     TarOutputStream tos = new TarOutputStream(gzos)) {

                    for (TarEntryData entryData : entries) {
                        TarEntry entry = entryData.toTarEntry();
                        if (entry == null) {
                            continue;
                        }
                        tos.putNextEntry(entry);

                        if (entryData.getSource() != null) {
                            writeFile(tos, entryData.getSource());
                        } else if (entryData.getBytes() != null) {
                            tos.write(entryData.getBytes());
                        }
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
                                String partExtension = String.format(".%02d", partNumber);
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

        private void writeFile(TarOutputStream tos, File source) throws IOException {
            if (!source.isFile() || !source.canRead()) {
                return;
            }
            try (InputStream is = new BufferedInputStream(new FileInputStream(source))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    tos.write(buffer, 0, len);
                }
            }
        }

        @Override
        public TarWriteBuilder write(Object data) {
            if (data == null) {
                return this;
            }
            if (data instanceof File) {
                addFile(((File) data).getName(), (File) data);
            } else if (data instanceof byte[]) {
                addBytes(String.valueOf(data), (byte[]) data);
            } else if (data instanceof InputStream) {
                addStream(String.valueOf(data), (InputStream) data);
            } else {
                addBytes(String.valueOf(data), data.toString().getBytes(StandardCharsets.UTF_8));
            }
            return this;
        }

        private static byte[] drain(InputStream inputStream) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[STREAM_BUFFER_SIZE];
                int len;
                while ((len = inputStream.read(buf)) > 0) {
                    baos.write(buf, 0, len);
                }
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Getter
    private static final class TarEntryData {

        private final String entryName;
        private final File source;
        private final byte[] bytes;

        TarEntryData(String entryName, File source) {
            this.entryName = entryName;
            this.source = source;
            this.bytes = null;
        }

        TarEntryData(String entryName, byte[] bytes) {
            this.entryName = entryName;
            this.source = null;
            this.bytes = bytes;
        }

        TarEntry toTarEntry() {
            if (source != null) {
                return new TarEntry(source, entryName);
            }
            byte[] data = bytes == null ? new byte[0] : bytes;
            TarHeader header = new TarHeader();
            header.name = new StringBuffer(entryName);
            header.size = data.length;
            header.modTime = System.currentTimeMillis() / 1000L;
            return new TarEntry(header);
        }
    }
}
