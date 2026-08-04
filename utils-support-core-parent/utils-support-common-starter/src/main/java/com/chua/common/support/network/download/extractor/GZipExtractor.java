package com.chua.common.support.network.download.extractor;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.NullUnmarked;

/**
 * GZip 压缩文件提取器
 * <p>
 * 支持 .gz 格式的解压。使用 Java 原生 GZIPInputStream 解压后输出到指定目录，文件名去除 .gz 后缀。
 * </p>
 *
 * @author CH
 */
@NullUnmarked
@Slf4j
@Spi("gz")
public class GZipExtractor implements Extractor {

    @Override
    public boolean extract(File sourceFile, File targetDir) {
        if (sourceFile == null || !sourceFile.exists()) {
            log.error("源文件不存在: {}", sourceFile);
            return false;
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        String baseName = sourceFile.getName().replaceAll("\\.gz$", "");
        File outFile = new File(targetDir, baseName);

        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(sourceFile));
             FileOutputStream os = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            log.info("GZip 解压完成：{} -> {}", sourceFile.getName(), outFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            log.error("GZip 解压失败：{}", sourceFile.getName(), e);
            return false;
        }
    }

    @Override
    public String[] supportedExtensions() {
        return new String[]{".gz"};
    }
}