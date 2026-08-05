package com.chua.common.support.network.server.request;

import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Multipart/form-data 解析器 SPI。
 *
 * <p>将 multipart 请求体解析为 {@link FormFile} 列表，供各 HttpServer 实现按需调用。
 * 默认无内置实现，可通过扩展模块引入（如 {@code Apache Commons FileUpload}）。
 *
 * @author CH
 * @since 2026/07/17
 */
@Spi
public interface MultipartParser {

    /**
     * 解析 multipart/form-data 请求体中的文件上传。
     */
    List<FormFile> parse(byte[] body, String contentType);

    /**
     * 判断此解析器是否支持指定的 Content-Type。
     */
    boolean support(String contentType);

    /**
     * 解析 multipart/form-data 请求体中的表单字段。
     *
     * @param body        完整请求体字节数组
     * @param contentType Content-Type 头（含 boundary）
     * @return 表单字段名到值的映射，无字段或解析失败时返回空 Map
     */
    default Map<String, String> parseFormFields(byte[] body, String contentType) {
        return java.util.Collections.emptyMap();
    }
}
