package com.chua.common.support.objects.provider;

import com.chua.common.support.objects.ObjectContext;

/**
 * 默认对象提供者实现。
 *
 * <p>基于 {@link ObjectContext} 的 Bean 查找能力，实现了 {@link ObjectProvider} 接口。
 * 在调用 {@link #getObject()} 时，通过上下文按类型安全地获取 Bean 实例。</p>
 *
 * @param <T> 对象类型泛型
 * @author CH
 * @since 2024/12/20
 */
public class DefaultObjectProvider<T> implements ObjectProvider<T> {

    /** CTX */
    private final ObjectContext ctx;
    /**
    * 类型
    */
    private final Class<T> type;

    /**
     * 创建 默认对象提供者 实例
     * @param ctx ctx
     * @param type 类
     * @param type 类型
     */
    public DefaultObjectProvider(ObjectContext ctx, Class<T> type) {
        this.ctx = ctx;
        this.type = type;
    }

    @Override
    /** 获取对象 */
    public T getObject() {
        return ctx.getBeanOfTypeSafely(type);
    }
}
