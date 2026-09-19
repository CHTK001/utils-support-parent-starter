package com.chua.common.support.file.converter;

import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.net.URL;

/**
 * 文件转换系统 SPI 接口 — 将一种格式转换为另一种格式。
 * <p>实现类通过 {@code @Spi("source2target")} 注册，如 {@code @Spi("png2jpg")}。</p>
 *
 * @author CH
 * @since 2026-07-16
 */
public interface ConvertFileSystem {

    /**
     * 源格式 → 目标格式标识，用于 {@code ServiceProvider} 查找
     *
     * @return 如 "png2jpg"、"docx2pdf"
     */
    String type();

    /**
     * 执行文件转换
     *
     * @param sourcePath  源文件路径
     * @param targetPath  目标文件路径
     * @throws Exception 转换失败
     */
    void convert(String sourcePath, String targetPath) throws Exception;

    /**
     * 执行文件转换
     *
     * @param sourceFile  源文件
     * @param targetFile  目标文件
     * @throws Exception 转换失败
     */
    void convert(File sourceFile, File targetFile) throws Exception;

    /**
     * 执行文件转换（从 URL 读取源文件）
     *
     * @param sourceUrl  源文件 URL（支持 file://、http://、https:// 等协议）
     * @param targetPath 目标文件路径
     * @throws Exception 转换失败
     * @since 1.0.0
     */
    void convert(URL sourceUrl, String targetPath) throws Exception;

    /**
     * 执行文件转换（从 URL 读取源文件）
     *
     * @param sourceUrl  源文件 URL
     * @param targetFile 目标文件
     * @throws Exception 转换失败
     * @since 1.0.0
     */
    void convert(URL sourceUrl, File targetFile) throws Exception;

    /**
     * 支持的转换类型
     * @return 转换Support 对象
     */
    ConvertSupport[] supportedTypes();

    /**
     * 支持的转换类型描述
     *
     * @param sourceFormat 源格式
     * @param targetFormat 目标格式
     * @return 结果值
     */
    record ConvertSupport(String sourceFormat, String targetFormat) {
        @Override
        /** ToString */
        public String toString() {
            return sourceFormat + " → " + targetFormat;
        }
    }
}
