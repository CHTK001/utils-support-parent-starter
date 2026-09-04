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
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
        implements MethodAnnotationIntercept<Collapsible>, DisposableBean {

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
        return executor.execute(new InvocationKey(method, (Collection<?>) args[0], proxyMethod, invocation));
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
            Class<?> type = target != null ? ClassUtils.getUserClass(target) : method.getDeclaringClass();
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
        config.setWaitThreshold(annotation.waitThreshold());
        config.setCollectingWaitTime(annotation.collectingWaitTime());
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
        // 并集与元素归属索引（同一元素可被多个调用者请求）
        Set<Object> union = new LinkedHashSet<>();
        Map<Object, List<InvocationKey>> owners = new LinkedHashMap<>();
        for (InvocationKey key : inputs) {
            for (Object element : key.collectionArgs) {
                union.add(element);
                owners.computeIfAbsent(element, ignored -> new ArrayList<>(2)).add(key);
            }
        }
        // 一次核心方法调用（实参 = 并集）
        Object mergedArg = newCollectionArg(method.getParameterTypes()[0], union);
        Object rawResult = invokeCore(first, mergedArg);
        if (!(rawResult instanceof Map)) {
            throw new IllegalStateException("合并拆分模式要求方法返回 Map：" + method);
        }
        Map<?, ?> full = (Map<?, ?>) rawResult;
        // 按归属拆分回填
        Map<InvocationKey, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, List<InvocationKey>> entry : owners.entrySet()) {
            Object element = entry.getKey();
            if (!full.containsKey(element)) {
                continue;
            }
            for (InvocationKey owner : entry.getValue()) {
                Map<Object, Object> sub = (Map<Object, Object>) result.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
                sub.put(element, full.get(element));
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

        private InvocationKey(Method method,
                              Collection<?> collectionArgs,
                              ProxyMethod proxyMethod,
                              MethodInvocation invocation) {
            this.method = method;
            this.collectionArgs = collectionArgs;
            this.proxyMethod = proxyMethod;
            this.invocation = invocation;
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
