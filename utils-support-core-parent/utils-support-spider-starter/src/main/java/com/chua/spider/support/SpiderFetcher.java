package com.chua.spider.support;

import com.chua.spider.support.model.SpiderRequest;
import com.chua.spider.support.model.SpiderResponse;

/**
* 爬虫抓取器 SPI 接口。
*
* <p>负责根据 {@link SpiderRequest} 发起网络请求，获取原始响应内容。
* 不同实现支持不同的协议或渲染方式：
* <ul>
*   <li>HTTP 客户端 - OkHttp、HttpClient 等常规 HTTP 请求</li>
*   <li>浏览器渲染 - Selenium、Playwright 等支持 JS 渲染的抓取</li>
*   <li>文件协议 - 抓取本地文件系统上的内容</li>
* </ul>
*
* <p>通过 {@code @Spi("http")} 等方式注册，
* {@link Spider} 主入口通过 SPI 名称选择具体的 Fetcher 实现。
* 内置默认实现为 {@code http}（基于 JDK HTTP客户端，无外部依赖）。
*
* @author CH
* @since 4.0.0.42
 */
public interface SpiderFetcher {

    /**
    * 执行一次爬取请求。
    *
    * <p>根据请求中的 URL、方法、头信息等参数发起网络请求，
    * 返回包含状态码、响应头和原始内容的响应对象。
    * 如果请求失败，应在响应中设置 错误 信息而非抛出异常。
    *
    * @param request 爬取请求，包含 URL、头信息、请求方法等
    * @return 爬取响应，包含状态码、内容和可能的错误信息
     */
    SpiderResponse fetch(SpiderRequest request);

    /**
    * 获取当前 Fetcher 支持的内容类型。
    *
    * <p>返回该 Fetcher 支持的 MIME 类型列表，如
    * {@code "text/html"}、{@code "application/json"} 等。
    * 空数组表示支持所有类型。
    *
    * @return 支持的内容类型数组
     */
    default String[] supportedContentTypes() {
        return new String[0];
    }
}
