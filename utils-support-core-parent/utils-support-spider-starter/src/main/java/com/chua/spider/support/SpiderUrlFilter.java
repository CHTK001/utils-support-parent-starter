package com.chua.spider.support;

import com.chua.spider.support.model.SpiderRequest;

/**
 * 爬虫 URL 过滤器 SPI 接口。
 *
 * <p>在 URL 被 {@link SpiderScheduler} 入队之前进行过滤，
   * 决定哪些 URL 应该被爬取、哪些应该被忽略。多个 过滤器 可组合使用。
 * 常见过滤策略：
 * <ul>
 *   <li>域名白名单 - 只爬取指定域名内的链接</li>
 *   <li>路径模式 - 只匹配特定路径模式的 URL</li>
 *   <li>文件类型 - 排除图片、PDF 等非目标文件</li>
 *   <li>robots.txt - 遵守目标站点的爬取规则</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SpiderUrlFilter {

    /**
     * 判断 URL 是否应该被爬取。
     *
     * <p>对候选 URL 进行过滤检查，返回 true 表示允许爬取，
     * 返回 false 表示需要跳过此链接。
     *
     * @param request 待过滤的爬取请求，包含 URL 和深度信息
     * @return true 表示允许爬取，false 表示忽略此链接
     */
    boolean accept(SpiderRequest request);
}
