package com.chua.spider.support;

import com.chua.spider.support.model.SpiderResponse;
import com.chua.spider.support.model.SpiderResult;

/**
 * 爬虫解析器 SPI 接口。
 *
 * <p>负责将 {@link SpiderResponse} 中的原始内容解析为结构化的
 * {@link SpiderResult}。不同实现针对不同内容类型：
 * <ul>
 *   <li>HTML 解析器 - 提取标题、正文、元数据等</li>
 *   <li>JSON 解析器 - 解析 JSON 格式的 API 响应</li>
 *   <li>XML 解析器 - 解析 XML/RSS Feed</li>
 *   <li>二进制解析器 - 提取图片、PDF 等文件信息</li>
 * </ul>
 *
 * <p>Parser 的输出将传递给 LinkExtractor 和 Pipeline 进行后续处理。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SpiderParser {

    /**
     * 解析响应内容。
     *
     * <p>根据响应的 contentType 选择合适的解析策略，将原始内容
      * 解析为包含标题、文本、结构化字段等信息的 结果 对象。
      * 返回 空 表示该解析器无法处理此内容。
     *
     * @param response 爬取响应，包含原始内容和内容类型
     * @return 解析结果，包含标题、文本、结构化数据等；无法解析时返回 空
     */
    SpiderResult parse(SpiderResponse response);

    /**
     * 获取当前 Parser 支持的内容类型。
     *
     * <p>返回该 Parser 能够处理的 MIME 类型列表，
     * 如 {@code "text/html"}、{@code "application/json"} 等。
     *
     * @return 支持的内容类型数组
     */
    String[] supportedContentTypes();
}
