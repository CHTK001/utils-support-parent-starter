package com.chua.common.support.file.converter;

import java.util.List;

/**
* 文件格式转换器 SPI 接口。
*
* <p>每个实现可支持多种源格式到目标格式的转换组合，通过 {@link #isSupported(String, String)} 判断是否支持特定的转换路径。
* SPI 名称应体现实现的技术栈（如 {@code "aspose-word"}、{@code "libreoffice"}）。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* @Spi("aspose-word")
* public class AsposeWordConverter implements FileConvertSystem {
*     public boolean isSupported(String src, String tgt) {
*         return List.of("doc","docx").contains(src) && "pdf".equals(tgt);
*     }
*     public void convert(FileSource source, FileSource target, ConvertSetting setting) {
*         // 转换逻辑
*     }
* }
* }</pre>
*
* @author CH
* @since 1.0.0
* @see ConvertSupport 统一转换入口
* @see FileSource 输入输出源
 */
public interface FileConvertSystem {

    /**
    * 判断是否支持从指定源格式转换到指定目标格式。
    *
    * @param sourceType 源文件格式（如 docx, xls, pdf）
    * @param targetType 目标文件格式（如 pdf, html, csv）
    * @return 如果支持该转换路径则返回 true
     */
    boolean isSupported(String sourceType, String targetType);

    /**
    * 执行文件格式转换。
    *
    * @param source  输入源，包含待转换文件路径或输入流
    * @param target  输出目标，包含转换后文件路径或输出流
    * @param setting 转换参数设置
     */
    void convert(FileSource source, FileSource target, ConvertSetting setting);

    /**
    * 源格式到目标格式的转换类型对。
    *
    * @param source 源文件格式
    * @param target 目标文件格式
     */
    record ConvertPair(String source, String target) {
    }
}
