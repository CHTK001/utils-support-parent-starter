package com.chua.filestorage.support.preview;

import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;

/**
 * 文件预览 SPI 接口。
 *
 * <p>实现类通过 {@code META-INF/extensions/} 注册，由
 * {@link com.chua.filestorage.support.filter.FileStorageViewServerFilter} 自动发现。
 * 服务启动后优先尝试 SPI 预览，未命中则回退到插件头或 PDF 转换。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi
public interface FileStoragePreviewProvider {

    /**
     * 判断是否支持处理给定扩展名/ MIME 的文件。
     *
     * @param extension 文件扩展名（小写，不包含点，如 "docx"、"pdf"、"md"）
     * @param mimeType  MIME 类型（如 "application/pdf"）
     * @return true 表示可以处理
     */
    boolean supports(String extension, String mimeType);

    /**
     * 将文件内容转换为 HTML 预览结果。
     *
     * @param content   原始文件字节
     * @param extension 文件扩展名
     * @param mimeType  MIME 类型
     * @return 预览结果
     * @throws IOException 转换失败时抛出
     */
    PreviewResult preview(byte[] content, String extension, String mimeType) throws IOException;
}
