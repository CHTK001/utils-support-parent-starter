package com.chua.runtime.spy;

import com.chua.runtime.plugin.InterceptPoint;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插桩运行时入口 — 被 ASM 字节码修改插入到目标方法中。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>作为 ASM 字节码插桩的静态入口，被 SpyTransformer 插入</li>
 *   <li>维护 Handler 注册表，根据类名/方法名/插桩点路由事件到对应 Handler</li>
 *   <li>维护线程局部上下文（TraceContext）</li>
 * </ul>
 *
 * <p>插桩流程：</p>
 * <pre>
 * 1. Handler.start() 调用 RuntimeSpy.registerInterceptor()
 * 2. RuntimeSpy 将拦截规则传递给 SpyTransformer
 * 3. SpyTransformer 在类加载时插入字节码: RuntimeSpy.onIntercept()
 * 4. 目标方法执行时触发 RuntimeSpy.onIntercept()
 * 5. RuntimeSpy 查找匹配的 Handler 并调用 onIntercept()
 * </pre>
 *
 * <p>插入的字节码：</p>
 * <pre>
 * LDC "org/slf4j/Logger"           // className
 * LDC "info"                        // methodName
 * LDC "(Ljava/lang/String;)V"       // descriptor
 * LDC "log_pre"                     // pointKey
 * INVOKESTATIC RuntimeSpy.onIntercept
 *
 *   ... 原始方法体 ...
 *
 * LDC "org/slf4j/Logger"           // className
 * LDC "info"                        // methodName
 * LDC "(Ljava/lang/String;)V"       // descriptor
 * LDC "log_post"                    // pointKey
 * INVOKESTATIC RuntimeSpy.onIntercept
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RuntimeSpy {

    /**
     * 拦截点匹配键：className#methodName#pointKey
     */
    private static final String KEY_SEPARATOR = "#";

    /**
     * 插桩上下文
     */
    private static final ThreadLocal<SpyContext> CONTEXT = new ThreadLocal<>();

    /**
     * Handler 注册表：拦截键 -> Handler 实例
     */
    private static final Map<String, Interceptor> INTERCEPTOR_MAP = new ConcurrentHashMap<>();

    /**
     * 插桩计数器
     */
    private static final ThreadLocal<Integer> TRANSFORM_COUNT = ThreadLocal.withInitial(() -> 0);

    private RuntimeSpy() {
    }

    /**
     * 注册拦截器。
     *
     * <p>Handler 在 start() 中调用此方法注册拦截规则。</p>
     *
     * @param className  目标类名（内部名，如 "org/slf4j/Logger"）
     * @param methodName 目标方法名（如 "info"）
     * @param descriptor 方法描述符（如 "(Ljava/lang/String;)V"）
     * @param point      插桩点（ENTRY / EXIT / LOG_PRE / LOG_POST 等）
     * @param interceptor Handler 实例
     */
    public static void registerInterceptor(String className,
                                           String methodName,
                                           String descriptor,
                                           InterceptPoint point,
                                           Interceptor interceptor) {
        String key = buildKey(className, methodName, point.getKey());
        INTERCEPTOR_MAP.put(key, interceptor);
        // 同步精确插桩规则到 SpyTransformer
        syncTransformerRule(className, methodName, point, true);
        log.debug("注册拦截器: {} -> {}[{}]", key, interceptor.getClass().getSimpleName(), point.getKey());
    }

    /**
     * 同步精确插桩规则到 SpyTransformer。
     *
     * <p>保证 ASM 转换器在类加载/重变换时对目标方法真正插入字节码。</p>
     *
     * @param className  目标类名（内部名）
     * @param methodName 目标方法名
     * @param point      插桩点
     * @param register   注册为 true，注销为 false
     */
    private static void syncTransformerRule(String className, String methodName,
                                            InterceptPoint point, boolean register) {
        try {
            if (SpyBootstrap.isInitialized()) {
                SpyTransformer transformer = SpyBootstrap.getTransformer();
                if (transformer != null) {
                    if (register) {
                        transformer.registerMethod(className, methodName, point);
                    } else {
                        transformer.unregisterMethod(className, methodName, point);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("同步插桩规则失败: {}.{}.{}", className, methodName, e.getMessage());
        }
    }

    /**
     * 注销拦截器。
     *
     * @param className  目标类名
     * @param methodName 目标方法名
     * @param point      插桩点
     */
    public static void unregisterInterceptor(String className,
                                             String methodName,
                                             InterceptPoint point) {
        String key = buildKey(className, methodName, point.getKey());
        INTERCEPTOR_MAP.remove(key);
        log.debug("注销拦截器: {}", key);
    }

    /**
     * 批量注销指定 Handler 的所有拦截器。
     *
     * @param interceptor Handler 实例
     */
    public static void unregisterAll(Interceptor interceptor) {
        String handlerName = interceptor.getClass().getSimpleName();
        List<String> keysToRemove = new ArrayList<>();
        for (Map.Entry<String, Interceptor> entry : INTERCEPTOR_MAP.entrySet()) {
            Interceptor v = entry.getValue();
            if (v != null && v.getClass().getSimpleName().equals(handlerName)) {
                keysToRemove.add(entry.getKey());
            }
        }
        for (String key : keysToRemove) {
            INTERCEPTOR_MAP.remove(key);
            syncRemoveRule(key);
        }
        log.debug("注销 {} 的所有拦截器，共 {} 个", handlerName, keysToRemove.size());
    }

    /**
     * 根据拦截键同步注销 transformer 规则。
     *
     * @param key 拦截键（className#methodName#pointKey）
     */
    private static void syncRemoveRule(String key) {
        String[] parts = key.split(KEY_SEPARATOR, -1);
        if (parts.length != 3) {
            return;
        }
        InterceptPoint point = InterceptPoint.of(parts[2]);
        if (point != null) {
            syncTransformerRule(parts[0], parts[1], point, false);
        }
    }

    /**
     * ASM 字节码插桩入口 — 被 SpyTransformer 通过 INVOKESTATIC 调用。
     *
     * @param className  目标类名
     * @param methodName 目标方法名
     * @param descriptor 方法描述符
     * @param pointKey   插桩点标识
     */
    public static void onIntercept(String className,
                                   String methodName,
                                   String descriptor,
                                   String pointKey) {
        InterceptPoint point = InterceptPoint.of(pointKey);
        if (point == null) {
            return;
        }

        String key = buildKey(className, methodName, pointKey);
        Interceptor interceptor = INTERCEPTOR_MAP.get(key);
        if (interceptor == null) {
            return;
        }

        try {
            InterceptContext ctx = InterceptContext.builder()
                    .className(className)
                    .methodName(methodName)
                    .descriptor(descriptor)
                    .point(point)
                    .timestamp(System.currentTimeMillis())
                    .build();
            interceptor.onIntercept(ctx);
        } catch (Exception e) {
            log.error("拦截器执行异常: {}", key, e);
        }

        // 链路追踪上下文维护
        if (point == InterceptPoint.ENTRY) {
            SpyContext spyCtx = new SpyContext(className, methodName, System.currentTimeMillis());
            CONTEXT.set(spyCtx);
        } else if (point == InterceptPoint.EXIT) {
            SpyContext spyCtx = CONTEXT.get();
            CONTEXT.remove();
            if (spyCtx != null) {
                long duration = System.currentTimeMillis() - spyCtx.startTime;
                log.trace("[Spy] {}.{}, duration={}ms", className, methodName, duration);
            }
        } else if (point == InterceptPoint.EXCEPTION) {
            SpyContext spyCtx = CONTEXT.get();
            CONTEXT.remove();
            if (spyCtx != null) {
                long duration = System.currentTimeMillis() - spyCtx.startTime;
                log.trace("[Spy] {}.{}, duration={}ms [EXCEPTION]", className, methodName, duration);
            }
        }

        TRANSFORM_COUNT.set(TRANSFORM_COUNT.get() + 1);
    }

    /**
     * 方法入口插桩 — 直接入口。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param descriptor 描述符
     * @param pointKey   插桩点
     */
    public static void onEntry(String className,
                               String methodName,
                               String descriptor,
                               String pointKey) {
        onIntercept(className, methodName, descriptor, pointKey);
    }

    /**
     * 方法出口插桩 — 直接入口。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param descriptor 描述符
     * @param pointKey   插桩点
     */
    public static void onExit(String className,
                              String methodName,
                              String descriptor,
                              String pointKey) {
        onIntercept(className, methodName, descriptor, pointKey);
    }

    /**
     * 异常出口插桩 — 由 SpyTransformer 在异常处理器中调用。
     *
     * <p>className 传入的可能是点分隔的全限定名，统一转换为内部名参与路由。</p>
     *
     * @param className  类名（点分隔或内部名均可）
     * @param methodName 方法名
     * @param throwable  异常对象
     */
    public static void onException(String className,
                                   String methodName,
                                   Throwable throwable) {
        String internalName = className.replace('.', '/');
        InterceptPoint point = InterceptPoint.EXCEPTION;
        String key = buildKey(internalName, methodName, point.getKey());
        Interceptor interceptor = INTERCEPTOR_MAP.get(key);
        if (interceptor == null) {
            return;
        }
        try {
            InterceptContext ctx = InterceptContext.builder()
                    .className(internalName)
                    .methodName(methodName)
                    .descriptor("")
                    .point(point)
                    .timestamp(System.currentTimeMillis())
                    .throwable(throwable)
                    .build();
            interceptor.onIntercept(ctx);
        } catch (Exception e) {
            log.error("异常拦截器执行异常: {}", key, e);
        }
    }

    /**
     * 构建拦截键。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param pointKey   插桩点 key
     * @return 拦截键
     */
    private static String buildKey(String className, String methodName, String pointKey) {
        return className + KEY_SEPARATOR + methodName + KEY_SEPARATOR + pointKey;
    }

    /**
     * 获取当前插桩上下文。
     *
     * @return SpyContext
     */
    public static SpyContext currentContext() {
        return CONTEXT.get();
    }

    /**
     * 清除当前插桩上下文。
     */
    public static void clearContext() {
        CONTEXT.remove();
    }

    /**
     * 获取插桩总次数。
     *
     * @return 次数
     */
    public static int getInterceptCount() {
        return TRANSFORM_COUNT.get();
    }

    /**
     * 获取已注册的拦截器数量。
     *
     * @return 数量
     */
    public static int getRegisteredCount() {
        return INTERCEPTOR_MAP.size();
    }

    /**
     * 清除所有拦截器注册。
     */
    public static void clear() {
        INTERCEPTOR_MAP.clear();
        CONTEXT.remove();
        TRANSFORM_COUNT.remove();
        log.info("RuntimeSpy 已清除");
    }

    /**
     * 插桩上下文。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param startTime  开始时间
     * @author CH
     * @since 4.0.0.42
     */
    public record SpyContext(
            String className,
            String methodName,
            long startTime
    ) {
    }

    /**
     * 拦截器接口 — Handler 实现此接口接收插桩事件。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public interface Interceptor {

        /**
         * 接收插桩事件。
         *
         * @param context 插桩上下文
         */
        void onIntercept(InterceptContext context);
    }
}