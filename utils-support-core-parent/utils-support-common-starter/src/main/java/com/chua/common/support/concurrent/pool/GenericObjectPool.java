package com.chua.common.support.concurrent.pool;

import com.chua.common.support.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 通用对象池实现
 *
 * <p>基于 ReentrantLock + Condition 的阻塞式对象池，支持：
 * <ul>
 *   <li>固定容量限制（maxTotal）</li>
 *   <li>空闲对象淘汰（idleTimeout）</li>
 *   <li>借出超时等待（borrowTimeout）</li>
 *   <li>对象有效性验证（testOnBorrow / testOnReturn）</li>
 *   <li>空闲检测定时清理（idleEviction）</li>
 *   <li>try-with-resources 自动归还（borrowGuard）</li>
 * </ul>
 *
 * <p>线程安全：所有操作通过同一把 ReentrantLock 串行化。
 *
 * <p>使用示例：
 * <pre>{@code
 *   ObjectPoolConfig config = ObjectPoolConfig.builder()
 *       .maxTotal(20)
 *       .borrowTimeoutMillis(5000)
 *       .build();
 *
 *   ObjectFactory<Connection> factory = new ObjectFactory<>() {
 *       public Connection create() { return DriverManager.getConnection(url); }
 *       public void destroy(Connection c) { c.close(); }
 *       public boolean validate(Connection c) { return !c.isClosed(); }
 *   };
 *
 *   ObjectPool<Connection> pool = new GenericObjectPool<>(config, factory);
 *
 *   // 借出 → 使用 → 归还
 *   Connection conn = pool.borrow();
 *   try {
 *       conn.query("SELECT ...");
 *   } finally {
 *       pool.returnObject(conn);
 *   }
 *
 *   // try-with-resources 自动归还
 *   try (var guard = pool.guard()) {
 *       guard.get().query("SELECT ...");
 *   }
 * }</pre>
 *
 * @param <T> 池化对象类型
 * @author CH
 * @since 2026/07/16
 */
public class GenericObjectPool<T> implements ObjectPool<T> {

    /**
     * 对象池配置
     */
    private final ObjectPoolConfig config;

    /**
     * 对象工厂
     */
    private final ObjectFactory<T> factory;

    /**
     * 空闲对象队列（FIFO）
     */
    private final LinkedList<PooledObject<T>> idleObjects = new LinkedList<>();

    /**
     * 所有对象（含借出+空闲）
     */
    private final List<PooledObject<T>> allObjects = new ArrayList<>();

    /**
     * 锁
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 等待借出的条件
     */
    private final Condition borrowCondition = lock.newCondition();

    /**
     * 空闲检测线程
     */
    private Thread evictionThread;

    /**
     * 池是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 使用默认配置创建对象池
     *
     * <p>默认配置：最大容量 10，借出超时 3 秒，空闲超时 60 秒。
     * 适用于快速创建场景，复杂场景请使用 {@link #GenericObjectPool(ObjectPoolConfig, ObjectFactory)}。
     *
     * @param factory 对象工厂
     */
    public GenericObjectPool(ObjectFactory<T> factory) {
        this(ObjectPoolConfig.builder().build(), factory);
    }

    /**
     * 创建对象池
     *
     * @param config  池配置
     * @param factory 对象工厂（必须实现 create/initObject/destroy/validate）
     */
    public GenericObjectPool(ObjectPoolConfig config, ObjectFactory<T> factory) {
        this.config = config;
        this.factory = factory;
        if (config.getMaxIdle() == 0) {
            this.config.setMaxIdle(config.getMaxTotal());
        }
        // 预热：创建 minIdle 个空闲对象
        if (config.getMinIdle() > 0) {
            preallocate();
        }
        // 启动空闲检测线程
        if (config.isIdleEvictionEnabled()) {
            startEvictionThread();
        }
    }

    @Override
    /**
     * Borrow
    */
    public T borrow() throws Exception {
        if (closed) {
            throw new IllegalStateException("对象池已关闭");
        }
        lock.lock();
        try {
            long deadline = System.currentTimeMillis() + config.getBorrowTimeoutMillis();

            while (true) {
                // 1. 尝试从空闲队列获取
                PooledObject<T> pooled = pollIdleObject();
                if (pooled != null) {
                    return pooled.getObject();
                }

                // 2. 尝试创建新对象
                if (allObjects.size() < config.getMaxTotal()) {
                    T obj = createAndInit();
                    PooledObject<T> newPooled = new PooledObject<>(obj);
                    newPooled.markBorrowed();
                    allObjects.add(newPooled);
                    return obj;
                }

                // 3. 池已满，等待归还
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new PoolTimeoutException("等待借出超时: " + config.getBorrowTimeoutMillis() + "ms"
                            + "，池容量: " + config.getMaxTotal()
                            + "，已借出: " + getNumActive());
                }
                borrowCondition.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * ReturnObject
    */
    public void returnObject(T object) {
        if (object == null) {
            return;
        }
        lock.lock();
        try {
            PooledObject<T> pooled = findPooled(object);
            if (pooled == null) {
                return;
            }

            // 验证有效性
            if (config.isTestOnReturn() && !factory.validate(object)) {
                destroyPooled(pooled);
                signalBorrowers();
                return;
            }

            // 归还到空闲队列
            pooled.markReturned();

            // 超出最大空闲数则销毁
            if (idleObjects.size() >= config.getMaxIdle()) {
                destroyPooled(pooled);
            } else {
                idleObjects.addLast(pooled);
            }

            signalBorrowers();
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * InvalidateObject
    */
    public void invalidateObject(T object) {
        if (object == null) {
            return;
        }
        lock.lock();
        try {
            PooledObject<T> pooled = findPooled(object);
            if (pooled != null) {
                destroyPooled(pooled);
                signalBorrowers();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * 获取NumIdle
    */
    public int getNumIdle() {
        lock.lock();
        try {
            return idleObjects.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * 获取NumActive
    */
    public int getNumActive() {
        lock.lock();
        try {
            int active = 0;
            for (PooledObject<T> p : allObjects) {
                if (p.getStatus() == PooledObject.Status.BORROWED) {
                    active++;
                }
            }
            return active;
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * Clear
    */
    public void clear() {
        lock.lock();
        try {
            // 销毁所有空闲对象
            Iterator<PooledObject<T>> it = idleObjects.iterator();
            while (it.hasNext()) {
                PooledObject<T> p = it.next();
                it.remove();
                destroyObjectQuietly(p);
            }
            allObjects.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        lock.lock();
        try {
            closed = true;
            // 停止空闲检测线程
            if (evictionThread != null) {
                evictionThread.interrupt();
            }
            clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取池状态信息
     *
     * @return 状态摘要字符串
     */
    public String getStats() {
        lock.lock();
        try {
            return "GenericObjectPool{maxTotal=" + config.getMaxTotal()
                    + ", idle=" + idleObjects.size()
                    + ", active=" + getNumActive()
                    + ", total=" + allObjects.size() + "}";
        } finally {
            lock.unlock();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 从空闲队列取出一个可用对象
     * @return Pooled对象 对象
     */
    private PooledObject<T> pollIdleObject() {
        Iterator<PooledObject<T>> it = idleObjects.iterator();
        while (it.hasNext()) {
            PooledObject<T> p = it.next();
            it.remove();

            // 检查空闲超时
            if (config.getIdleTimeoutMillis() > 0
                    && p.getIdleTimeMillis() > config.getIdleTimeoutMillis()) {
                destroyObjectQuietly(p);
                continue;
            }

            // 验证有效性
            if (config.isTestOnBorrow() && !factory.validate(p.getObject())) {
                destroyObjectQuietly(p);
                continue;
            }

            p.markBorrowed();
            return p;
        }
        return null;
    }

    /**
     * 查找对象对应的 PooledObject
     * @param object 对象，不允许为 null
     * @return Pooled对象 对象
     */
    private PooledObject<T> findPooled(T object) {
        for (PooledObject<T> p : allObjects) {
            if (p.getObject() == object) {
                return p;
            }
        }
        return null;
    }

    /**
     * 销毁 PooledObject 并从 allObjects 移除
     * @param pooled 方法入参 pooled
     */
    private void destroyPooled(PooledObject<T> pooled) {
        pooled.setStatus(PooledObject.Status.INVALID);
        allObjects.remove(pooled);
        destroyObjectQuietly(pooled);
    }

    /**
     * 静默销毁对象（不抛异常）
     * @param pooled 方法入参 pooled
     */
    private void destroyObjectQuietly(PooledObject<T> pooled) {
        try {
            factory.destroy(pooled.getObject());
        } catch (Exception ignored) {
        }
    }

    /**
     * 通知等待借出的线程
     */
    private void signalBorrowers() {
        borrowCondition.signalAll();
    }

    /**
     * 预分配 minIdle 个对象
     */
    private void preallocate() {
        try {
            for (int i = 0; i < config.getMinIdle(); i++) {
                T obj = createAndInit();
                PooledObject<T> pooled = new PooledObject<>(obj);
                idleObjects.addLast(pooled);
                allObjects.add(pooled);
            }
        } catch (Exception e) {
            throw new RuntimeException("预分配对象失败", e);
        }
    }

    /**
     * 创建并初始化对象
     *
     * <p>调用 factory.create() 创建原始对象，再调用 factory.initObject() 执行初始化。
     * 初始化失败时自动销毁对象并抛出异常。
     *
     * @return 初始化完成的对象
     * @throws Exception 创建或初始化失败
     */
    private T createAndInit() throws Exception {
        T obj = factory.create();
        try {
            factory.initObject(obj);
        } catch (Exception e) {
            // 初始化失败，销毁对象
            try {
                factory.destroy(obj);
            } catch (Exception ignored) {
            }
            throw e;
        }
        return obj;
    }

    /**
     * 启动空闲检测线程
     */
    private void startEvictionThread() {
        evictionThread = ThreadUtils.newThread(() -> {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                ThreadUtils.sleep(config.getIdleEvictionIntervalMillis());
                evictIdleObjects();
            }
        }, "object-pool-eviction");
        evictionThread.setDaemon(true);
        evictionThread.start();
    }

    /**
     * 清理超时空闲对象
     */
    private void evictIdleObjects() {
        lock.lock();
        try {
            Iterator<PooledObject<T>> it = idleObjects.iterator();
            while (it.hasNext()) {
                PooledObject<T> p = it.next();
                if (p.getIdleTimeMillis() > config.getIdleTimeoutMillis()) {
                    it.remove();
                    destroyObjectQuietly(p);
                    allObjects.remove(p);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
