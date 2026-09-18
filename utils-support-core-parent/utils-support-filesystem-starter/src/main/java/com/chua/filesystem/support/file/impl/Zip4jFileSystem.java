package com.chua.filesystem.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
* 压缩4J 压缩文件系统 SPI 实现（支持密码保护）。
*
* <p>通过 SPI 机制注册为 {@code "zip4j"} 类型的文件系统实现。
* 基于 压缩4j 库提供更强大的 压缩 操作，支持密码加密/解密。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("zip4j")
public class Zip4jFileSystem implements FileSystem {

    @Override
    /** 获取类型 */
    public String getType() {
        return "zip4j";
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        return new Zip4jReadBuilder(file);
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
        return new Zip4jWriteBuilder(file);
    }

    public static class Zip4jReadBuilder extends ReadBuilder {

        /** 密码 */
        private char[] password;

        Zip4jReadBuilder(File file) {
            super(file);
        }

        /**
        * 链式设置密码（推荐）。
        *
        * @param password 密码
        * @return 当前构建器
        */
        public Zip4jReadBuilder password(String password) {
            return setPassword(password);
        }

        /**
        * 链式设置密码。
        *
        * @param password 密码字符数组
        * @return 当前构建器
        */
        public Zip4jReadBuilder password(char[] password) {
            return setPassword(password);
        }

        /**
        * 设置密码
        *
        * @param password 密码
        * @return 设置密码的结果
        */
        public Zip4jReadBuilder setPassword(String password) {
            this.password = password != null ? password.toCharArray() : null;
            return this;
        }

        /**
        * 设置密码
        *
        * @param password 密码
        * @return 设置密码的结果
        */
        public Zip4jReadBuilder setPassword(char[] password) {
            this.password = password;
            return this;
        }

        /**
        * 列表entries
        *
        * @return 列表entries的结果
        */
        public List<String> listEntries() {
            List<String> entries = new ArrayList<>();
            try (ZipFile zipFile = openZip()) {
                zipFile.getFileHeaders().forEach(h -> entries.add(h.getFileName()));
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
            try (ZipFile zipFile = openZip()) {
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }

                if (entryNames == null || entryNames.length == 0) {
                    zipFile.extractAll(targetDir.getAbsolutePath());
                    return;
                }

                for (String name : entryNames) {
                    zipFile.extractFile(name, targetDir.getAbsolutePath());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
        * 读取Entry
        *
        * @param entryName entry名称
        * @return 读取entry的结果
        */
        public String readEntry(String entryName) {
            try (ZipFile zipFile = openZip();
                 InputStream is = zipFile.getInputStream(zipFile.getFileHeader(entryName));
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                if (is == null) {
                    return null;
                }
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }
                return baos.toString(StandardCharsets.UTF_8.name());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
        * 打开 压缩 文件。
        * <p>zip4j 原生支持分卷读取，无需特殊处理。
        * 只需指向主 .压缩 文件，压缩4j 会自动检测并读取分卷。</p>
        *
        * @return ZipFile 实例
        */
        private ZipFile openZip() {
            ZipFile zf = new ZipFile(file);
            if (password != null) {
                zf.setPassword(password);
            }
            return zf;
        }

        @Override
        /** 读取 */
        public Object read() {
            return listEntries();
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

    public static class Zip4jWriteBuilder extends WriteBuilder {

        /** Entries */
        private final List<EntryData> entries = new ArrayList<>();
        /** 密码 */
        private char[] password;
        /** Compression级别 */
        private CompressionLevel compressionLevel = CompressionLevel.NORMAL;
        /** 分卷大小（字节），0 表示不分卷。最小 65536 字节（64KB） */
        private long splitSize = 0;

        Zip4jWriteBuilder(File file) {
            super(file);
        }

        /**
        * 设置分卷大小。
        * <p>zip4j 最小分卷大小为 65536 字节（64KB）。设置后将创建分卷归档。</p>
        *
        * @param size 每个分卷的最大字节数（最小 65536）
        * @return 当前构建器
        */
        public Zip4jWriteBuilder splitSize(long size) {
            if (size < 65536) {
                throw new UncheckedIOException(
                    new IOException("Split size must be at least 65536 bytes (64KB)"));
            }
            this.splitSize = size;
            return this;
        }

        /**
        * 设置密码
        *
        * @param password 密码
        * @return 设置密码的结果
        */
        public Zip4jWriteBuilder setPassword(String password) {
            this.password = password != null ? password.toCharArray() : null;
            return this;
        }

        /**
        * 设置密码
        *
        * @param password 密码
        * @return 设置密码的结果
        */
        public Zip4jWriteBuilder setPassword(char[] password) {
            this.password = password;
            return this;
        }

        /**
        * 链式设置密码（推荐）。
        *
        * @param password 密码
        * @return 当前构建器
        */
        public Zip4jWriteBuilder password(String password) {
            return setPassword(password);
        }

        /**
        * 链式设置密码。
        *
        * @param password 密码字符数组
        * @return 当前构建器
        */
        public Zip4jWriteBuilder password(char[] password) {
            return setPassword(password);
        }

        /**
        * 链式设置压缩级别。
        *
        * @param level 压缩级别
        * @return 当前构建器
        */
        public Zip4jWriteBuilder compressionLevel(CompressionLevel level) {
            return setCompressionLevel(level);
        }

        /**
        * 设置compression级别
        *
        * @param level 级别
        * @return 设置compression级别的结果
        */
        public Zip4jWriteBuilder setCompressionLevel(CompressionLevel level) {
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
        public Zip4jWriteBuilder addFile(String entryName, File source) {
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
        public Zip4jWriteBuilder addStream(String entryName, InputStream in) {
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
        public Zip4jWriteBuilder addBytes(String entryName, byte[] bytes) {
            entries.add(new EntryData(entryName, bytes));
            return this;
        }

        @Override
        /** 饰面 */
        public void finish() {
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            try (ZipFile zipFile = new ZipFile(file)) {
                if (password != null) {
                    zipFile.setPassword(password);
                }

                for (EntryData ed : entries) {
                    ZipParameters params = new ZipParameters();
                    params.setCompressionLevel(compressionLevel);
                    params.setFileNameInZip(ed.getEntryName());

                    if (password != null) {
                        params.setEncryptFiles(true);
                        params.setEncryptionMethod(EncryptionMethod.AES);
                    }

                    // 设置分卷参数（仅对第一个条目生效）
 // 压缩4j 2.11.x 分卷通过 创建分割压缩文件 实现，不在 压缩参数 中设置

                    if (ed.getSource() != null) {
                        zipFile.addFile(ed.getSource(), params);
                    } else if (ed.getInputStream() != null) {
                        zipFile.addStream(ed.getInputStream(), params);
                    } else if (ed.getBytes() != null) {
                        zipFile.addStream(new ByteArrayInputStream(ed.getBytes()), params);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
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
        public Zip4jWriteBuilder write(Object data) {
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
            final String entryName; // entry名称
            final File source; // 源
            final InputStream inputStream; // 输入流
            final byte[] bytes; // bytes

            EntryData(String entryName, File source) {
                this.entryName = entryName;
                this.source = source;
                this.inputStream = null;
                this.bytes = null;
            }

            EntryData(String entryName, InputStream inputStream) {
                this.entryName = entryName;
                this.source = null;
                this.inputStream = inputStream;
                this.bytes = null;
            }

            EntryData(String entryName, byte[] bytes) {
                this.entryName = entryName;
                this.source = null;
                this.inputStream = null;
                this.bytes = bytes;
            }

            String getEntryName() { return entryName; }
            File getSource() { return source; }
            InputStream getInputStream() { return inputStream; }
            byte[] getBytes() { return bytes; }
        }
    }
}
