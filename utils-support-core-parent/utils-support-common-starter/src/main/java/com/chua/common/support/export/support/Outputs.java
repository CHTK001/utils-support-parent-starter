package com.chua.common.support.export.support;

import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.ExportFormats;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 输出目标与模板来源工具。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Outputs {

    /**
     * classpath 前缀。
     */
    private static final String CLASSPATH_PREFIX = "classpath:";

    private Outputs() {
    }

    /**
     * 写出结果：优先写文件，其次写流，都没有则返回字节。
     *
     * @param data   字节
     * @param file   目标文件，可为 {@code null}
     * @param stream 目标流，可为 {@code null}
     * @return 最终字节
     * @throws IOException 写出失败
     */
    public static byte[] deliver(byte[] data, File file, OutputStream stream) throws IOException {
        if (file != null) {
            Path parent = file.toPath().toAbsolutePath().getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(file.toPath(), data);
            return data;
        }
        if (stream != null) {
            stream.write(data);
            stream.flush();
            return data;
        }
        return data;
    }

    /**
     * 解析模板来源。
     *
     * <p>支持：</p>
     * <ul>
     *     <li>{@code "classpath:templates/a.xlsx"} —— 显式类路径</li>
     *     <li>{@code "templates/a.xlsx"} —— 先试文件系统，再试类路径</li>
     *     <li>{@code "D:/tmp/a.xlsx"} —— 存在的文件路径</li>
     * </ul>
     *
     * @param source 模板来源
     * @return 模板输入流，调用方负责关闭
     * @throws IOException 读取失败
     */
    public static InputStream openTemplate(String source) throws IOException {
        if (source == null || source.isBlank()) {
            throw new ExportException("模板路径不能为空");
        }
        if (source.startsWith(CLASSPATH_PREFIX)) {
            String path = source.substring(CLASSPATH_PREFIX.length());
            InputStream in = classpath(path);
            if (in == null) {
                throw new ExportException("类路径下未找到模板: " + path);
            }
            return in;
        }
        File file = new File(source);
        if (file.isFile()) {
            return new FileInputStream(file);
        }
        InputStream in = classpath(source);
        if (in != null) {
            return in;
        }
        Path path = Path.of(source);
        if (Files.isRegularFile(path)) {
            return Files.newInputStream(path);
        }
        throw new ExportException("模板不存在: " + source);
    }

    /**
     * 打开模板文件。
     *
     * @param file 模板文件
     * @return 输入流
     * @throws FileNotFoundException 文件不存在
     */
    public static InputStream openTemplate(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new ExportException("模板文件不存在: " + file);
        }
        return new FileInputStream(file);
    }

    /**
     * 包装字节数组为输入流。
     *
     * @param data 字节数组
     * @return 输入流
     */
    public static InputStream openTemplate(byte[] data) {
        if (data == null || data.length == 0) {
            throw new ExportException("模板内容不能为空");
        }
        return new ByteArrayInputStream(data);
    }

    /**
     * 从类路径读取资源。
     *
     * @param path 资源路径
     * @return 输入流，不存在返回 {@code null}
     */
    public static InputStream classpath(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        InputStream in = loader == null ? null : loader.getResourceAsStream(normalized);
        if (in == null) {
            in = Outputs.class.getClassLoader().getResourceAsStream(normalized);
        }
        return in;
    }

    /**
     * 从输出目标推断格式。
     *
     * @param file    目标文件
     * @param stream  目标流
     * @param format  显式格式
     * @param fallback 兜底格式
     * @return 格式标识
     */
    public static String resolveFormat(File file, OutputStream stream, String format, String fallback) {
        if (format != null && !format.isBlank()) {
            return ExportFormats.normalize(format);
        }
        if (file != null) {
            String inferred = ExportFormats.of(file.getName());
            if (inferred != null) {
                return inferred;
            }
        }
        return fallback;
    }
}