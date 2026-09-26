package com.chua.datalake.support.subscriber;

import com.chua.datalake.support.model.DataEnvelope;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅器注册表 —— 实时投递的落点。
 *
 * <p>{@code RealTimeSink} 经 {@code SubscriberChannel} 把信封交给本注册表，
 * 由本注册表按主题过滤后调用 {@link Subscriber#onPush(PushPayload)}。
 * 单个订阅器抛错只影响它自己（位点不推进，可重投），不会波及其他订阅器。</p>
 *
 * <p>进程级单例：Sink 侧由 SPI 实例化、拿不到应用持有的管理器实例，
 * 因此注册表作为唯一事实源，{@code SubscriberManager} 只是它面向应用的门面。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SubscriberRegistry {

    /**
     * 进程级实例
     */
    private static final SubscriberRegistry INSTANCE = new SubscriberRegistry();

    /**
     * 订阅器标识 → 订阅器
     */
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();

    /**
     * 创建订阅器注册表
     */
    private SubscriberRegistry() {
    }

    /**
     * 返回进程级实例。
     *
     * @return 注册表
     */
    public static SubscriberRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 登记订阅器，同标识直接覆盖。
     *
     * @param subscriber 订阅器
     * @return this
     */
    public SubscriberRegistry register(Subscriber subscriber) {
        if (subscriber == null || subscriber.subscriberId() == null) {
            throw new IllegalArgumentException("订阅器与 subscriberId 不能为空");
        }
        subscribers.put(subscriber.subscriberId(), subscriber);
        return this;
    }

    /**
     * 注销订阅器。
     *
     * @param subscriberId 订阅器标识
     * @return 被注销的订阅器，未登记时为空
     */
    public Subscriber unregister(String subscriberId) {
        return subscriberId == null ? null : subscribers.remove(subscriberId);
    }

    /**
     * 判断订阅器是否已登记。
     *
     * @param subscriberId 订阅器标识
     * @return 已登记返回 true
     */
    public boolean contains(String subscriberId) {
        return subscriberId != null && subscribers.containsKey(subscriberId);
    }

    /**
     * 当前已登记的订阅器快照。
     *
     * @return 订阅器列表
     */
    public Collection<Subscriber> subscribers() {
        return List.copyOf(subscribers.values());
    }

    /**
     * 已登记订阅器数量。
     *
     * @return 数量
     */
    public int size() {
        return subscribers.size();
    }

    /**
     * 清空注册表，用于测试与停机。
     */
    public void clear() {
        subscribers.clear();
    }

    /**
     * 按主题过滤后投递一条信封。
     *
     * @param envelope 数据信封
     * @return 成功收下的订阅器数量
     */
    public int dispatch(DataEnvelope envelope) {
        if (envelope == null) {
            return 0;
        }
        int delivered = 0;
        for (Subscriber subscriber : subscribers.values()) {
            if (!interests(subscriber, envelope)) {
                continue;
            }
            try {
                subscriber.onPush(PushPayload.of(subscriber.subscriberId(), envelope));
                delivered++;
            } catch (Exception e) {
                log.error("[datalake-subscribe] 订阅器投递失败: subscriberId=" + subscriber.subscriberId(), e);
            }
        }
        return delivered;
    }

    /**
     * 判定订阅器是否关心该信封。
     *
     * <p>订阅器未声明主题即全量接收；声明了主题则要求信封主题集合有交集，
     * 无主题的信封只能被全量接收者命中。</p>
     *
     * @param subscriber 订阅器
     * @param envelope   数据信封
     * @return 需要投递返回 true
     */
    private static boolean interests(Subscriber subscriber, DataEnvelope envelope) {
        Set<String> wanted = subscriber.topics();
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        Set<String> owned = envelope.getTopics();
        if (owned == null || owned.isEmpty()) {
            return false;
        }
        for (String topic : owned) {
            if (wanted.contains(topic)) {
                return true;
            }
        }
        return false;
    }
}
