package com.chua.common.support.network.download.extractor;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import org.jspecify.annotations.NullUnmarked;


/**
 * TAR 文件提取器。
 * <p>
 * 该类用于处理 .tar 格式文件的解压操作。
 * 继承自 TarGzExtractor，专注于标准的 TAR 归档格式。
 *
 * @author CH
 * @version 1.0.0
 * @since 2025/11/29
 */
@NullUnmarked
@Slf4j
@Spi({"tar"})
public class TarExtractor extends TarGzExtractor {

    /**
     * 获取当前提取器支持的扩展名列表。
     *
     * @return 包含 ".tar" 的字符串数组
     */
    @Override
    public String[] supportedExtensions() {
        return new String[]{".tar"};
    }

    /**
     * 从指定的源文件解压到目标目录。
     *
     * @param sourceFile 待解压的 TAR 源文件
     * @param targetDir  解压后的目标目录
     * @return 如果解压成功返回 true，否则返回 false
     */
    @Override
    public boolean extract(File sourceFile, File targetDir) {
        if (log.isDebugEnabled()) {
            log.debug("TAR 文件解压：{} -> {}", sourceFile.getName(), targetDir.getAbsolutePath());
        }

        try (FileInputStream fis = new FileInputStream(sourceFile);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            extractTar(bis, targetDir);
            if (log.isDebugEnabled()) {
                log.debug("TAR 文件解压完成：{}", sourceFile.getName());
            }
            return true;
        } catch (Exception e) {
            log.error("TAR 文件解压失败：{}", sourceFile.getName(), e);
            return false;
        }
    }
}
