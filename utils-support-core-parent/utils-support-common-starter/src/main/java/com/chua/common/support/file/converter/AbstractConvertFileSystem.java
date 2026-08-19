package com.chua.common.support.file.converter;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件转换器抽象基类 — 简化 ConvertFileSystem 实现。
 * <p>子类只需实现 {@link #doConvert(InputStream, OutputStream, File, File)} 和 {@link #type()}。</p>
 *
 * @since 2026-07-16
 */
public abstract class AbstractConvertFileSystem implements ConvertFileSystem {

    @Override
    /** 转换 */
    public void convert(String sourcePath, String targetPath) throws Exception {
        convert(new File(sourcePath), new File(targetPath));
    }

    @Override
    /** 转换 */
    public void convert(File sourceFile, File targetFile) throws Exception {
        if (!sourceFile.exists()) {
            throw new FileNotFoundException("源文件不存在: " + sourceFile);
        }
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        try (InputStream is = new FileInputStream(sourceFile);
             OutputStream os = new FileOutputStream(targetFile)) {
            doConvert(is, os, sourceFile, targetFile);
        }
    }

    @Override
    /** 转换 */
    public void convert(URL sourceUrl, String targetPath) throws Exception {
        convert(sourceUrl, new File(targetPath));
    }

    @Override
    /** 转换 */
    public void convert(URL sourceUrl, File targetFile) throws Exception {
        if (sourceUrl == null) {
            throw new IllegalArgumentException("源 URL 不能为空");
        }
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        try (InputStream is = sourceUrl.openStream();
             OutputStream os = new FileOutputStream(targetFile)) {
            doConvert(is, os, null, targetFile);
        }
    }

    /**
     * 子类实现此方法完成实际转换。
     *
     * @param inputStream  源文件输入流
     * @param outputStream 目标文件输出流
     * @param sourceFile   源文件（可用于获取文件名、后缀等，URL 转换时可能为 null）
     * @param targetFile   目标文件
     * @throws Exception 转换失败
     */
    protected abstract void doConvert(InputStream inputStream, OutputStream outputStream,
                                      File sourceFile, File targetFile) throws Exception;

    /** 创建 AbstractConvertFileSystem 实例 */
    protected AbstractConvertFileSystem() {
    }

    /**
     * 创建 AbstractConvertFileSystem 实例
     * @param file file
     */
    protected AbstractConvertFileSystem(File file) {
    }

    /**
     * 创建 AbstractConvertFileSystem 实例
     * @param filePath filePath
     */
    protected AbstractConvertFileSystem(String filePath) {
    }
}
