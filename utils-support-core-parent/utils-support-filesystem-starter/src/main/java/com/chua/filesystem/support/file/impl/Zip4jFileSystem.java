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
 * ZIP4J 压缩文件系统 SPI 实现（支持密码保护）。
 *
 * <p>通过 SPI 机制注册为 {@code "zip4j"} 类型的文件系统实现。
 * 基于 zip4j 库提供更强大的 ZIP 操作，支持密码加密/解密。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zip4j")
public class Zip4jFileSystem implements FileSystem {

    @Override
    public String getType() {
        return "zip4j";
    }

    @Override
    public ReadBuilder read(File file) {
        return new Zip4jReadBuilder(file);
    }

    @Override
    public WriteBuilder write(File file) {
        return new Zip4jWriteBuilder(file);
    }

    public static class Zip4jReadBuilder extends ReadBuilder {

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

        public Zip4jReadBuilder setPassword(String password) {
            this.password = password != null ? password.toCharArray() : null;
            return this;
        }

        public Zip4jReadBuilder setPassword(char[] password) {
            this.password = password;
            return this;
        }

        public List<String> listEntries() {
            List<String> entries = new ArrayList<>();
            try (ZipFile zipFile = openZip()) {
                zipFile.getFileHeaders().forEach(h -> entries.add(h.getFileName()));
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

        private ZipFile openZip() {
            ZipFile zf = new ZipFile(file);
            if (password != null) {
                zf.setPassword(password);
            }
            return zf;
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

    public static class Zip4jWriteBuilder extends WriteBuilder {

        private final List<EntryData> entries = new ArrayList<>();
        private char[] password;
        private CompressionLevel compressionLevel = CompressionLevel.NORMAL;

        Zip4jWriteBuilder(File file) {
            super(file);
        }

        public Zip4jWriteBuilder setPassword(String password) {
            this.password = password != null ? password.toCharArray() : null;
            return this;
        }

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

        public Zip4jWriteBuilder setCompressionLevel(CompressionLevel level) {
            this.compressionLevel = level;
            return this;
        }

        public Zip4jWriteBuilder addFile(String entryName, File source) {
            entries.add(new EntryData(entryName, source));
            return this;
        }

        public Zip4jWriteBuilder addStream(String entryName, InputStream in) {
            entries.add(new EntryData(entryName, in));
            return this;
        }

        public Zip4jWriteBuilder addBytes(String entryName, byte[] bytes) {
            entries.add(new EntryData(entryName, bytes));
            return this;
        }

        @Override
        public void finish() {
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            try (ZipFile zipFile = new ZipFile(file)) {
                for (EntryData ed : entries) {
                    ZipParameters params = new ZipParameters();
                    params.setCompressionLevel(compressionLevel);
                    params.setFileNameInZip(ed.getEntryName());

                    if (password != null) {
                        params.setEncryptFiles(true);
                        params.setEncryptionMethod(EncryptionMethod.AES);
                        zipFile.setPassword(password);
                    }

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
            final String entryName;
            final File source;
            final InputStream inputStream;
            final byte[] bytes;

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
