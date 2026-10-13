package com.chua.common.support.export.resolver;

/**
 * 内置解析器的默认优先级。
 *
 * <p>数值越小越先执行，越大越后执行（优先级越高）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MetaResolverOrders {

    /**
     * 反射兜底：最先执行，为所有列打底。
     */
    public static final int FALLBACK = -100;

    /**
     * 三方注解：作为底层来源，可被自定义注解覆盖。
     */
    public static final int THIRD_PARTY = 0;

    /**
     * 自定义注解：最后执行，最终覆盖。
     */
    public static final int CUSTOM = 100;

    private MetaResolverOrders() {
    }
}