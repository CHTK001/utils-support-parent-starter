package com.chua.common.support.concurrent.threadflow;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
* 线程上下文，用于在任务执行期间传递参数与状态。
*
* <p>基于 {@link ThreadLocal} 实现，支持父子线程之间的值传递。
* 适用于 {@link ThreadFlow} 中跨任务共享上下文信息的场景。</p>
*
* @author CH
* @since 2026/08/15
 */
public class ThreadContext {

    /**
    * 上下文 ID 自增生成器
    */
    private static final AtomicLong ID_GEN = new AtomicLong(0);

    /**
    * 当前线程上下文
    */
    private static final ThreadLocal<ThreadContext> CURRENT = new ThreadLocal<>();

    /**
    * 上下文唯一标识
    */
    private final long id = ID_GEN.incrementAndGet();

    /**
    * 上下文属性存储
    */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
    * 获取当前线程的上下文，若不存在则自动创建。
    *
    * @return 当前线程上下文
    */
    public static ThreadContext current() {
        ThreadContext ctx = CURRENT.get();
        if (ctx == null) {
            ctx = new ThreadContext();
            CURRENT.set(ctx);
        }
        return ctx;
    }

    /**
    * 获取当前线程的上下文，不存在时返回 null。
    *
    * <p>与 {@link #current()} 的区别：不会自动创建空上下文，
    * 便于判断调用线程是否已显式设置上下文。</p>
    *
    * @return 当前线程上下文，未设置返回 null
    */
    public static ThreadContext currentOrNull() {
        return CURRENT.get();
    }

    /**
    * 设置当前线程的上下文。
    *
    * @param context 上下文实例
    */
    public static void set(ThreadContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    /**
    * 清除当前线程的上下文。
    */
    public static void clear() {
        CURRENT.remove();
    }

    /**
    * 获取上下文唯一标识。
    *
    * @return 上下文 ID
    */
    public long getId() {
        return id;
    }

    /**
    * 设置上下文属性。
    *
    * @param key   属性键
    * @param value 属性值
    * @return 当前上下文，支持链式调用
    */
    public ThreadContext setAttribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    /**
    * 获取上下文属性。
    *
    * @param key 属性键
    * @param <V> 值类型
    * @return 属性值，不存在返回 null
    */
    @SuppressWarnings("unchecked")
    public <V> V getAttribute(String key) {
        return (V) attributes.get(key);
    }

    /**
    * 移除上下文属性。
    *
    * @param key 属性键
    */
    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    /**
    * 创建当前上下文的一个浅拷贝，用于传递给子线程。
    *
    * @return 新的上下文实例，属性值共享引用
    */
    public ThreadContext copy() {
        ThreadContext copy = new ThreadContext();
        copy.attributes.putAll(this.attributes);
        return copy;
    }

    /**
    * 将当前上下文绑定到当前线程（用于子线程继承）。
    */
    public void bind() {
        CURRENT.set(this);
    }
}
