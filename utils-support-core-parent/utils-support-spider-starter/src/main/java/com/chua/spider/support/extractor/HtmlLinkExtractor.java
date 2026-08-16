package com.chua.spider.support.extractor;

import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.spider.support.SpiderLinkExtractor;
import com.chua.spider.support.model.SpiderResponse;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * HTML 链接提取器。
 *
 * <p>使用 JSoup 解析 HTML 内容，从 &lt;a href&gt; 标签中提取所有链接。
 * 自动处理相对路径转绝对路径，过滤无效链接（javascript:、mailto: 等）。
 *
 * <p>SPI 名称：{@code extractor:html}
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("html")
@ConditionalOnClass("org.jsoup.Jsoup")
public class HtmlLinkExtractor implements SpiderLinkExtractor {

    /**
     * 需要排除的链接协议前缀
     */
    private static final String[] EXCLUDED_PROTOCOLS = {
            "javascript:", "mailto:", "tel:", "sms:", "file:", "data:", "blob:"
    };

    @Override
    public List<String> extract(SpiderResponse response) {
        List<String> links = new ArrayList<>();
        String content = response.getContent();
        if (StringUtils.isEmpty(content)) {
            return links;
        }

        try {
            // 确定基准 URL，用于相对路径转绝对路径
            String baseUrl = response.getRequest() != null ?
                    response.getRequest().getUrl() : "";
            Document doc = Jsoup.parse(content, baseUrl);

            // 提取所有 <a href> 链接
            for (Element a : doc.select("a[href]")) {
                // a.attr("abs:href"); // JSoup 自动处理相对路径
                String href = a.attr("abs:href");
                if (isValidLink(href)) {
                    links.add(normalizeUrl(href));
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("[spider-extractor] 从 {} 提取了 {} 个链接", baseUrl, links.size());
            }

        } catch (Exception e) {
            log.warn("[spider-extractor] 链接提取失败: {}", response.getRequest() != null ?
                    response.getRequest().getUrl() : "unknown", e);
        }

        return links;
    }

    @Override
    public String[] supportedContentTypes() {
        return new String[]{"text/html", "application/xhtml+xml"};
    }

    /**
     * 判断链接是否有效（排除非 HTTP 协议链接、空链接、锚点链接）。
     *
     * @param href 链接 URL
     * @return true 表示有效链接
     */
    private boolean isValidLink(String href) {
        if (StringUtils.isEmpty(href)) {
            return false;
        }

        // 排除 javascript:、mailto: 等协议
        for (String protocol : EXCLUDED_PROTOCOLS) {
            if (href.startsWith(protocol)) {
                return false;
            }
        }

        // 排除纯锚点
        if (href.startsWith("#")) {
            return false;
        }

        // 只保留 http/https 协议
        return href.startsWith("http://") || href.startsWith("https://");
    }

    /**
     * 标准化 URL。
     *
     * <p>去除尾部斜杠、清理多余的空格、统一小写协议等。
     *
     * @param url 原始 URL
     * @return 标准化后的 URL
     */
    private String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        try {
            url = url.trim();
            URI uri = URI.create(url);
            String path = uri.getPath();
            // 去除尾部斜杠（保留根路径 "/"）
            if (path != null && path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            // 去除 fragment
            return new URI(uri.getScheme(), uri.getAuthority(), path,
                    uri.getQuery(), null).toString();
        } catch (Exception e) {
            return url;
        }
    }
}
