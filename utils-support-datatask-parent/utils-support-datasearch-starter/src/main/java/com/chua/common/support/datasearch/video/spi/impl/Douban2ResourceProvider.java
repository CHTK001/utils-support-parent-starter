package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 豆瓣 (douban2) 影视搜索提供器。
 *
 * <p>抓取豆瓣影视搜索页（{@code /search?cat=1002&q=}）的
 * {@code div.result} 结果条目，提取标题、详情链接、封面、简介与评分。</p>
 *
 * <p>与 {@link DoubanResourceProvider} 的区别：本实现只解析搜索列表页，
 * 不逐个请求详情页，因此更快，适合作为聚合检索的第二入口。</p>
 *
 * <p>注意：豆瓣综合搜索（{@code /search?query=}）是 JS 渲染页，直接抓取只能拿到
 * 导航链接（“登录/注册”等）。因此这里必须带 {@code cat=1002} 走影视分类，
 * 并只接受指向豆瓣详情页的链接，避免把页面导航当成结果。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@Spi("douban2")
public class Douban2ResourceProvider extends AbstractResourceProvider {

    /**
     * 搜索页地址前缀（影视分类 cat=1002，拼接 UTF-8 编码后的关键词）
     */
    private static final String SEARCH_URL = "https://www.douban.com/search?cat=1002&q=";

    /**
     * 模拟浏览器的 User-Agent
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    /**
     * 请求超时时间（毫秒）
     */
    private static final int TIMEOUT = 15000;

    /**
     * 结果条数上限
     */
    private static final int MAX_RESULT_COUNT = 10;

    /**
     * 豆瓣详情页链接特征（用于过滤导航/广告链接）
     */
    private static final String DOUBAN_LINK_KEY = "douban.com/";

    /**
     * 创建 douban2资源提供器 实例（无参构造，使用默认数据源）。
     */
    public Douban2ResourceProvider() {
        super();
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }

        try {
            String url = SEARCH_URL + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            Document document = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT)
                    .get();

            List<VideoInfoResult> results = Wraps.list(MAX_RESULT_COUNT);
            for (Element item : document.select("div.result")) {
                VideoInfoResult info = parseItem(item);
                if (info == null) {
                    continue;
                }
                results.add(info);
                if (results.size() >= MAX_RESULT_COUNT) {
                    break;
                }
            }

            if (results.isEmpty()) {
                return ReturnPageResult.error("豆瓣资源未找到相关视频");
            }

            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(videoSearch.getPage())
                    .pageSize(results.size())
                    .total(results.size())
                    .totalPages(1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("douban2 搜索失败: " + e.getMessage());
        }
    }

    /**
     * 解析单个搜索结果条目。
     *
     * @param item 搜索结果条目
     * @return 视频信息，非有效条目返回 空
     */
    private VideoInfoResult parseItem(Element item) {
        Element titleElement = item.selectFirst("div.title h3 a");
        if (titleElement == null) {
            return null;
        }

        String title = titleElement.text().trim();
        String detailUrl = titleElement.attr("href").trim();
        // 只接受豆瓣详情链接，过滤“登录/注册”等导航条目
        if (!StringUtils.hasText(title) || !detailUrl.contains(DOUBAN_LINK_KEY)) {
            return null;
        }

        VideoInfoResult info = new VideoInfoResult();
        info.setVideoName(title);
        info.setVideoAliasName(title);
        info.setVideoPlatform("douban2");
        info.setVideoUrl(detailUrl);
        info.setVideoCover(attr(item.selectFirst("div.pic img"), "src"));

        String description = text(item.selectFirst("div.content p"));
        if (StringUtils.hasText(description)) {
            info.setVideoDescription(description);
        }

        String rating = text(item.selectFirst("div.rating_nums"));
        if (StringUtils.hasText(rating)) {
            info.setVideoScore(Converter.toBigDecimal(rating));
        }
        return info;
    }

    /**
     * 读取元素文本。
     *
     * @param element 元素
     * @return 文本，元素为空返回 空字符串
     */
    private static String text(Element element) {
        return element == null ? "" : element.text().trim();
    }

    /**
     * 读取元素属性。
     *
     * @param element 元素
     * @param name    属性名
     * @return 属性值，元素为空返回 空字符串
     */
    private static String attr(Element element, String name) {
        return element == null ? "" : element.attr(name).trim();
    }
}
