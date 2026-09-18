package com.chua.spider.support;

import com.chua.spider.support.model.SpiderResponse;

import java.util.List;

/**
* 爬虫链接提取器 SPI 接口。
*
* <p>负责从 {@link SpiderResponse} 的原始内容中提取链接 URL，
* 供调度器 {@link SpiderScheduler} 将这些链接加入待爬取队列。
* 不同实现支持不同的提取策略：
* <ul>
*   <li>HTML 链接提取器 - 从 &lt;a href&gt; 标签中提取</li>
*   <li>Sitemap 提取器 - 从 sitemap.xml 中提取链接</li>
*   <li>RSS 提取器 - 从 RSS Feed 中提取文章链接</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public interface SpiderLinkExtractor {

    /**
    * 从响应中提取链接。
    *
    * <p>解析响应内容，找出所有应该继续爬取的链接 URL。
    * 返回的链接应经过相对路径转绝对路径等标准化处理。
    *
    * @param response 爬取响应，包含原始内容和内容类型
    * @return 提取出的链接 URL 列表，按发现顺序排列
    */
    List<String> extract(SpiderResponse response);

    /**
    * 获取当前提取器支持的内容类型。
    *
    * <p>返回该提取器能够处理的 MIME 类型列表。
    * 空数组表示支持所有类型。
    *
    * @return 支持的内容类型数组
    */
    default String[] supportedContentTypes() {
        return new String[0];
    }
}
