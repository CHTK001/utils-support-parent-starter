package com.chua.common.support.pool;

import com.chua.common.support.concurrent.pool.GenericObjectPool;
import com.chua.common.support.concurrent.pool.ObjectPool;
import com.chua.common.support.concurrent.pool.ObjectPoolConfig;
import com.chua.common.support.concurrent.pool.ObjectFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 池化客户端抽象基类 (包装器风格)
 *
 * <p>为 {@code XxClient} 提供统一的池化能力, 通过持有一个底层客户端工厂实现。
 * 适用于已有客户端接口 (如 {@code ImageClient}, {@code ChatClient}) 的场景,
 * 子类只需在调用底层方法前通过 {@link #borrowClient()} 获取实例,
 * 调用后通过 {@link #returnClient(Object)} 归还。
 *
 * <p>数量语义 (与 {@link PooledObjectClient#pool(Number)} 保持一致):
 * <ul>
 *   <li>{@code null} 或 {@code <= 1}: 单例模式, 第一次访问时创建并复用同一实例 (默认)</li>
 *   <li>{@code > 1}: 池化模式, 创建指定大小的对象池</li>
 *   <li>{@code 0}: 关闭池化, 每次 {@link #borrowClient()} 都通过 factory 创建新实例并直接返回</li>
 * </ul>
 *
 * <p>线程安全: 内部使用 synchronized 保证池/单例的原子创建。
 *
 * @param <T> 客户端类型
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractPooledClient<T> implements PooledObjectClient<T> {

    /**
     * 池化模式: 0 (无池, 每次新建)
     */
    protected static final int MODE_DISABLED = 0;

    /**
     * 单例模式: 1
     */
    protected static final int MODE_SINGLETON = 1;

    /**
     * 当前模式: 0=无池, 1=单例, >1=池化大小
     */
    private volatile int poolMode = MODE_SINGLETON;

    /**
     * 单例实例
     */
    private volatile T singleton;

    /**
     * 对象池 (仅在池化模式下非空)
     */
    private volatile ObjectPool<T> objectPool;

    /**
     * 新实例工厂
     */
    private final Supplier<T> factory;

    /**
     * 实例配置回调 (用于在 borrow 后应用最新配置)
     */
    private final Function<T, T> configurator;


    /**
     * 构造方法
     *
     * @param factory     创建新实例的工厂, 必填
     * @param configurator 实例配置回调, 可为 null (表示无配置)
     */
    protected AbstractPooledClient(Supplier<T> factory, Function<T, T> configurator) {
        this.factory = factory;
        this.configurator = configurator;
    }


    /**
     * 构造方法 (无配置回调)
     *
     * @param factory 创建新实例的工厂
     */
    protected AbstractPooledClient(Supplier<T> factory) {
        this(factory, null);
    }


    /**
     * 自身既是池化目标又是客户端时的构造方法
     *
     * <p>当子类自身就是 {@code T} 的实现 (例如 {@code DefaultChatClient extends AbstractPooledClient<DefaultChatClient>}),
     * 可通过此构造方法直接使用, 内部 borrowClient 会返回 this。
     *
     * @param selfMarker 仅用于区分重载, 传任意非 null 值
     */
    protected AbstractPooledClient(Object selfMarker) {
        this.factory = null;
        this.configurator = null;
    }


    @Override
    /** ConfigurePool */
    public final void configurePool(Number size) {
        int target = size == null ? MODE_SINGLETON : size.intValue();
        synchronized (this) {
            closePool();
            if (target <= MODE_DISABLED) {
                poolMode = MODE_DISABLED;
                log.debug("已关闭客户端池化, 模式: DISABLED");
            } else if (target == MODE_SINGLETON) {
                poolMode = MODE_SINGLETON;
                log.debug("客户端切换为单例模式");
            } else {
                poolMode = target;
                objectPool = createPool(target);
                log.debug("客户端池化模式已启用, 大小: {}", target);
            }
        }
    }


    @Override
    /** 获取Pool */
    public final ObjectPool<T> getPool() {
        return objectPool;
    }


    /**
     * 借出客户端实例
     *
     * <p>子类调用此方法获取当前模式下可用的客户端实例:
     * <ul>
     *   <li>单例模式: 返回共享实例 (懒加载), 若 factory 为 null 则返回 this</li>
     *   <li>池化模式: 从池中 borrow</li>
     *   <li>无池化: 每次新建实例 (factory 为 null 时返回 this), 调用方无需归还</li>
     * </ul>
     *
     * @return 客户端实例
     */
    @SuppressWarnings("unchecked")
    protected T borrowClient() {
        int mode = poolMode;
        if (mode == MODE_DISABLED) {
            return factory == null ? (T) this : createAndConfigure();
        }
        if (mode == MODE_SINGLETON) {
            if (factory == null) {
                return (T) this;
            }
            T local = singleton;
            if (local == null) {
                synchronized (this) {
                    local = singleton;
                    if (local == null) {
                        local = createAndConfigure();
                        singleton = local;
                    }
                }
            }
            return local;
        }
        // 池化模式 (需要 factory)
        if (factory == null) {
            throw new IllegalStateException("无法对 self 类型 (factory=null) 启用池化");
        }
        ObjectPool<T> pool = objectPool;
        if (pool == null) {
            synchronized (this) {
                pool = objectPool;
                if (pool == null) {
                    pool = createPool(mode);
                    objectPool = pool;
                }
            }
            pool = objectPool;
        }
        try {
            return pool.borrow();
        } catch (Exception e) {
            throw new IllegalStateException("借出客户端实例失败", e);
        }
    }


    /**
     * 归还客户端实例
     *
     * <p>仅在池化模式下生效; 单例/无池化模式下为 no-op。
     *
     * @param client 要归还的实例
     */
    protected void returnClient(T client) {
        if (client == null) {
            return;
        }
        ObjectPool<T> pool = objectPool;
        if (pool != null) {
            pool.returnObject(client);
        }
    }


    /**
     * 创建并配置新实例
     */
    @SuppressWarnings("unchecked")
    private T createAndConfigure() {
        if (factory == null) {
            return (T) this;
        }
        T instance = factory.get();
        if (configurator != null && instance != null) {
            T configured = configurator.apply(instance);
            if (configured != null) {
                instance = configured;
            }
        }
        return instance;
    }


    /**
     * 创建对象池
     */
    private ObjectPool<T> createPool(int maxTotal) {
        ObjectPoolConfig config = ObjectPoolConfig.builder()
                .maxTotal(maxTotal)
                .maxIdle(maxTotal)
                .minIdle(0)
                .build();
        ObjectFactory<T> objectFactory = new ObjectFactory<T>() {
            @Override
            /** 创建 */
            public T create() {
                return createAndConfigure();
            }

            @Override
            /** 校验 */
            public boolean validate(T obj) {
                return obj != null;
            }

            @Override
            /** 销毁 */
            public void destroy(T obj) {
                if (obj instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) obj).close();
                    } catch (Exception e) {
                        log.warn("关闭池化客户端实例失败", e);
                    }
                }
            }
        };
        return new GenericObjectPool<>(config, objectFactory);
    }


    /**
     * 关闭并清理池
     */
    private void closePool() {
        if (objectPool != null) {
            try {
                objectPool.close();
            } catch (Exception e) {
                log.warn("关闭客户端池失败", e);
            }
            objectPool = null;
        }
        singleton = null;
    }


    /**
     * 关闭客户端, 释放所有池化资源
     */
    public void shutdown() {
        synchronized (this) {
            closePool();
        }
    }
}
