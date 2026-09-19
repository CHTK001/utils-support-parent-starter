package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.spring.support.configuration.SpringBeanUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * 降级方法（降级）引用解析器。
 *
 * <p>统一支持两种降级方法引用形式：</p>
 * <ul>
 *   <li><b>{@code beanName#methodName}</b>：从 Spring 容器按名称获取降级 Bean 后调用其方法。
 *       可将降级方法收敛到公共降级 Bean（如统一空降级），业务方法无需在同类内各自编写降级方法；</li>
 *   <li><b>{@code methodName}</b>：目标对象同类方法（与历史行为一致）。</li>
 * </ul>
 *
 * <p>方法查找兼容签名差异：优先精确匹配，其次按"参数个数相同且目标方法参数可赋给降级方法参数"宽松匹配；
 * Bean 或方法不存在、调用异常时均返回 {@code null}（调用方决定后续兜底行为），不阻断主流程。</p>
 *
 * <p>容器解析优先级：{@link SpringBeanUtils} 线程绑定上下文（ThreadLocal）→
 * {@link #registerApplicationContext(ApplicationContext)} 注册的全局容器
 * （由 {@link CollapsibleIntercept} 在 Spring 装配时注入，跨线程可用）。</p>
 *
 * @author CH
 * @since 2026/09/03
 */
@Slf4j
public final class FallbackResolver {

    /**
     * Bean 与方法名的分隔符
     */
    private static final String SEPARATOR = "#";

    /**
     * 全局降级 Bean 容器（Spring 装配时注册，线程无关；thread本地 上下文不可用时回退）
     */
    private static volatile ApplicationContext fallbackContext;

    /**
     * 降级解析器。
     */
    private FallbackResolver() {
    }

    /**
     * 注册全局降级 Bean 容器。
     *
     * <p>{@link SpringBeanUtils} 的上下文为线程绑定（ThreadLocal），并发调用线程取不到；
     * 此处注册的全局容器供任意线程解析 {@code bean#method} 降级。</p>
     *
     * @param applicationContext Spring 容器
     */
    public static void registerApplicationContext(ApplicationContext applicationContext) {
        fallbackContext = applicationContext;
    }

    /**
     * 解析并执行降级方法。
     *
     * @param fallback    注解上的 降级 属性值，支持 {@code beanName#methodName} 或 {@code methodName}
     * @param proxyMethod 被拦截方法信息
     * @return 降级方法返回值，无法降级时返回 空
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
 // 降级失败不阻断主流程，返回 空 交由调用方兜底
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
     * @return 降级方法返回值，Bean/方法不可用时返回 空
     */
    private static Object resolveBeanMethod(String beanName,
                                            String methodName,
                                            Object[] args,
                                            Class<?>[] paramTypes) throws Exception {
        Object bean = lookupBean(beanName);
        if (bean == null) {
            return null;
        }
        return resolveMethod(bean, methodName, args, paramTypes);
    }

    /**
     * 按名称查找降级 Bean：优先线程绑定上下文，其次全局注册容器。
     *
     * @param beanName Bean 名称
     * @return Bean 实例，不可用时返回 空
     */
    private static Object lookupBean(String beanName) {
        try {
            return SpringBeanUtils.getBean(beanName, Object.class);
        } catch (Throwable throwable) {
            // 线程绑定上下文不可用（并发调用线程）时回退全局注册容器
            ApplicationContext context = fallbackContext;
            if (context == null) {
                log.warn("Spring 容器未就绪或 Bean 不存在: beanName={}", beanName);
                return null;
            }
            try {
                return context.getBean(beanName, Object.class);
            } catch (Throwable ignored) {
                log.warn("未找到降级 Bean: beanName={}", beanName);
                return null;
            }
        }
    }

    /**
     * 在目标对象上解析并执行降级方法。
     *
     * @param owner      降级方法所属对象
     * @param methodName 方法名
     * @param args       目标方法实参
     * @param paramTypes 目标方法参数类型
     * @return 降级方法返回值，方法不存在时返回 空
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
        // 可访问性由 ReflectUtils.invoke（MethodHandle 私有查找）统一处理，不再原生 setAccessible/method.invoke
        return ReflectUtils.invoke(owner, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
    }

    /**
     * 查找降级方法：优先精确匹配，其次按"参数个数相同且目标方法参数类型可赋值给降级方法参数类型"宽松匹配。
     *
     * @param type       方法所属类型
     * @param methodName 方法名
     * @param paramTypes 目标方法参数类型
     * @return 匹配的方法，未找到返回 空
     */
    private static Method findMethod(Class<?> type, String methodName, Class<?>[] paramTypes) {
 // Spring 7 起 类工具.查找方法 已移除，使用等价的 获取方法if可用（精确签名匹配）
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
