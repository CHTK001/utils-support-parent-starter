package com.chua.common.support.task.flow;

/**
 * 爬虫节点接口。
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
public interface SpiderNode extends FlowNode {

    @Override
    /** Type */
    default String type() {
        return "spider";
    }
}
