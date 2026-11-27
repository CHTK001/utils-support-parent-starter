package com.chua.common.support.concurrent.pool;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.utils.ThreadUtils;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
     * 日志
     */
    private static final System.Logger LOG = System.getLogger(GenericObjectPool.class.getName());

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
    private final Deque<PooledObject<T>> idleObjects = Wraps.deque();

    /**
     * 所有对象（含借出+空闲）
     */
    private final List<PooledObject<T>> allObjects = Wraps.list();

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
        this.config = Objects.requireNonNull(config, "config");
        this.factory = Objects.requireNonNull(factory, "factory");
        if (config.getMaxTotal() < 1 || config.getMinIdle() < 0
                || config.getMinIdle() > config.getMaxTotal() || config.getMaxIdle() < 0
                || config.getMaxIdle() > config.getMaxTotal()
                || config.getIdleTimeoutMillis() < 0
                || config.getBorrowTimeoutMillis() < 0
                || (config.isIdleEvictionEnabled() && config.getIdleEvictionIntervalMillis() < 1)) {
            throw new IllegalArgumentException("对象池容量、等待时间或清扫间隔无效");
        }
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

    /**
     * Borrow
     */
    @Override
    public T borrow() throws Exception {
        if (closed) {
            throw new IllegalStateException("对象池已关闭");
        }
        lock.lockInterruptibly();
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(config.getBorrowTimeoutMillis());

            while (true) {
                if (closed) {
                    throw new IllegalStateException("对象池已关闭");
                }
                // 1. 尝试从空闲队列获取
                PooledObject<T> pooled = pollIdleObject();
                if (pooled != null) {
                    return pooled.getObject();
                }

                // 2. 尝试创建新对象
                if (allObjects.size() < config.getMaxTotal()) {
                    T obj = createAndInit();
                    var newPooled = new PooledObject<>(obj);
                    newPooled.markBorrowed();
                    allObjects.add(newPooled);
                    return obj;
                }

                // 3. 池已满，等待归还
                if (remaining <= 0) {
                    throw new PoolTimeoutException("等待借出超时: " + config.getBorrowTimeoutMillis() + "ms"
                            + "，池容量: " + config.getMaxTotal()
                            + "，已借出: " + getNumActive());
                }
                remaining = borrowCondition.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * ReturnObject
     */
    @Override
    public void returnObject(T object) {
        if (object == null) {
            return;
        }
        lock.lock();
        try {
            PooledObject<T> pooled = findPooled(object);
            if (pooled == null || pooled.getStatus() != PooledObject.Status.BORROWED) {
                return;
            }

            // 归还前先清理可变状态，失败对象不进入空闲队列
            if (closed || !passivateQuietly(object)
                    || (config.isTestOnReturn() && !validateQuietly(object))) {
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

    /**
     * InvalidateObject
     */
    @Override
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

    /**
     * 获取NumIdle
     */
    @Override
    public int getNumIdle() {
        lock.lock();
        try {
            return idleObjects.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取NumActive
     */
    @Override
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

    /**
     * Clear
     */
    @Override
    public void clear() {
        lock.lock();
        try {
            // 销毁所有空闲对象
            Iterator<PooledObject<T>> it = idleObjects.iterator();
            while (it.hasNext()) {
                PooledObject<T> p = it.next();
                it.remove();
                allObjects.remove(p);
                p.setStatus(PooledObject.Status.INVALID);
                destroyObjectQuietly(p);
            }
            signalBorrowers();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            // 停止空闲检测线程
            if (evictionThread != null) {
                evictionThread.interrupt();
            }
            clear();
            signalBorrowers();
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
                destroyPooled(p);
                continue;
            }

            // 验证有效性
            if (config.isTestOnBorrow() && !validateQuietly(p.getObject())) {
                destroyPooled(p);
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
        idleObjects.remove(pooled);
        allObjects.remove(pooled);
        destroyObjectQuietly(pooled);
    }

    /**
     * 校验器异常同失效处理，保证对象不会脱离队列后占住容量。
     *
     * @param object 池化对象
     * @return 是否有效
     */
    private boolean validateQuietly(T object) {
        try {
            return factory.validate(object);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "[pool] 校验失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 执行归还前清理；工厂异常按不可复用处理。
     *
     * @param object 池化对象
     * @return 是否可以继续复用
     */
    private boolean passivateQuietly(T object) {
        try {
            factory.passivateObject(object);
            return true;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "[pool] 归还前清理失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 静默销毁对象（不抛异常）
     * @param pooled 方法入参 pooled
     */
    private void destroyObjectQuietly(PooledObject<T> pooled) {
        try {
            factory.destroy(pooled.getObject());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "[pool] 销毁对象失败，已忽略: " + e.getMessage(), e);
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
                var pooled = new PooledObject<>(obj);
                idleObjects.addLast(pooled);
                allObjects.add(pooled);
            }
        } catch (Exception e) {
            clear();
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
        T obj = Objects.requireNonNull(factory.create(), "对象工厂返回了 null");
        try {
            factory.initObject(obj);
        } catch (Exception e) {
            // 初始化失败，销毁对象
            try {
                factory.destroy(obj);
            } catch (Exception destroyEx) {
                LOG.log(System.Logger.Level.DEBUG, "[pool] 清理对象失败，已忽略: " + destroyEx.getMessage(), destroyEx);
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
    public void evictIdleObjects() {
        lock.lock();
        try {
            if (closed || config.getIdleTimeoutMillis() <= 0) {
                return;
            }
            Iterator<PooledObject<T>> it = idleObjects.iterator();
            while (it.hasNext()) {
                PooledObject<T> p = it.next();
                if (p.getIdleTimeMillis() > config.getIdleTimeoutMillis()) {
                    it.remove();
                    allObjects.remove(p);
                    p.setStatus(PooledObject.Status.INVALID);
                    destroyObjectQuietly(p);
                }
            }
            signalBorrowers();
        } finally {
            lock.unlock();
        }
    }
}
