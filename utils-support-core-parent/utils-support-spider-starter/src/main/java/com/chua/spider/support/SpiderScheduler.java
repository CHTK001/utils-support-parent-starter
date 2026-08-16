package com.chua.spider.support;

import com.chua.spider.support.model.SpiderRequest;

/**
 * 爬虫调度器 SPI 接口。
 *
 * <p>管理待爬取的 URL 队列，决定爬取顺序和执行策略。
 * 调度器是爬虫的核心协调组件，控制着爬虫的爬取节奏和顺序。
 * <p>实现类需保证 {@link #enqueue}、{@link #dequeue} 等方法的<strong>线程安全</strong>，
 * 因为在多线程爬取模式下这些方法会被不同线程并发调用。
 *
 * <p>不同实现支持不同的调度策略：
 * <ul>
 *   <li>FIFO 队列 - 先入先出，广度优先</li>
 *   <li>优先级队列 - 按优先级排序，高优先级先爬取</li>
 *   <li>延迟队列 - 支持定时爬取，控制请求间隔</li>
 *   <li>分布式队列 - 基于 Redis 等实现分布式爬取</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SpiderScheduler {

    /**
     * 将一个请求加入待爬取队列。
     *
 * <p>在入队之前，应经过 {@link SpiderUrlFilter} 的过滤
 * 和 {@link com.chua.common.support.task.deduplicate.Deduplicator} 的去重检查。
     *
     * @param request 待爬取的请求
     */
    void enqueue(SpiderRequest request);

    /**
     * 从队列中取出下一个待爬取请求。
     *
     * <p>如果队列为空，返回 null。调度器应在此方法中控制请求间隔。
     *
     * @return 下一个待爬取的请求，队列为空时返回 null
     */
    SpiderRequest dequeue();

    /**
     * 判断队列中是否还有未处理的请求。
     *
     * @return 还有待处理请求时返回 true
     */
    boolean hasNext();

    /**
     * 获取队列中的剩余请求数。
     *
     * @return 待处理请求的数量
     */
    int size();

    /**
     * 清空队列。
     *
     * <p>重置调度器状态，清除所有待处理请求。
     */
    void clear();
}
