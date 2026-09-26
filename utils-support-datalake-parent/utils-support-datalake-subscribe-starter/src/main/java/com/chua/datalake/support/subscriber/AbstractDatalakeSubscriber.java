package com.chua.datalake.support.subscriber;

import com.chua.common.support.concurrent.offset.OffsetFlow;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 数据订阅器抽象类。每个订阅器绑定一个 {@code subscriberId}，
 * 并通过 {@link OffsetFlow} 管理 偏移量。
 *
 * <p>{@link #topics()} 决定该订阅器接收哪些主题，空集合表示全量接收。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractDatalakeSubscriber implements Subscriber {

    /**
     * 订阅器 标识，唯一标识
     */
    protected final String subscriberId;

    /**
     * 偏移量 门面
     */
    protected final OffsetFlow offsetFlow;

    /**
     * 订阅主题集合，空集合表示不限主题
     */
    protected final Set<String> topics;

    /**
     * 构造（不限主题）。
     *
     * @param subscriberId 订阅器 标识
     * @param offsetFlow   偏移量流 门面
     */
    protected AbstractDatalakeSubscriber(String subscriberId, OffsetFlow offsetFlow) {
        this(subscriberId, offsetFlow, Collections.emptySet());
    }

    /**
     * 构造。
     *
     * @param subscriberId 订阅器 标识
     * @param offsetFlow   偏移量流 门面
     * @param topics       订阅主题，空表示全量接收
     */
    protected AbstractDatalakeSubscriber(String subscriberId, OffsetFlow offsetFlow, Set<String> topics) {
        this.subscriberId = subscriberId;
        this.offsetFlow = offsetFlow;
        this.topics = topics == null || topics.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(topics));
    }

    /**
     * 返回订阅器 标识
     *
     * @return 订阅器唯一标识
     */
    @Override
    public String subscriberId() {
        return subscriberId;
    }

    @Override
    /**
     * topics
    */
    public Set<String> topics() {
        return topics;
    }

    /**
     * 当前已推送的最大 偏移量
     *
     * @return offset 数值
     */
    public long currentOffset() {
        return offsetFlow.current(subscriberId);
    }

    /**
     * 推进 偏移量。
     *
     * @return 新 偏移量 值
     */
    public long advance() {
        return offsetFlow.advance(subscriberId);
    }

    /**
     * 重置 偏移量。
     *
     * @param newValue 期望的 偏移量 值
     */
    public void reset(long newValue) {
        offsetFlow.reset(subscriberId, newValue);
    }

    /**
     * 删除该订阅器 偏移量。
     */
    public void remove() {
        offsetFlow.remove(subscriberId);
    }

    /**
     * 订阅器实际订阅/推送实现。
     */
    public abstract void subscribe();
}
