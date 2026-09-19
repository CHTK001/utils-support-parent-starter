package com.chua.common.support.concurrent.queue;

/**
 * 无锁队列工厂，提供统一创建入口。
 *
 * <p>根据 {@link QueueType} 创建对应的无锁队列实现：
 * <ul>
 *   <li>{@link QueueType#SPSC} — {@link SpscArrayQueue}</li>
 *   <li>{@link QueueType#MPMC} — {@link MpmcArrayQueue}</li>
 *   <li>{@link QueueType#UNBOUNDED} — {@link MichaelScottQueue}</li>
 * </ul>
 * </p>
 *
 * <p>本类为纯静态工具类，禁止实例化。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class LockFreeQueueFlow {

    /**
     * 私有构造，禁止实例化。
     */
    private LockFreeQueueFlow() {
    }

    /**
     * 根据配置创建无锁队列。
     *
     * @param config 队列配置，禁止为 null
     * @param <E>    队列元素类型
     * @return 无锁队列实例
     * @throws NullPointerException 配置为 null 时抛出
     */
    public static <E> LockFreeQueue<E> create(QueueConfig config) {
        if (config == null) {
            throw new NullPointerException("config must not be null");
        }
        return create(config.getType(), config.getCapacity());
    }

    /**
     * 根据类型与容量创建无锁队列。
     *
     * @param type     队列类型，禁止为 null
     * @param capacity 有界队列容量，UNBOUNDED 时忽略
     * @param <E>      队列元素类型
     * @return 无锁队列实例
     * @throws NullPointerException 类型为 null 时抛出
     */
    public static <E> LockFreeQueue<E> create(QueueType type, int capacity) {
        if (type == null) {
            throw new NullPointerException("type must not be null");
        }
        switch (type) {
            case SPSC:
                return new SpscArrayQueue<>(capacity);
            case MPMC:
                return new MpmcArrayQueue<>(capacity);
            case UNBOUNDED:
                return new MichaelScottQueue<>();
            default:
                throw new IllegalArgumentException("未知的队列类型: " + type);
        }
    }
}
