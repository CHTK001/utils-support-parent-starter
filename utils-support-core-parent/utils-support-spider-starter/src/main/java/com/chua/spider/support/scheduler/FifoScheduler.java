package com.chua.spider.support.scheduler;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.spider.support.SpiderScheduler;
import com.chua.spider.support.model.SpiderRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
* FIFO（先进先出）爬虫调度器。
*
* <p>基于 {@link ConcurrentLinkedQueue} 实现，保证线程安全。
* 按入队顺序依次取出 URL，支持并发场景下的安全操作。
*
* <p>SPI 名称：{@code scheduler:fifo}
*
* <p>适用于广度优先爬取场景，先入队的 URL 先被处理。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("fifo")
public class FifoScheduler implements SpiderScheduler {

    /**
    * 待爬取队列
     */
    private final ConcurrentLinkedQueue<SpiderRequest> queue = new ConcurrentLinkedQueue<>();

    /**
    * 队列中剩余请求数
     */
    private final AtomicInteger count = new AtomicInteger(0);

    @Override
    /** 入队 */
    public void enqueue(SpiderRequest request) {
        if (request == null) {
            return;
        }
        queue.offer(request);
        count.incrementAndGet();
    }

    @Override
    /** 出队 */
    public SpiderRequest dequeue() {
        SpiderRequest request = queue.poll();
        if (request != null) {
            count.decrementAndGet();
        }
        return request;
    }

    @Override
    /** 是否拥有下一个 */
    public boolean hasNext() {
        return count.get() > 0;
    }

    @Override
    /** 获取大小 */
    public int size() {
        return count.get();
    }

    @Override
    /** Clear */
    public void clear() {
        queue.clear();
        count.set(0);
    }
}
