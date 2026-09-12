package com.chua.spider.support.flow;

import com.chua.common.support.task.flow.FlowContext;
import com.chua.common.support.task.flow.FlowProps;
import com.chua.common.support.task.flow.SpiderNode;
import com.chua.spider.support.Spider;
import com.chua.spider.support.model.SpiderResult;
import com.chua.spider.support.model.SpiderSite;

import java.util.List;

/**
* 爬虫流程节点。
*
* <p>将爬虫能力封装为流程编排节点，作为爬虫编排的入口：
* 流程运行到该节点时启动一次爬虫抓取，结果写入流程上下文供下游节点消费
* （清洗、入库、通知等）。</p>
*
* <p>节点属性说明：</p>
* <ul>
*   <li>{@code urls} — 种子 URL 列表（必填）</li>
*   <li>{@code threads} — 并发线程数，默认 1</li>
*   <li>{@code maxDepth} — 最大爬取深度，-1 不限制</li>
*   <li>{@code maxPages} — 最大抓取页面数，0 不限制</li>
*   <li>{@code interval} — 请求间隔毫秒数，默认 1000</li>
*   <li>{@code retryTimes} — 失败重试次数，默认 3</li>
* </ul>
*
* <p>执行后写入上下文：</p>
* <ul>
*   <li>{@code spider.result} — 本次爬取的全部结果列表</li>
*   <li>当前数据 — 同步设置为爬取结果列表</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public class SpiderFlowNode implements SpiderNode {

    /**
    * 结果上下文属性键：爬取结果列表
     */
    private static final String RESULT_KEY = "spider.result";

    /**
    * 默认并发线程数
     */
    private static final int DEFAULT_THREADS = 1;

    /**
    * 默认请求间隔（毫秒）
     */
    private static final int DEFAULT_INTERVAL = 1000;

    /**
    * 默认重试次数
     */
    private static final int DEFAULT_RETRY_TIMES = 3;

    /**
    * 执行爬虫节点。
    *
    * <p>根据节点属性构建爬虫并同步执行抓取，
    * 结果写入上下文供下游节点消费。
    * 种子 URL 为空时抛出异常，由引擎标记实例失败。</p>
    *
    * @param context 当前流程上下文
     */
    @Override
    public void execute(FlowContext context) {
        FlowProps props = context.currentNodeProps();
        List<String> urls = props.getStringList("urls");
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("spider 节点缺少 urls 属性");
        }

        Spider.Builder builder = buildSpider(urls, props);
        List<SpiderResult> results = builder.build().runSync();

        context.setAttribute(RESULT_KEY, results);
        context.setData(results);
    }

    /**
    * 构建爬虫构建器。
    *
    * <p>按节点属性设置种子 URL、并发数、站点配置（深度、页数、间隔、重试）。</p>
    *
    * @param urls  种子 URL 列表
    * @param props 节点属性
    * @return 爬虫构建器
     */
    private Spider.Builder buildSpider(List<String> urls, FlowProps props) {
        Spider.Builder builder = Spider.create();
        for (String url : urls) {
            builder.addUrl(url);
        }
        builder.threads(props.getInt("threads", DEFAULT_THREADS));

        int maxDepth = props.getInt("maxDepth", -1);
        int maxPages = props.getInt("maxPages", 0);
        int interval = props.getInt("interval", DEFAULT_INTERVAL);
        int retryTimes = props.getInt("retryTimes", DEFAULT_RETRY_TIMES);

        SpiderSite site = SpiderSite.builder()
                .maxDepth(maxDepth)
                .maxPages(maxPages)
                .interval(interval)
                .retryTimes(retryTimes)
                .build();
        builder.site(site);
        return builder;
    }
}
