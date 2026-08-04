package com.chua.common.support.proxy.intercept;

import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.ObjectUtils;
import lombok.AllArgsConstructor;

import java.lang.reflect.Method;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NullUnmarked;


/**
 * 桥接方法拦截器，将代理方法调用委托给指定的目标对象处理。
 *
 * @param <T> 代理接口类型
 * @author CH
 * @since 2025/7/20
 */
@NullUnmarked
@AllArgsConstructor
public class BridgingMethodIntercept<T> implements MethodIntercept<T> {

    /** 桥接目标对象，方法调用将被委托给该对象 */
    private final Object bridging;

    /**
     * 类型
     */
    private final Class<?> type;

    @Override
    public Object invoke(Object obj, Method method, Object[] args, T proxy) throws Throwable {
        if (MethodIntercept.isToString(method)) {
            return ObjectUtils.withNull(bridging, () -> "void", Object::toString);
        }

        if (MethodIntercept.isGetClass(method)) {
            return type;
        }

        if (MethodIntercept.isEquals(method)) {
            return ObjectUtils.withNull(bridging, () -> false, a -> a.equals(args != null && args.length > 0 ? args[0] : null));
        }

        if (MethodIntercept.isHashCode(method)) {
            return ObjectUtils.withNull(bridging, type::hashCode, Object::hashCode);
        }

        if (bridging == null) {
            return null;
        }
        return ClassUtils.invokeMethod(method, bridging, args);
    }


}
