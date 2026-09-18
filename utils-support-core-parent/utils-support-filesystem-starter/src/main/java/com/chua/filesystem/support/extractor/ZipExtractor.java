package com.chua.filesystem.support.extractor;

import com.chua.common.support.network.download.extractor.Extractor;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
* 压缩 压缩文件提取器（支持密码）
*
* <p>使用 zip4j 库实现，支持带密码保护的 ZIP 文件解压。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("zip")
public class ZipExtractor implements Extractor {

    /**
    * 缓冲区大小（8KB）
    */
    private static final int BUFFER_SIZE = 8192;

    @Override
    /** 支持延伸 */
    public String[] supportedExtensions() {
        return new String[]{".zip"};
    }

    @Override
    /** Extract */
    public boolean extract(File sourceFile, File targetDir) {
        return extract(sourceFile, targetDir, null);
    }

    /**
    * 将 压缩 文件提取到目标目录
    *
    * @param sourceFile 源 压缩 文件
    * @param targetDir  目标目录
    * @param password   密码（可选，空 表示无密码）
    * @return 提取是否成功
    */
    public boolean extract(File sourceFile, File targetDir, String password) {
        if (sourceFile == null || !sourceFile.exists()) {
            log.error("[filesystem-extractor] 源文件不存在: {}", sourceFile);
            return false;
        }

        Path targetPath = Paths.get(targetDir.getAbsolutePath());
        try {
            Files.createDirectories(targetPath);
        } catch (IOException e) {
            log.error("[filesystem-extractor] 创建目标目录失败: {}", targetDir, e);
            return false;
        }

        try (net.lingala.zip4j.ZipFile zipFile = new net.lingala.zip4j.ZipFile(sourceFile)) {
            if (zipFile.isEncrypted() && password == null) {
                log.error("[filesystem-extractor] ZIP 文件已加密但未提供密码: {}", sourceFile.getName());
                return false;
            }

            if (password != null) {
                zipFile.setPassword(password.toCharArray());
            }

            zipFile.extractAll(targetDir.getAbsolutePath());

            List<FileHeader> fileHeaders = zipFile.getFileHeaders();
            for (FileHeader header : fileHeaders) {
                if (!header.isDirectory()) {
                    Path entryPath = targetPath.resolve(header.getFileName());
                    if (!entryPath.startsWith(targetPath)) {
                        log.warn("[filesystem-extractor] 条目路径超出目标目录: {}", header.getFileName());
                        continue;
                    }
                    log.debug("[filesystem-extractor] Extracted: {}", header.getFileName());
                }
            }

            log.info("[filesystem-extractor] ZIP 解压完成: {} -> {}", sourceFile.getName(), targetDir.getAbsolutePath());
            return true;
        } catch (IOException e) {
            log.error("[filesystem-extractor] ZIP 解压失败: {}", sourceFile.getName(), e);
            return false;
        }
    }

}
