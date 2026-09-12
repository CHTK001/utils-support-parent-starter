package com.chua.common.support.shmqueue;

/**
   * shm队列 的 SPI 提供者接口。
 *
 * <p>具体实现放在独立的 native 模块中，通过
 * {@code META-INF/services/com.chua.common.support.shmqueue.ShmQueueProvider}
 * 文件注册。运行时由 {@link ShmQueue} 的静态工厂方法自动加载。</p>
 *
 * <p>典型实现见 utils-support-native-shm-queue 的
 * {@code NativeShmQueueProvider}（封装 libshmqueue.so / shmqueue.dll）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ShmQueueProvider {

    /**
     * 创建（或复用）一个共享内存队列。
     *
     * @param name      共享内存对象名
     * @param capacity  槽位数
     * @param slotSize  每槽字节数
     * @param mode      等待模式
     * @return ShmQueue 实例
     * @throws ShmQueueException 创建失败
     */
    ShmQueue create(String name, int capacity, int slotSize, ShmQueue.Mode mode);

    /**
     * 仅附加到已存在的共享内存队列。
     *
     * @param name 共享内存对象名
     * @return ShmQueue 实例
     * @throws ShmQueueException attach 失败
     */
    ShmQueue attach(String name);

    /**
     * 提供者优先级（数值越小优先级越高）。当存在多个实现时，{@link ShmQueue} 默认取第一个。
     *
     * @return 优先级，默认 100
     */
    default int order() {
        return 100;
    }

    /**
     * 提供者名称，便于诊断。
     *
     * @return 提供者名称
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
