package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.collapse.CollapseBatchFunction;
import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseExecutor;
import com.chua.common.support.concurrent.collapse.CollapseExecutorFactory;
import com.chua.common.support.concurrent.collapse.CollapseResultMapper;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.spring.support.annotation.Collapsible;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 折叠拦截器，处理 {@link Collapsible} 注解标注的方法。
 *
 * <p><b>v2 语义（按返回类型自适应）</b>：</p>
 * <ul>
 *   <li>入参契约为"恰好一个 Collection"，不满足时抛出 {@link IllegalStateException}；</li>
 *   <li>返回 {@code Map}（key = 入参元素）：{@code mergeAll} 整批合并——窗口内全部调用的
 *       集合实参取并集，调用一次核心方法，再按"元素归属"将结果拆分为子 Map 回填各调用者；</li>
 *   <li>返回非 {@code Map}：降级为同参折叠（相同实参合并执行一次并广播结果）；</li>
 *   <li>空集合实参 / 无 SPI 实现：直接执行不折叠。</li>
 * </ul>
 *
 * <p>合并执行核心方法时以 {@link ThreadLocal} 标记防重入，避免代理链递归再次进入折叠。</p>
 *
 * @author CH
 * @since 2026/09/03
 */
@Slf4j
@Spi("com.chua.spring.support.annotation.Collapsible")
public class CollapsibleIntercept
        implements MethodAnnotationIntercept<Collapsible>, DisposableBean, ApplicationContextAware {

    /**
     * 折叠执行器工厂的 SPI 名称
     */
    private static final String DEFAULT_FACTORY_NAME = "collapse";

    /**
     * 按执行器名称缓存折叠执行器
     */
    private final Map<String, CollapseExecutor<InvocationKey, Object>> executors = new ConcurrentHashMap<>(16);

    /**
     * 正在合并执行核心方法的方法集合（防递归重入）
     */
    private final ThreadLocal<Set<Method>> collapsingMethods = ThreadLocal.withInitial(HashSet::new);

    @Override
    public Class<Collapsible> annotationType() {
        return Collapsible.class;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Object intercept(Collapsible annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        Method method = proxyMethod.getMethod();
        Object[] args = proxyMethod.getArgs();
        if (args.length != 1 || !(args[0] instanceof Collection)) {
            throw new IllegalStateException("@Collapsible 仅支持恰好一个 Collection 入参的方法：" + method);
        }
        // 合并执行核心方法时再次进入本拦截器（代理链重入），直接透传真实调用
        if (isCollapsing(method)) {
            return invocation.proceed();
        }
        // 空集合调用无折叠价值，直接执行
        if (((Collection<?>) args[0]).isEmpty()) {
            return invocation.proceed();
        }
        CollapseExecutorFactory factory = ServiceProvider.of(CollapseExecutorFactory.class).getExtension(DEFAULT_FACTORY_NAME);
        if (factory == null) {
            log.warn("未找到折叠执行器工厂 SPI[collapse]，请引入 utils-support-collapse-starter，本次调用不折叠直接执行。");
            return invocation.proceed();
        }
        String name = resolveName(annotation, proxyMethod);
        CollapseExecutor<InvocationKey, Object> executor = executors.computeIfAbsent(name,
                key -> createExecutor(name, annotation, proxyMethod, factory));
        try {
            return executor.execute(new InvocationKey(method, (Collection<?>) args[0], proxyMethod, invocation,
                    resolveKeyExtractor(annotation)));
        } catch (Throwable throwable) {
            return resolveFallback(annotation, proxyMethod, throwable);
        }
    }

    /**
     * 折叠执行失败时的降级：注解 fallback 非空时调用降级方法（支持 bean#method 与同类方法名）。
     *
     * <p>降级方法返回值非 null 视为降级成功；降级不可用（无 fallback 配置/Bean 或方法不存在/
     * 降级方法返回 null）时抛出原始异常（Error 不包装直接抛出）。</p>
     *
     * @param annotation  折叠注解
     * @param proxyMethod 被拦截方法信息
     * @param cause       折叠执行异常
     * @return 降级结果
     */
    private Object resolveFallback(Collapsible annotation, ProxyMethod proxyMethod, Throwable cause) {
        String fallback = annotation.fallback();
        if (fallback == null || fallback.isBlank()) {
            throw collapseException(cause);
        }
        Object fallbackResult = FallbackResolver.resolve(fallback, proxyMethod);
        if (fallbackResult != null) {
            log.warn("折叠执行失败，已降级处理: {}", cause.getMessage());
            return fallbackResult;
        }
        throw collapseException(cause);
    }

    /**
     * 将折叠执行异常包装为可抛出形态（Error 原样抛出，异常包装为 RuntimeException）。
     *
     * @param cause 原始异常
     * @return 可抛出的运行时异常
     */
    private static RuntimeException collapseException(Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException("折叠执行失败", cause);
    }

    /**
     * Spring 容器装配回调：注册全局降级容器（线程无关），供并发线程解析 bean#method 降级。
     *
     * @param applicationContext Spring 容器
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        FallbackResolver.registerApplicationContext(applicationContext);
    }

    /**
     * 全局默认折叠配置（可空）：注解属性未显式指定时生效（Spring Boot 自动装配注入）。
     */
    private final CollapseConfig globalDefaults;

    /**
     * 创建折叠拦截器（无全局默认配置）。
     */
    public CollapsibleIntercept() {
        this(null);
    }

    /**
     * 创建折叠拦截器。
     *
     * @param globalDefaults 全局默认折叠配置（可空；注解属性未显式指定时生效）
     */
    public CollapsibleIntercept(CollapseConfig globalDefaults) {
        this.globalDefaults = globalDefaults;
    }

    /**
     * 解析批量收集阈值：注解显式值优先；未指定时取全局默认，再取内置默认 10。
     *
     * @param annotation 折叠注解
     * @return 批量收集阈值
     */
    private int resolveWaitThreshold(Collapsible annotation) {
        int threshold = annotation.waitThreshold();
        if (threshold >= 0) {
            return threshold;
        }
        if (globalDefaults != null && globalDefaults.getWaitThreshold() > 0) {
            return globalDefaults.getWaitThreshold();
        }
        return 10;
    }

    /**
     * 解析补收等待时间：注解显式值（含 -1 立即执行）优先；未指定（-2）时取全局默认，再取内置默认 0。
     *
     * @param annotation 折叠注解
     * @return 补收等待时间（毫秒）
     */
    private long resolveCollectingWaitTime(Collapsible annotation) {
        long waitTime = annotation.collectingWaitTime();
        if (waitTime != -2) {
            return waitTime;
        }
        if (globalDefaults != null) {
            return globalDefaults.getCollectingWaitTime();
        }
        return 0;
    }

    /**
     * 解析执行器名称：未显式声明时使用 目标用户类全限定名.方法名。
     *
     * <p>目标对象为 AOP 代理时经 {@link ClassUtils#getUserClass(Class)} 还原为用户类，
     * 避免 CGLIB 代理类名（含 {@code $$SpringCGLIB$$} 后缀）污染执行器名称。</p>
     *
     * @param annotation  折叠注解
     * @param proxyMethod 被拦截方法信息
     * @return 执行器名称
     */
    private String resolveName(Collapsible annotation, ProxyMethod proxyMethod) {
        String name = annotation.name();
        if (name == null || name.isBlank()) {
            Method method = proxyMethod.getMethod();
            Object target = proxyMethod.getTarget();
            Class<?> type = null;
            if (target != null) {
                Class<?> userClass = ClassUtils.getUserClass(target);
                // JDK 动态代理类无法还原真实用户类（非 CGLIB 命名），回退方法声明类
                if (!Proxy.isProxyClass(userClass)) {
                    type = userClass;
                }
            }
            if (type == null) {
                type = method.getDeclaringClass();
            }
            return type.getName() + "." + method.getName();
        }
        return name;
    }

    /**
     * 创建折叠执行器：返回 Map 走合并拆分模式，其余走同参折叠（降级）模式。
     *
     * @param name        执行器名称
     * @param annotation  折叠注解
     * @param proxyMethod 被拦截方法信息
     * @param factory     折叠执行器工厂
     * @return 折叠执行器实例
     */
    private CollapseExecutor<InvocationKey, Object> createExecutor(String name,
                                                                   Collapsible annotation,
                                                                   ProxyMethod proxyMethod,
                                                                   CollapseExecutorFactory factory) {
        CollapseConfig config = new CollapseConfig();
        config.setName(name);
        config.setWaitThreshold(resolveWaitThreshold(annotation));
        config.setCollectingWaitTime(resolveCollectingWaitTime(annotation));
        Class<?> returnType = proxyMethod.getMethod().getReturnType();
        if (Map.class.isAssignableFrom(returnType)) {
            // v2：整批合并执行一次 + 按元素归属拆分回填
            config.setMergeAll(true);
            return factory.create(config, (CollapseResultMapper<InvocationKey, Object>) this::mergeAndSplit);
        }
        // 降级：同参折叠（相同实参并发调用合并一次，结果广播）
        return factory.create(config, (CollapseBatchFunction<InvocationKey, Object>) this::collapseSame);
    }

    /**
     * v2 合并拆分：并集调用一次核心方法，按"元素归属"拆分子结果回填各调用者。
     *
     * @param inputs 整批调用者
     * @return 调用者到其子结果的映射
     * @throws Throwable 合并执行异常
     */
    private Map<InvocationKey, Object> mergeAndSplit(Collection<InvocationKey> inputs) throws Throwable {
        InvocationKey first = inputs.iterator().next();
        Method method = first.method;
        for (InvocationKey key : inputs) {
            if (!method.equals(key.method)) {
                throw new IllegalStateException("同一折叠执行器混入了不同方法：" + key.method);
            }
        }
        // 按归约键去重并集与归属索引（key() SpEL 提取；缺省时元素自身即键，兼容 Map 返回模式）
        Map<Object, Object> unionElements = new LinkedHashMap<>();
        Map<Object, List<InvocationKey>> owners = new LinkedHashMap<>();
        for (InvocationKey key : inputs) {
            for (Object element : key.collectionArgs) {
                Object collapseKey = key.keyExtractor.apply(element);
                unionElements.putIfAbsent(collapseKey, element);
                owners.computeIfAbsent(collapseKey, ignored -> new ArrayList<>(2)).add(key);
            }
        }
        // 一次核心方法调用（实参 = 按归约键去重后的元素集合）
        Object mergedArg = newCollectionArg(method.getParameterTypes()[0], unionElements.values());
        Object rawResult = invokeCore(first, mergedArg);
        if (!(rawResult instanceof Map)) {
            throw new IllegalStateException("合并拆分模式要求方法返回 Map：" + method);
        }
        Map<?, ?> full = (Map<?, ?>) rawResult;
        // 按归属拆分回填（子 Map 键 = 调用者元素的归约键）
        Map<InvocationKey, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, List<InvocationKey>> entry : owners.entrySet()) {
            Object collapseKey = entry.getKey();
            if (!full.containsKey(collapseKey)) {
                continue;
            }
            for (InvocationKey owner : entry.getValue()) {
                Map<Object, Object> sub = (Map<Object, Object>) result.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
                sub.put(collapseKey, full.get(collapseKey));
            }
        }
        return result;
    }

    /**
     * 按方法入参声明类型实例化合并后的集合实参（Set 参数使用 LinkedHashSet，其余使用 ArrayList）。
     *
     * @param parameterType 方法入参类型
     * @param union         元素并集
     * @return 合并后的集合实参
     */
    private static Object newCollectionArg(Class<?> parameterType, Collection<?> union) {
        if (Set.class.isAssignableFrom(parameterType)) {
            return new LinkedHashSet<>(union);
        }
        return new ArrayList<>(union);
    }

    /**
     * 执行一次核心方法（带防重入标记）。
     *
     * @param key       调用载体（目标对象与方法）
     * @param mergedArg 合并后的实参
     * @return 核心方法返回的全量结果
     * @throws Throwable 核心方法异常（解除包装）
     */
    private Object invokeCore(InvocationKey key, Object mergedArg) throws Throwable {
        Method method = key.method;
        Set<Method> methods = collapsingMethods.get();
        methods.add(method);
        try {
            return method.invoke(key.proxyMethod.getTarget(), mergedArg);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } finally {
            methods.remove(method);
        }
    }

    /**
     * 当前线程是否正在合并执行该方法（防代理链递归）。
     *
     * @param method 目标方法
     * @return 正在执行返回 true
     */
    private boolean isCollapsing(Method method) {
        return collapsingMethods.get().contains(method);
    }

    /**
     * 同参折叠（降级模式）：组内实参相同，执行一次并广播结果。
     *
     * @param inputs 同参调用组
     * @return 执行结果
     * @throws Throwable 执行异常
     */
    private Object collapseSame(Collection<InvocationKey> inputs) throws Throwable {
        InvocationKey first = inputs.iterator().next();
        return first.invocation.proceed();
    }

    @Override
    public void destroy() {
        for (CollapseExecutor<InvocationKey, Object> executor : executors.values()) {
            try {
                executor.close();
            } catch (Exception e) {
                log.warn("关闭折叠执行器失败：{}", e.getMessage());
            }
        }
        executors.clear();
        collapsingMethods.remove();
    }

    /**
     * SpEL 表达式解析器（线程安全可复用）
     */
    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();

    /**
     * key() SpEL 表达式缓存（表达式字符串 -> 编译后表达式）
     */
    private static final Map<String, Expression> SPEL_CACHE = new ConcurrentHashMap<>();

    /**
     * 解析元素归约键提取器：注解 key() 非空时按 SpEL 从元素求值，否则元素自身即键。
     *
     * @param annotation 折叠注解
     * @return 元素 -> 归约键 提取函数
     */
    private static Function<Object, Object> resolveKeyExtractor(Collapsible annotation) {
        String key = annotation.key();
        if (key == null || key.isBlank()) {
            return Function.identity();
        }
        Expression expression = SPEL_CACHE.computeIfAbsent(key, SPEL_PARSER::parseExpression);
        return element -> {
            StandardEvaluationContext context = new StandardEvaluationContext(element);
            return expression.getValue(context);
        };
    }

    /**
     * 折叠调用标识：方法 + 实参集合，equals/hashCode 不包含调用上下文。
     *
     * <p>同参折叠模式下 equals 决定合并分组；合并拆分模式下整批执行不依赖 equals。</p>
     */
    private static final class InvocationKey {

        /**
         * 目标方法
         */
        private final Method method;

        /**
         * 调用者的集合实参（原对象）
         */
        private final Collection<?> collectionArgs;

        /**
         * 调用上下文载体（目标对象/方法/实参）
         */
        private final ProxyMethod proxyMethod;

        /**
         * 调用上下文（proceed 载体，不参与相等比较）
         */
        private final MethodInvocation invocation;

        /**
         * 元素归约键提取器（key() SpEL；缺省为元素自身，不参与相等比较）
         */
        private final Function<Object, Object> keyExtractor;

        private InvocationKey(Method method,
                              Collection<?> collectionArgs,
                              ProxyMethod proxyMethod,
                              MethodInvocation invocation,
                              Function<Object, Object> keyExtractor) {
            this.method = method;
            this.collectionArgs = collectionArgs;
            this.proxyMethod = proxyMethod;
            this.invocation = invocation;
            this.keyExtractor = keyExtractor;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof InvocationKey)) {
                return false;
            }
            InvocationKey that = (InvocationKey) obj;
            return Objects.equals(method, that.method)
                    && Objects.equals(collectionArgs, that.collectionArgs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(method, collectionArgs);
        }
    }
}
