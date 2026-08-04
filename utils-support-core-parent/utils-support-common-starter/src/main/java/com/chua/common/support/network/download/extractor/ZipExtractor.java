package com.chua.common.support.network.download.extractor;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.NullUnmarked;

/**
 * ZIP 压缩文件提取器
 *
 * <p>支持 .zip 格式的解压。使用 Java 原生 ZipInputStream 实现，包含防 Zip Slip 安全校验。</p>
 *
 * @author CH
 */
@NullUnmarked
@Slf4j
@Spi("zip")
public class ZipExtractor implements Extractor {

    /**
     * 获取支持的扩展名列表
     *
     * @return 支持的扩展名数组，仅包含 ".zip"
     */
    @Override
    public String[] supportedExtensions() {
        return new String[]{".zip"};
    }

    /**
     * 从源 ZIP 文件中提取内容到目标目录
     *
     * @param sourceFile 源 ZIP 文件对象
     * @param targetDir  目标解压目录对象
     * @return 如果解压成功返回 true，否则返回 false
     */
    @Override
    public boolean extract(File sourceFile, File targetDir) {
        if (sourceFile == null || !sourceFile.exists()) {
            log.error("源文件不存在：{}", sourceFile);
            return false;
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(sourceFile))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                    throw new IOException("ZIP entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
            log.info("ZIP 解压完成：{} -> {}", sourceFile.getName(), targetDir.getAbsolutePath());
            return true;
        } catch (IOException e) {
            log.error("ZIP 解压失败：{}", sourceFile.getName(), e);
            return false;
        }
    }
}