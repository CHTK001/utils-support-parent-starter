package com.chua.common.support.network.download.extractor;

import java.io.File;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
* 文件解压接口定义。
* <p>
* 作为 SPI (Service Provider Interface) 扩展点，用于定义不同压缩格式的提取器。
*
* @author CH
* @version 1.0.0
* @since 2025/11/29
 */
public interface Extractor {

    /**
    * 获取当前实现支持的压缩文件扩展名列表。
    *
    * @return 支持的扩展名数组，例如 [".zip"], [".tar.gz", ".tgz"]
    */
    String[] supportedExtensions();

    /**
    * 判断给定的文件名是否由当前提取器支持。
    *
    * @param fileName 待检查的文件名
    * @return 如果文件名后缀匹配支持的扩展名则返回 true，否则返回 false
    */
    default boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lowerName = fileName.toLowerCase();
        for (String ext : supportedExtensions()) {
            if (lowerName.endsWith(ext.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
    * 执行文件解压操作。
    *
    * @param sourceFile 源压缩文件
    * @param targetDir  目标解压目录
    * @return 如果解压成功返回 true，否则返回 false
    */
    boolean extract(File sourceFile, File targetDir);

    /**
    * 从文件名中提取基础名称（去除已知的压缩扩展名）。
    *
    * @param fileName 原始文件名
    * @return 去除扩展名后的基础文件名，如果未找到匹配的扩展名则返回原文件名
    */
    default String getBaseName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lowerName = fileName.toLowerCase();
        for (String ext : supportedExtensions()) {
            if (lowerName.endsWith(ext.toLowerCase())) {
                return fileName.substring(0, fileName.length() - ext.length());
            }
        }
        return fileName;
    }
}
