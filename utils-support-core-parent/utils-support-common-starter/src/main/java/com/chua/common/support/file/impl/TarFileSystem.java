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
import org.jspecify.annotations.NullUnmarked;

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
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
@SuppressWarnings("NullAway")
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
    public String getType() {
        return "tar";
    }

    @Override
    public ReadBuilder read(File file) {
        return new TarReadBuilder(file);
    }

    @Override
    public WriteBuilder write(File file) {
        return new TarWriteBuilder(file);
    }

    /**
     * TAR 文件读取构建器。
     *
     * @author CH
     * @since 1.0.0
     */
    public static class TarReadBuilder extends ReadBuilder {

        /** 是否启用 GZIP 解包 */
        private boolean gzipEnabled;

        TarReadBuilder(File file) {
            super(file);
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
            InputStream is = new BufferedInputStream(new FileInputStream(file));
            if (gzipEnabled) {
                return new GZIPInputStream(is);
            }
            return is;
        }

        /**
         * 列出归档包中所有条目名称。
         *
         * @return 条目名称列表
         */
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

        /**
         * 将归档包全部内容提取到目标目录。
         *
         * @param targetDir 目标目录
         */
        public void extractAll(File targetDir) {
            extract(targetDir);
        }

        /**
         * 将归档包中指定条目提取到目标目录。
         *
         * @param entryName 要提取的条目名称
         * @param targetDir 目标目录
         */
        public void extract(String entryName, File targetDir) {
            extract(targetDir, entryName);
        }

        /**
         * 将归档包中指定一个或多个条目提取到目标目录。
         *
         * <p>未指定 entryNames 时提取全部内容。提取过程中会校验每个条目路径
         * 必须在 targetDir 之内，防止 Zip Slip 攻击。</p>
         *
         * @param targetDir  目标目录
         * @param entryNames 要提取的条目名称（不限数量），为空表示全部提取
         */
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

        /**
         * 读取归档包中指定文件的内容为字符串（UTF-8 编码）。
         *
         * @param entryName 条目名称
         * @return 文件内容字符串，未找到返回 null
         */
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
     * <p>支持链式调用添加文件、流、字节数组到归档包；调用 {@link #finish()} 完成写入。</p>
     *
     * @author CH
     * @since 1.0.0
     */
    public static class TarWriteBuilder extends WriteBuilder {

        /**
         * 待写入归档包的全部条目
         */
        private final List<TarEntryData> entries = new ArrayList<>();

        /** 是否启用 GZIP 压缩 */
        private boolean gzipEnabled;

        /** GZIP 压缩级别（0~9，-1 为默认） */
        private int gzipLevel = Deflater.DEFAULT_COMPRESSION;

        TarWriteBuilder(File file) {
            super(file);
        }

        /**
         * 启用 GZIP 压缩（输出 .tar.gz 文件时使用）。
         *
         * @return 当前构建器
         */
        public TarWriteBuilder gz() {
            this.gzipEnabled = true;
            return this;
        }

        /**
         * 启用 GZIP 压缩并指定压缩级别。
         *
         * @param level 压缩级别（0=不压缩, 1=BEST_SPEED, 9=BEST_COMPRESSION, -1=默认）
         * @return 当前构建器
         */
        public TarWriteBuilder gz(int level) {
            this.gzipEnabled = true;
            this.gzipLevel = level;
            return this;
        }

        /**
         * 添加文件到归档包。
         *
         * @param entryName 归档包内的条目名称（路径）
         * @param source    源文件
         * @return 当前构建器
         */
        public TarWriteBuilder addFile(String entryName, File source) {
            entries.add(new TarEntryData(entryName, source));
            return this;
        }

        /**
         * 添加输入流到归档包（流数据在调用本方法时立即读取为字节数组）。
         *
         * @param entryName 归档包内的条目名称（路径）
         * @param in        输入流（读取后自动关闭）
         * @return 当前构建器
         */
        public TarWriteBuilder addStream(String entryName, InputStream in) {
            entries.add(new TarEntryData(entryName, drain(in)));
            return this;
        }

        /**
         * 添加字节数组到归档包。
         *
         * @param entryName 归档包内的条目名称（路径）
         * @param bytes     字节数组内容
         * @return 当前构建器
         */
        public TarWriteBuilder addBytes(String entryName, byte[] bytes) {
            entries.add(new TarEntryData(entryName, bytes));
            return this;
        }

        /**
         * 完成写入：遍历 entries 写入 TarOutputStream，自动写入 EOF 块。
         */        @Override
        public void finish() {
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

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
                // try-with-resources 自动关闭：tos → gzos (或 bos) → bos → fos
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * 将源文件内容写入 TarOutputStream。
         *
         * @param tos    目标输出流
         * @param source 源文件
         * @throws IOException 读取文件失败
         */
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

        /**
         * 将输入流读取为字节数组。
         *
         * @param inputStream 输入流
         * @return 读取结果字节数组
         */
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

    /**
     * 单条条目数据，支持文件 / 字节数组两种数据源。
     *
     * <p>流式数据通过 {@link TarWriteBuilder#addStream(String, InputStream)} 构造时立即
     * 读取为字节数组，避免在 {@code finish()} 阶段持有已关闭的 InputStream。</p>
     *
     * @author CH
     * @since 1.0.0
     */
    @Getter
    private static final class TarEntryData {

        /**
         * 条目名称（归档包内的路径）
         */
        private final String entryName;

        /**
         * 源文件（可为 null）
         */
        private final File source;

        /**
         * 字节数组内容（流数据或直接传入，可为 null）
         */
        private final byte[] bytes;

        /**
         * 文件源构造。
         *
         * @param entryName 条目名称
         * @param source    源文件
         */
        TarEntryData(String entryName, File source) {
            this.entryName = entryName;
            this.source = source;
            this.bytes = null;
        }

        /**
         * 字节数组源构造。
         *
         * @param entryName 条目名称
         * @param bytes     字节数组
         */
        TarEntryData(String entryName, byte[] bytes) {
            this.entryName = entryName;
            this.source = null;
            this.bytes = bytes;
        }

        /**
         * 构建 TarEntry，根据数据源的不同走不同分支。
         *
         * <p>文件源通过 {@link TarEntry#TarEntry(File, String)} 自动提取头信息；
         * 字节数组源手动构造 TarHeader 并设置大小与时间戳。</p>
         *
         * @return TarEntry 实例
         */
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
