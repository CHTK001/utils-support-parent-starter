package com.chua.spider.support.parser;

import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.spider.support.SpiderParser;
import com.chua.spider.support.model.SpiderResponse;
import com.chua.spider.support.model.SpiderResult;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.LinkedHashMap;
import java.util.Map;

/**
* HTML 内容解析器。
*
* <p>使用 JSoup 库解析 HTML 格式的爬取响应，提取页面标题、纯文本内容、
* 结构化元数据（作者、发布时间、描述等）。
*
* <p>SPI 名称：{@code parser:html}
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("html")
@ConditionalOnClass("org.jsoup.Jsoup")
public class HtmlParser implements SpiderParser {

    /**
    * 支持的内容类型前缀
    */
    private static final String[] SUPPORTED_TYPES = {"text/html", "application/xhtml+xml"};

    @Override
    /** 解析 */
    public SpiderResult parse(SpiderResponse response) {
        String content = response.getContent();
        if (StringUtils.isEmpty(content)) {
            return null;
        }

        try {
            // 解析 HTML
            String baseUri = response.getRequest() != null ?
                    response.getRequest().getUrl() : "";
            Document doc = Jsoup.parse(content, baseUri);

            // 提取标题
            String title = extractTitle(doc);

            // 提取纯文本
            String text = doc.body() != null ? doc.body().text() : "";

            // 提取 meta 结构化信息
            Map<String, Object> structured = extractMeta(doc);

            // 构建结果
            return SpiderResult.builder()
                    .url(baseUri)
                    .title(title)
                    .text(text)
                    .html(content)
                    .structured(structured)
                    .contentType(response.getContentType())
                    .extractedAt(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.warn("[spider-parser] HTML 解析失败: {}", response.getRequest() != null ?
                    response.getRequest().getUrl() : "unknown", e);
            return null;
        }
    }

    @Override
    /** 支持内容类型 */
    public String[] supportedContentTypes() {
        return SUPPORTED_TYPES;
    }

    /**
    * 从 HTML 文档中提取页面标题。
    *
    * <p>优先使用 &lt;title&gt; 标签内容，其次使用 &lt;h1&gt; 标签内容。
    *
    * @param doc HTML 文档
    * @return 页面标题，找不到时返回空字符串
    */
    private String extractTitle(Document doc) {
        String title = doc.title();
        if (StringUtils.isNotEmpty(title)) {
            return title.trim();
        }
        Element h1 = doc.selectFirst("h1");
        if (h1 != null) {
            return h1.text().trim();
        }
        return "";
    }

    /**
    * 从 HTML 的 &lt;meta&gt; 标签中提取结构化元数据。
    *
    * <p>支持提取的字段：description、keywords、author、publishedDate 等。
    *
    * @param doc HTML 文档
    * @return 结构化元数据键值对
    */
    private Map<String, Object> extractMeta(Document doc) {
        Map<String, Object> meta = new LinkedHashMap<>();

 // 提取 名称 类 meta
        for (Element element : doc.select("meta[name]")) {
            String name = element.attr("name").toLowerCase();
            String content = element.attr("content");
            if (!name.isEmpty() && !content.isEmpty()) {
                meta.put(name, content);
            }
        }

 // 提取 财产 类 meta（打开 图计算）
        for (Element element : doc.select("meta[property]")) {
            String property = element.attr("property").toLowerCase();
            String content = element.attr("content");
            if (!property.isEmpty() && !content.isEmpty()) {
                meta.put(property, content);
            }
        }

        return meta;
    }
}
