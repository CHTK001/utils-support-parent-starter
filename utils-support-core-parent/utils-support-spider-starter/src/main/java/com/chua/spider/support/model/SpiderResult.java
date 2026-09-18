package com.chua.spider.support.model;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;


import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 爬虫解析结果。
*
* <p>Parser 将原始响应解析后产生的结构化数据，包含页面标题、文本内容、
* 链接列表、结构化字段等。aiparser 可能在此基础上添加 AI 总结、分类等信息。
* Pipeline 组件负责消费此结果。
*
* @author CH
* @since 4.0.0.42
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder(toBuilder = true)
public class SpiderResult {

    /**
    * 页面 URL。
    *
    * <p>当前结果对应的页面完整地址。
    */
    private String url;

    /**
    * 爬取深度。
    *
    * <p>当前页面相对于种子 URL 的链接追踪深度。种子 URL 深度为 0，
    * 从种子页面提取的链接深度为 1，以此类推。
    * Pipeline 回调中可通过此字段区分列表页和详情页。
    */
    private int depth;

    /**
    * 页面标题。
    *
    * <p>从 HTML 的 &lt;title&gt; 标签中提取的页面标题。
    */
    private String title;

    /**
    * 纯文本内容。
    *
    * <p>去除 HTML 标签后的页面纯文本内容，供全文搜索或 AI 分析使用。
    */
    private String text;

    /**
    * 原始 HTML 内容。
    *
    * <p>页面完整的 HTML 源码，供需要原始格式的场景使用。
    */
    private String html;

    /**
    * 结构化字段。
    *
    * <p>Parser 或 AiParser 从页面中提取的结构化数据，如作者、发布时间、分类等。
    */
    @Builder.Default
    private Map<String, Object> structured = new LinkedHashMap<>(); // Structured Streaming Streaming Streaming

    /**
    * 提取的链接列表。
    *
    * <p>LinkExtractor 从页面中提取的所有链接 URL。
    */
    private List<String> links;

    /**
    * AI 总结。
    *
    * <p>AiParser 通过 ChatClient 对页面内容进行 AI 总结后的摘要文本。
    */
    private String aiSummary;

    /**
    * AI 分类。
    *
    * <p>AiParser 通过 ChatClient 对页面进行分类的结果标签。
    */
    private String aiCategory;

    /**
    * 元数据。
    *
    * <p>爬取过程中产生的附加元数据，如抓取时间、解析耗时等。
    */
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>(); // metadata

    /**
    * 内容类型。
    *
    * <p>页面内容的 MIME 类型，同响应的 contentType。
    */
    private String contentType;

    /**
    * 解析时间戳。
    *
    * <p>结果被解析完成的时间戳（毫秒）。
    */
    private long extractedAt;
}
