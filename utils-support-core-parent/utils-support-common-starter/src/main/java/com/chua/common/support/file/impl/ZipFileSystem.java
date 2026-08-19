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
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@Spi({"zip"})
public class ZipFileSystem implements FileSystem {

    @Override
    public String getType() {
        return "zip";
    }

    @Override
    public ReadBuilder read(File file) {
        return new ZipReadBuilder(file);
    }

    @Override
    public WriteBuilder write(File file) {
        return new ZipWriteBuilder(file);
    }

    /**
     * ZIP 文件读取构建器。
     *
     * @since 1.0.0
     */
    public static class ZipReadBuilder extends ReadBuilder {

        ZipReadBuilder(File file) {
            super(file);
        }

        /**
         * 列出压缩包中所有条目名称。
         *
         * @return 条目名称列表
         */
        public List<String> listEntries() {
            List<String> entries = new ArrayList<>();
            try (ZipFile zipFile = new ZipFile(file, StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
                while (enumeration.hasMoreElements()) {
                    entries.add(enumeration.nextElement().getName());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
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
     * ZIP 文件写入构建器。
     *
     * <p>支持链式调用添加文件、流、字节数组到压缩包。</p>
     *
     * @since 1.0.0
     */
    public static class ZipWriteBuilder extends WriteBuilder {

        /** ZIP 条目列表 */
        /** Entries */
        private final List<ZipEntryData> entries = new ArrayList<>();

        /** 压缩级别（0~9，-1 为默认） */
        /** Compression级别 */
        private int compressionLevel = Deflater.DEFAULT_COMPRESSION;

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
        public void finish() {
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

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

        private void writeFile(ZipOutputStream zos, File file) throws IOException {
            try (FileInputStream fis = new FileInputStream(file)) {
                writeStream(zos, fis);
            }
        }

        private void writeStream(ZipOutputStream zos, InputStream in) throws IOException {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
            in.close();
        }

        @Override
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
            /** Entry名称 */
            private final String entryName;
            /**
             * 数据源
             */
            private final File source;
            /** 输入流 */
            private final InputStream inputStream;
            /** 字节数组 */
            /** Bytes */
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
