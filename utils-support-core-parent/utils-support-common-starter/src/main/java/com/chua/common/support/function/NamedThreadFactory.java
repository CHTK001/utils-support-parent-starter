package com.chua.common.support.function;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义命名线程工厂
 * <p>用于创建带有自定义名称前缀的线程，方便在排查问题时通过线程名称快速定位。</p>
 *
 * @author CH
 * @since 2021-09-27
 */
public class NamedThreadFactory implements ThreadFactory {

    /**
     * 线程池序号，用于区分不同的线程池
     */
    protected static final AtomicInteger POOL_SEQ = new AtomicInteger(1);

    /**
     * 线程序号，用于区分同一个线程池中的不同线程
     */
    protected final AtomicInteger mThreadNum = new AtomicInteger(1);

    /**
     * 线程名称前缀
     */
    protected final String mPrefix;

    /**
     * 是否为守护线程
     */
    protected final boolean mDaemon;

    /**
     * 线程组
     */
    protected final ThreadGroup mGroup;

    /**
     * 默认构造方法，使用 "pool-{序号}" 作为前缀，非守护线程
     */
    public NamedThreadFactory() {
        this("pool-" + POOL_SEQ.getAndIncrement(), false);
    }

    /**
     * 指定前缀的构造方法，非守护线程
     *
     * @param prefix 线程名称前缀
     */
    public NamedThreadFactory(String prefix) {
        this(prefix, false);
    }

    /**
     * 指定前缀和是否守护线程的构造方法
     *
     * @param prefix  线程名称前缀
     * @param daemon  是否为守护线程
     */
    public NamedThreadFactory(String prefix, boolean daemon) {
        mPrefix = prefix + "-thread-";
        mDaemon = daemon;
        mGroup = Thread.currentThread().getThreadGroup();
    }

    /**
     * 创建一个新线程
     *
     * @param runnable 线程执行的任务
     * @return 新创建的线程
     */
    @Override
    public Thread newThread(Runnable runnable) {
        String name = mPrefix + mThreadNum.getAndIncrement();
        Thread ret = new Thread(mGroup, runnable, name, 0);
        ret.setDaemon(mDaemon);
        return ret;
    }

    /**
     * 获取线程组
     *
     * @return 线程组
     */
    public ThreadGroup getThreadGroup() {
        return mGroup;
    }
}
