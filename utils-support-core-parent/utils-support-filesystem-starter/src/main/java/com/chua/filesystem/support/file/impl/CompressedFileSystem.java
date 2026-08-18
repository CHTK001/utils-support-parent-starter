package com.chua.filesystem.support.file.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 压缩包文件系统
 *
 * <p>支持 ZIP 格式的压缩包读写操作，提供链式添加和指定提取功能。
 *
 * <h3>链式添加</h3>
 * <pre>{@code
 *   CompressedFileSystem zip = CompressedFileSystem.create(Path.of("archive.zip"));
 *   zip.addFile(source1, "dir/file1.txt")
 *      .addFile(source2, "dir/file2.json")
 *      .addBytes("config.yml", configBytes)
 *      .addString("readme.txt", "Hello World")
 *      .close();
 * }</pre>
 *
 * <h3>指定提取</h3>
 * <pre>{@code
 *   CompressedFileSystem zip = CompressedFileSystem.open(Path.of("archive.zip"));
 *   // 提取所有文件
 *   zip.extractAll(Path.of("/tmp/extracted"));
 *   // 提取指定文件
 *   zip.extract("dir/file1.txt", Path.of("/tmp/file1.txt"));
 *   // 列出所有文件
 *   List<String> files = zip.list();
 *   zip.close();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CompressedFileSystem implements AutoCloseable {

    /** ZIP路径 */
    private final Path zipPath;
    /** Readonly */
    private final boolean readOnly;

    private CompressedFileSystem(Path zipPath, boolean readOnly) {
        this.zipPath = zipPath;
        this.readOnly = readOnly;
    }

    /**
     * 创建新的 ZIP 文件（写模式）
     *
     * @param zipPath ZIP 文件路径
     * @return CompressedFileSystem 实例
     */
    public static CompressedFileSystem create(Path zipPath) {
        return new CompressedFileSystem(zipPath, false);
    }

    /**
     * 打开已有的 ZIP 文件（读模式）
     *
     * @param zipPath ZIP 文件路径
     * @return CompressedFileSystem 实例
     */
    public static CompressedFileSystem open(Path zipPath) {
        return new CompressedFileSystem(zipPath, true);
    }

    /**
     * 链式添加文件
     *
     * @param source      源文件路径
     * @param entryName   压缩包内的路径
     * @return 当前实例（支持链式调用）
     * @throws IOException IO 异常
     */
    public CompressedFileSystem addFile(Path source, String entryName) throws IOException {
        checkWritable();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            zos.putNextEntry(new ZipEntry(entryName));
            Files.copy(source, zos);
            zos.closeEntry();
        }
        return this;
    }

    /**
     * 链式添加字节数组
     *
     * @param data      字节数据
     * @param entryName 压缩包内的路径
     * @return 当前实例
     * @throws IOException IO 异常
     */
    public CompressedFileSystem addBytes(byte[] data, String entryName) throws IOException {
        checkWritable();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(data);
            zos.closeEntry();
        }
        return this;
    }

    /**
     * 链式添加字符串
     *
     * @param content   字符串内容
     * @param entryName 压缩包内的路径
     * @return 当前实例
     * @throws IOException IO 异常
     */
    public CompressedFileSystem addString(String content, String entryName) throws IOException {
        return addBytes(content.getBytes(java.nio.charset.StandardCharsets.UTF_8), entryName);
    }

    /**
     * 列出压缩包内的所有文件
     *
     * @return 文件名列表
     * @throws IOException IO 异常
     */
    public List<String> list() throws IOException {
        List<String> files = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    files.add(entry.getName());
                }
            }
        }
        return files;
    }

    /**
     * 提取所有文件到目标目录
     *
     * @param targetDir 目标目录
     * @throws IOException IO 异常
     */
    public void extractAll(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    Files.createDirectories(targetDir.resolve(entry.getName()));
                } else {
                    Path target = targetDir.resolve(entry.getName());
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 提取指定文件
     *
     * @param entryName 压缩包内的文件路径
     * @param target    提取目标路径
     * @throws IOException IO 异常
     */
    public void extract(String entryName, Path target) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
            throw new IOException("文件不存在: " + entryName);
        }
    }

    /**
     * 读取指定文件的内容为字节数组
     *
     * @param entryName 压缩包内的文件路径
     * @return 文件内容
     * @throws IOException IO 异常
     */
    public byte[] readBytes(String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return zis.readAllBytes();
                }
            }
            throw new IOException("文件不存在: " + entryName);
        }
    }

    /**
     * 获取压缩包文件路径
     */
    public Path getPath() {
        return zipPath;
    }

    private void checkWritable() {
        if (readOnly) {
            throw new IllegalStateException("只读模式，无法写入");
        }
    }

    @Override
    public void close() {
        // ZIP 操作完成后无需特殊关闭
    }
}
