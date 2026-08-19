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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    public String getType() {
        return "7z";
    }

    @Override
    public ReadBuilder read(File file) {
        return new SevenZReadBuilder(file);
    }

    @Override
    public WriteBuilder write(File file) {
        return new SevenZWriteBuilder(file);
    }

    public static class SevenZReadBuilder extends ReadBuilder {

        SevenZReadBuilder(File file) {
            super(file);
        }

        public List<String> listEntries() {
            List<String> entries = new ArrayList<>();
            try (SevenZFile szFile = new SevenZFile(file)) {
                SevenZArchiveEntry entry;
                while ((entry = szFile.getNextEntry()) != null) {
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
            try (SevenZFile szFile = new SevenZFile(file)) {
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
        public Object read() {
            return listEntries();
        }

        /**
         * 读取压缩包中指定文件的内容为字符串。
         *
         * @param entryName 条目名称
         * @return 文件内容字符串，若条目不存在返回 null
         * @throws UncheckedIOException 如果 IO 异常
         */
        public String readEntry(String entryName) {
            try (SevenZFile szFile = new SevenZFile(file)) {
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

        SevenZWriteBuilder(File file) {
            super(file);
        }

        /**
         * 设置压缩方法。
         *
         * @param method 压缩方法（LZMA2、COPY、DEFLATE、BZIP2 等）
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

        public SevenZWriteBuilder addFile(String entryName, File source) {
            entries.add(new EntryData(entryName, source));
            return this;
        }

        public SevenZWriteBuilder addStream(String entryName, InputStream in) {
            entries.add(new EntryData(entryName, in));
            return this;
        }

        public SevenZWriteBuilder addBytes(String entryName, byte[] bytes) {
            entries.add(new EntryData(entryName, bytes));
            return this;
        }

        @Override
        public void finish() {
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

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
         * 根据配置的压缩方法和级别创建 SevenZOutputFile。
         */
        private SevenZOutputFile createOutputFile() throws IOException {
            SevenZOutputFile szOut = new SevenZOutputFile(file);
            if (compressionMethod != null) {
                szOut.setContentMethods(
                        Collections.singletonList(
                                compressionLevel >= 0
                                        ? new SevenZMethodConfiguration(compressionMethod, compressionLevel)
                                        : new SevenZMethodConfiguration(compressionMethod)));
            }
            return szOut;
        }

        private void writeFile(SevenZOutputFile out, File file) throws IOException {
            try (FileInputStream fis = new FileInputStream(file)) {
                writeStream(out, fis);
            }
        }

        private void writeStream(SevenZOutputFile out, InputStream in) throws IOException {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            in.close();
        }

        @Override
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
            final String entryName; final File source;
            final InputStream inputStream; final byte[] bytes;

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
