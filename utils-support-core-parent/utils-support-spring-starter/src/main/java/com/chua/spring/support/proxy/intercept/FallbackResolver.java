package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.proxy.ProxyMethod;
import com.chua.spring.support.configuration.SpringBeanUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * 降级方法（fallback）引用解析器。
 *
 * <p>统一支持两种降级方法引用形式：</p>
 * <ul>
 *   <li><b>{@code beanName#methodName}</b>：从 Spring 容器按名称获取降级 Bean 后调用其方法。
 *       可将降级方法收敛到公共降级 Bean（如统一空降级），业务方法无需在同类内各自编写降级方法；</li>
 *   <li><b>{@code methodName}</b>：目标对象同类方法（与历史行为一致）。</li>
 * </ul>
 *
 * <p>方法查找兼容签名差异：优先精确匹配，其次按"参数个数相同且目标方法参数可赋给降级方法参数"宽松匹配；
 * 容器未初始化、Bean 或方法不存在、调用异常时均返回 {@code null}（调用方决定后续兜底行为），不阻断主流程。</p>
 *
 * @author CH
 * @since 2026/09/03
 */
@Slf4j
public final class FallbackResolver {

    /**
     * bean 与方法名的分隔符
     */
    private static final String SEPARATOR = "#";

    private FallbackResolver() {
    }

    /**
     * 解析并执行降级方法。
     *
     * @param fallback    注解上的 fallback 属性值，支持 {@code beanName#methodName} 或 {@code methodName}
     * @param proxyMethod 被拦截方法信息
     * @return 降级方法返回值，无法降级时返回 null
     */
    public static Object resolve(String fallback, ProxyMethod proxyMethod) {
        if (fallback == null || fallback.isBlank()) {
            return null;
        }
        Object target = proxyMethod.getTarget();
        Object[] args = proxyMethod.getArgs();
        Class<?>[] paramTypes = proxyMethod.getParameterTypes();
        int separatorIndex = fallback.indexOf(SEPARATOR);
        try {
            if (separatorIndex > 0) {
                String beanName = fallback.substring(0, separatorIndex).trim();
                String methodName = fallback.substring(separatorIndex + 1).trim();
                return resolveBeanMethod(beanName, methodName, args, paramTypes);
            }
            return resolveMethod(target, fallback.trim(), args, paramTypes);
        } catch (Throwable throwable) {
            // 降级失败不阻断主流程，返回 null 交由调用方兜底
            log.warn("解析/执行降级方法失败: fallback={}", fallback, throwable);
            return null;
        }
    }

    /**
     * 执行 Spring 容器中指定 Bean 的降级方法。
     *
     * @param beanName   Bean 名称
     * @param methodName 方法名
     * @param args       目标方法实参
     * @param paramTypes 目标方法参数类型
     * @return 降级方法返回值，Bean/方法不可用时返回 null
     */
    private static Object resolveBeanMethod(String beanName,
                                            String methodName,
                                            Object[] args,
                                            Class<?>[] paramTypes) throws Exception {
        Object bean;
        try {
            bean = SpringBeanUtils.getBean(beanName, Object.class);
        } catch (Throwable throwable) {
            log.warn("Spring 容器未就绪或 Bean 不存在: beanName={}", beanName);
            return null;
        }
        if (bean == null) {
            log.warn("未找到降级 Bean: beanName={}", beanName);
            return null;
        }
        return resolveMethod(bean, methodName, args, paramTypes);
    }

    /**
     * 在目标对象上解析并执行降级方法。
     *
     * @param owner      降级方法所属对象
     * @param methodName 方法名
     * @param args       目标方法实参
     * @param paramTypes 目标方法参数类型
     * @return 降级方法返回值，方法不存在时返回 null
     * @throws Exception 降级方法调用异常
     */
    private static Object resolveMethod(Object owner, String methodName, Object[] args, Class<?>[] paramTypes) throws Exception {
        if (owner == null) {
            return null;
        }
        Method method = findMethod(owner.getClass(), methodName, paramTypes);
        if (method == null) {
            log.warn("未找到降级方法: ownerClass={}, methodName={}", owner.getClass().getName(), methodName);
            return null;
        }
        if (!method.canAccess(owner)) {
            method.setAccessible(true);
        }
        return method.invoke(owner, args);
    }

    /**
     * 查找降级方法：优先精确匹配，其次按"参数个数相同且目标方法参数类型可赋值给降级方法参数类型"宽松匹配。
     *
     * @param type       方法所属类型
     * @param methodName 方法名
     * @param paramTypes 目标方法参数类型
     * @return 匹配的方法，未找到返回 null
     */
    private static Method findMethod(Class<?> type, String methodName, Class<?>[] paramTypes) {
        // Spring 7 起 ClassUtils.findMethod 已移除，使用等价的 getMethodIfAvailable（精确签名匹配）
        Method exact = ClassUtils.getMethodIfAvailable(type, methodName, paramTypes);
        if (exact != null) {
            return exact;
        }
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != paramTypes.length) {
                continue;
            }
            Class<?>[] declaredParamTypes = method.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!declaredParamTypes[i].isAssignableFrom(paramTypes[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return method;
            }
        }
        return null;
    }
}
