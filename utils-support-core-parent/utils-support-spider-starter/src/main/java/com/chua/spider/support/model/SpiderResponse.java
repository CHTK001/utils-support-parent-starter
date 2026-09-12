package com.chua.spider.support.model;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;


import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 爬虫响应。
 *
 * <p>封装一次爬取请求的完整响应结果，包含状态码、响应头、原始内容、
 * 内容类型等信息。是 {@link com.chua.spider.support.SpiderFetcher} 的输出，
 * {@link com.chua.spider.support.SpiderParser} 的输入。
 *
 * @author CH
 * @since 4.0.0.42
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder(toBuilder = true)
public class SpiderResponse {

    /**
     * 对应的原始请求。
     *
     * <p>记录本次响应是由哪个请求产生的，便于追溯。
     */
    private SpiderRequest request;

    /**
     * HTTP 状态码。
     *
     * <p>如 200 表示成功，404 表示未找到，500 表示服务端错误。
     */
    private int statusCode;

    /**
     * 响应头。
     *
     * <p>服务端返回的 HTTP 响应头键值对。
     */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>(); // 头部

    /**
     * 原始内容字节数组。
     *
     * <p>服务端返回的完整响应体原始字节，未经过任何编码转换。
     * Parser 组件负责将其解码为文本或结构化数据。
     */
    private byte[] rawContent;

    /**
     * 原始内容文本。
     *
     * <p>将 rawContent 按响应头的字符集解码后的文本字符串。
      * 供 Parser 和 链接extractor 直接使用。
     */
    private String content;

    /**
     * 内容类型。
     *
     * <p>MIME 类型，如 "text/html"、"application/json"、"image/png" 等。
     * Parser 根据此字段选择合适的解析策略。
     */
    private String contentType;

    /**
     * 字符集编码。
     *
     * <p>响应内容的字符集编码，如 "UTF-8"、"GBK" 等。
      * 用于将 raw内容 正确解码为文本。
     */
    private String charset;

    /**
     * 内容长度。
     *
     * <p>响应体的字节长度，单位为字节。
     */
    private long contentLength;

    /**
     * 抓取耗时。
     *
     * <p>从发起请求到收到完整响应所花费的毫秒数。
     */
    private long fetchTimeMs;

    /**
     * 抓取错误。
     *
     * <p>如果抓取过程中发生异常，记录异常信息。正常响应时为空。
     */
    private String error;

    /**
     * 是否成功。
     *
     * @return 状态码为 2xx 且无错误时返回 true
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300 && error == null;
    }
}
