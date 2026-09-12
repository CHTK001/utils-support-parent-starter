package com.chua.common.support.proxy;

import com.chua.common.support.constant.ValueConstant;
import com.chua.common.support.proxy.annotation.Around;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.describe.MethodDescribe;
import com.chua.common.support.proxy.intercept.MethodArroundIntercept;
import com.chua.common.support.proxy.intercept.MethodIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.proxy.intercept.VoidMethodIntercept;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.ArrayUtils;
import com.chua.common.support.utils.MatchUtils;
import lombok.Getter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
* 默认代理提供者实现，采用建造者模式构建代理对象。
* <p>
* 本类实现了 {@link ProxyProvider} 接口，提供了完整的代理构建流程，
* 包括类加载器设置、接口配置、方法拦截器注册、注解扫描、环绕拦截等功能。
* 内部通过组合拦截器模式将用户自定义拦截器、注解扫描拦截器和环绕拦截器
* 整合为统一的拦截链。
* </p>
* <p>
* 代理构建流程：
* </p>
* <ol>
*   <li>配置阶段 — 通过链式方法配置类加载器、接口、目标对象、拦截器等参数</li>
*   <li>组合拦截器 — 通过 {@link #createCompositeIntercept(MethodIntercept)} 将多种拦截器整合为调用链</li>
*   <li>代理创建 — 根据目标类型选择 JDK 动态代理或 Javassist 代理</li>
* </ol>
* <p>
* 拦截器执行顺序：
* </p>
* <pre>{@code
* before(obj, method, args, proxy)           ← 前置处理
*   └── invoke(obj, method, args, proxy)     ← 方法调用
*         ├── 注解拦截器链（按注解类型匹配，按 SPI 注解全名查找）
*         ├── 环绕拦截器链（按方法签名匹配）
*         └── 用户自定义拦截器
* after(obj, method, args, proxy)            ← 后置处理（finally 中执行）
* handleException(...)                       ← 异常处理
* }</pre>...)                       ← 异常处理
* }</pre>
* <p>
* 注解扫描 SPI 约定：
* </p>
* <p>{@link MethodAnnotationIntercept} 实现类必须使用 {@code @Spi("被拦截注解的全限定类名")}
* 标记，代理框架在运行时按方法上注解的全类名去 SPI 注册表中查找拦截器，
* 确保实现类可放置在任意包而无需被同包扫描器发现。</p>
*
* @param <T> 代理接口类型
* @author CH
* @since 2025/11/26
* @版本 1.1.0
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class DefaultProxyProvider<T> implements ProxyProvider<T> {

    /**
    * 目标接口类型。
     */
    private final Class<T> type;

    /**
    * 类加载器。
     */
    private ClassLoader classLoader;

    /**
    * 要代理的额外接口。
     */
    private Class<?>[] interfaces = ValueConstant.SYMBOL_EMPTY_CLASS;

    /**
    * 方法拦截器。
     */
    private MethodIntercept<T> methodIntercept = new VoidMethodIntercept<>();

    /**
    * 是否启用注解扫描。
     */
    private boolean enableAnnotationScan = true;

    /**
    * 是否启用环绕拦截。
     */
    private boolean enableArround = true;

    /**
    * 是否优先尝试 ASM 代理。
     */
    private boolean tryAsm = true;

    /**
    * 是否使用 Javassist 代理。
     */
    private boolean tryJavassist = true;

    /**
    * 目标对象（用于委托调用）。
     */
    private Object target;

    /**
    * 对象上下文。
    * <p>
    * 用于与 IOC 容器集成，提供 Bean 的查找和注入能力。
    * </p>
     */
    @Getter
    /** 对象上下文 */
    private ObjectContext objectContext;

    /**
    * 注解拦截器缓存（注解全名 -> 拦截器列表）。
    * <p>
    * 按需懒加载，避免每次方法调用重复扫描 SPI。
    * 使用 volatile + 双重检查锁保证线程安全。
    * </p>
     */
    private volatile Map<String, List<MethodAnnotationIntercept<Annotation>>> annotationInterceptCache;

    /**
    * 注解拦截器缓存初始化的锁标志。
     */
    private volatile boolean annotationCacheInit = false;

    /**
    * 环绕处理器缓存（懒加载）。
     */
    private volatile List<ArroundHandler> aroundHandlers;

    /**
    * SPI 提供者实例（懒加载）。
     */
    private volatile ServiceProvider<MethodAnnotationIntercept> annotationSpiProvider;

    /**
    * SPI 提供者实例（懒加载）。
     */
    private volatile ServiceProvider<MethodArroundIntercept> aroundSpiProvider;

    /**
    * 创建默认代理提供者实例。
    *
    * @param type 目标接口类型，不能为 空
     */
    DefaultProxyProvider(Class<T> type) {
        this.type = type;
    }

    /**
    * 设置类加载器。
    *
    * @param classLoader 类加载器
    * @return 当前代理提供者实例
     */
    @Override
    public ProxyProvider<T> classLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        return this;
    }

    /**
    * 设置要代理的额外接口。
    *
    * @param interfaces 要代理的额外接口数组
    * @return 当前代理提供者实例
     */
    @Override
    public ProxyProvider<T> interfaces(Class<?>... interfaces) {
        if (interfaces == null || interfaces.length == 0) {
            this.interfaces = ValueConstant.SYMBOL_EMPTY_CLASS;
        } else {
            this.interfaces = interfaces;
        }
        return this;
    }

    /**
    * 设置目标对象。
    *
    * @param target 目标对象实例
    * @return 当前代理提供者实例
     */
    @Override
    public ProxyProvider<T> target(Object target) {
        this.target = target;
        return this;
    }

    /**
    * 设置是否启用注解扫描。
    *
    * @param enable 是否启用注解扫描
    * @return 当前代理提供者实例
     */
    @Override
    public ProxyProvider<T> enableAnnotationScan(boolean enable) {
        this.enableAnnotationScan = enable;
        return this;
    }

    /**
    * 设置是否启用环绕拦截。
    *
    * @param enable 是否启用环绕拦截
    * @return 当前代理提供者实例
     */
    @Override
    public ProxyProvider<T> enableArround(boolean enable) {
        this.enableArround = enable;
        return this;
    }

    @Override
    /** 尝试asm */
    public ProxyProvider<T> tryAsm(boolean enable) {
        this.tryAsm = enable;
        return this;
    }

    @Override
    /** 尝试javassist */
    public ProxyProvider<T> tryJavassist(boolean enable) {
        this.tryJavassist = enable;
        return this;
    }

    /**
    * 设置对象上下文。
    *
    * @param objectContext 对象上下文实例
    * @return 当前代理提供者实例
     */
    @Override
    public ProxyProvider<T> objectContext(ObjectContext objectContext) {
        this.objectContext = objectContext;
        return this;
    }

    /**
    * 设置方法拦截器。
    *
    * @param methodIntercept 方法拦截器实例
    * @return 当前代理提供者实例
     */
    @Override
    public ProxyProvider<T> methodIntercept(MethodIntercept<T> methodIntercept) {
        this.methodIntercept = Optional.ofNullable(methodIntercept).orElse(new VoidMethodIntercept<>());
        return this;
    }

    /**
    * 构建代理对象。
    *
    * @return 代理对象实例
     */
    @Override
    public T build() {
        MethodIntercept<T> delegate = Optional.ofNullable(methodIntercept).orElse(new VoidMethodIntercept<>());
        MethodIntercept<T> finalIntercept = createCompositeIntercept(delegate);

        ClassLoader loader = Optional.ofNullable(classLoader).orElseGet(type::getClassLoader);
        Class<?>[] ifaces = Optional.ofNullable(interfaces).orElse(ValueConstant.SYMBOL_EMPTY_CLASS);

        if (tryAsm) {
            T proxy = tryAsm(type, ifaces, loader, finalIntercept);
            if (proxy != null) {
                return proxy;
            }
        }

        if (tryJavassist) {
            T proxy = tryJavassist(type, ifaces, loader, finalIntercept);
            if (proxy != null) {
                return proxy;
            }
        }

        Class<?>[] actual = ArrayUtils.merge(ifaces, type);
        return (T) JdkProxyFactory.INSTANCE.createProxy(type, actual, loader, finalIntercept);
    }
    /**
    * 尝试使用 ASM 创建代理对象。
    * <p>
    * 通过 SPI 机制查找名为 "asm" 的 {@link ProxyFactory} 扩展点，
    * 如果找到则调用其创建代理方法。若发生任何异常或工厂不存在，返回 空。
    * </p>
    *
    * @param type     目标接口类型
    * @param ifaces   要代理的额外接口数组
    * @param loader   类加载器
    * @param intercept 组合后的方法拦截器
    * @return ASM 创建的代理对象，如果失败则返回 空
     */
    private T tryAsm(Class<T> type, Class<?>[] ifaces, ClassLoader loader, MethodIntercept<T> intercept) {
        try {
            ProxyFactory<?> factory = ServiceProvider.of(ProxyFactory.class).getExtension("asm");
            if (factory != null) {
                return (T) factory.createProxy((Class) type, ifaces, loader, (MethodIntercept) intercept);
            }
        } catch (Exception ignored) {
            // 捕获所有异常并忽略，确保不影响后续代理方式的尝试
        }
        return null;
    }

    /**
    * 尝试使用 Javassist 创建代理对象。
    * <p>
    * 通过 SPI 机制查找名为 "javassist" 的 {@link ProxyFactory} 扩展点，
    * 如果找到则调用其创建代理方法。若发生任何异常或工厂不存在，返回 空。
    * </p>
    *
    * @param type     目标接口类型
    * @param ifaces   要代理的额外接口数组
    * @param loader   类加载器
    * @param intercept 组合后的方法拦截器
    * @return Javassist 创建的代理对象，如果失败则返回 空
     */
    private T tryJavassist(Class<T> type, Class<?>[] ifaces, ClassLoader loader, MethodIntercept<T> intercept) {
        try {
            ProxyFactory<?> factory = ServiceProvider.of(ProxyFactory.class).getExtension("javassist");
            if (factory != null) {
                return (T) factory.createProxy((Class) type, ifaces, loader, (MethodIntercept) intercept);
            }
        } catch (Exception ignored) {
            // 捕获所有异常并忽略，确保不影响后续代理方式的尝试
        }
        return null;
    }

    /**
    * 创建组合拦截器，整合注解扫描和环绕拦截功能。
    *
    * @param delegate 用户自定义的委托拦截器
    * @return 组合后的完整拦截器
     */
    private MethodIntercept<T> createCompositeIntercept(MethodIntercept<T> delegate) {
        if (!enableAnnotationScan && !enableArround) {
            return delegate;
        }
        return new MethodIntercept<>() {
            @Override
            /** 之前 */
            public void before(Object obj, Method method, Object[] args, T proxy) {
                delegate.before(obj, method, args, proxy);
            }

            @Override
            /** 调用 */
            public Object invoke(Object obj, Method method, Object[] args, T proxy) throws Throwable {
                ProxyMethod proxyMethod = ProxyMethod.builder()
                        .args(args)
                        .method(method)
                        .proxy(proxy)
                        .target(target != null ? target : obj)
                        .methodDescribe(new MethodDescribe(target != null ? target : obj, method))
                        .objectContext(objectContext)
                        .build();

                MethodInvocation invocation = () -> delegate.invoke(obj, method, args, proxy);

                if (enableAnnotationScan) {
                    invocation = wrapAnnotations(method, proxyMethod, invocation);
                }

                if (enableArround) {
                    invocation = wrapAround(method, proxyMethod, invocation);
                }

                return invocation.proceed();
            }

            @Override
            /** 之后 */
            public void after(Object obj, Method method, Object[] args, T proxy) {
                delegate.after(obj, method, args, proxy);
            }

            @Override
            /** 处理异常 */
            public Object handleException(Object obj, Method method, Object[] args, T proxy, Throwable throwable) {
                return delegate.handleException(obj, method, args, proxy, throwable);
            }
        };
    }

    /**
    * 包装注解拦截器。
    * <p>
    * 遍历方法上的所有注解，按注解全限定名从 SPI 注册表中查找
    * {@link MethodAnnotationIntercept} 实现，匹配到的拦截器按 order 优先级
    * 包装成洋葱调用链。
    * </p>
    *
    * @param method      被调用的方法
    * @param proxyMethod 代理方法的封装信息
    * @param next        下一个调用环节
    * @return 包装后的调用链
     */
    private MethodInvocation wrapAnnotations(Method method, ProxyMethod proxyMethod, MethodInvocation next) {
        Annotation[] annotations = method.getAnnotations();
        if (annotations.length == 0) {
            return next;
        }

        MethodInvocation invocation = next;
        for (Annotation annotation : annotations) {
            String annotationTypeName = annotation.annotationType().getName();
            List<MethodAnnotationIntercept<Annotation>> matched = findAnnotationIntercepts(annotationTypeName);
            if (matched.isEmpty()) {
                continue;
            }

 // 按 订单 升序组成洋葱链：数值越小越靠外层执行
            List<MethodAnnotationIntercept<Annotation>> sorted = new ArrayList<>(matched);
            sorted.sort(Comparator.comparingInt(MethodAnnotationIntercept::order));

            for (int i = sorted.size() - 1; i >= 0; i--) {
                MethodAnnotationIntercept<Annotation> handler = sorted.get(i);
                MethodInvocation currentNext = invocation;
                invocation = () -> handler.intercept(annotation, proxyMethod, currentNext);
            }
        }
        return invocation;
    }

    /**
    * 根据注解全名查找匹配的拦截器列表。
    * <p>
    * 先从缓存中查找，缓存不存在则通过 SPI 按名称 {@code annotationTypeName} 查找，
    * 找到后提取 订单 排序并缓存。查找结果也通过 {@code annotationType()} 做二次校验确认。
    * </p>
    *
    * @param annotationTypeName 注解全限定类名
    * @return 匹配的拦截器列表，不会为 空
     */
    private List<MethodAnnotationIntercept<Annotation>> findAnnotationIntercepts(String annotationTypeName) {
        if (!enableAnnotationScan) {
            return List.of();
        }

        // 确保缓存已初始化
        if (!annotationCacheInit) {
            synchronized (this) {
                if (!annotationCacheInit) {
                    annotationInterceptCache = new ConcurrentHashMap<>();
                    annotationCacheInit = true;
                }
            }
        }

        // 命中缓存
        List<MethodAnnotationIntercept<Annotation>> cached = annotationInterceptCache.get(annotationTypeName);
        if (cached != null) {
            return cached;
        }

        // 未命中：通过 SPI 按注解全名查找
        ServiceProvider<MethodAnnotationIntercept> provider = getAnnotationSpiProvider();
        if (provider.isEmpty()) {
            annotationInterceptCache.put(annotationTypeName, List.of());
            return List.of();
        }

 // SPI 名称 在注册时被 转为大写大小写，需大写化后查找
        String spiName = annotationTypeName.toUpperCase(Locale.ROOT);
        List<MethodAnnotationIntercept> intercepts = provider.getNewExtensions(spiName);
        if (intercepts == null || intercepts.isEmpty()) {
            annotationInterceptCache.put(annotationTypeName, List.of());
            return List.of();
        }

        // 二次校验：确保每个拦截器的 annotationType() 与目标注解一致
        List<MethodAnnotationIntercept<Annotation>> valid = new ArrayList<>(intercepts.size());
        for (MethodAnnotationIntercept raw : intercepts) {
            if (raw == null) {
                continue;
            }
            try {
                Class<? extends Annotation> declaredType = raw.annotationType();
                if (declaredType != null && declaredType.getName().equals(annotationTypeName)) {
                    valid.add((MethodAnnotationIntercept<Annotation>) raw);
                }
            } catch (Exception ignored) {
                // 跳过校验失败的拦截器
            }
        }

        if (valid.isEmpty()) {
            annotationInterceptCache.put(annotationTypeName, List.of());
            return List.of();
        }

        // 缓存并返回
        List<MethodAnnotationIntercept<Annotation>> result = List.copyOf(valid);
        annotationInterceptCache.put(annotationTypeName, result);
        return result;
    }

    /**
    * 获取 SPI 提供者（注解拦截器），懒加载。
    *
    * @return ServiceProvider 实例
     */
    private ServiceProvider<MethodAnnotationIntercept> getAnnotationSpiProvider() {
        if (annotationSpiProvider == null) {
            synchronized (this) {
                if (annotationSpiProvider == null) {
                    annotationSpiProvider = ServiceProvider.of(MethodAnnotationIntercept.class);
                }
            }
        }
        return annotationSpiProvider;
    }

    /**
    * 包装环绕拦截器。
    * <p>
    * 通过 SPI 发现所有 {@link MethodArroundIntercept} 实现，
    * 按方法签名匹配后包装为洋葱调用链。
    * </p>
    *
    * @param method      被调用的方法
    * @param proxyMethod 代理方法的封装信息
    * @param next        下一个调用环节
    * @return 包装后的调用链
     */
    private MethodInvocation wrapAround(Method method, ProxyMethod proxyMethod, MethodInvocation next) {
        List<ArroundHandler> handlers = aroundHandlers();
        if (handlers.isEmpty()) {
            return next;
        }
        String signature = buildSignature(method);

        List<ArroundHandler> matched = new ArrayList<>();
        for (ArroundHandler handler : handlers) {
            if (handler.matches(signature, method)) {
                matched.add(handler);
            }
        }
        if (matched.isEmpty()) {
            return next;
        }

        matched.sort(Comparator.comparingInt(ArroundHandler::order));

        MethodInvocation invocation = next;
        for (int i = matched.size() - 1; i >= 0; i--) {
            ArroundHandler handler = matched.get(i);
            MethodInvocation currentNext = invocation;
            invocation = () -> handler.invoke(proxyMethod, currentNext);
        }
        return invocation;
    }

    /**
    * 构建方法签名。
    *
    * @param method 方法对象
    * @return 方法签名字符串
     */
    private String buildSignature(Method method) {
        StringBuilder builder = new StringBuilder(type.getName())
                .append('#')
                .append(method.getName())
                .append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(parameterTypes[i].getName());
        }
        return builder.append(')').toString();
    }

    /**
    * 获取环绕处理器列表（懒加载，线程安全）。
    * <p>
    * 通过 SPI 发现所有 {@link MethodArroundIntercept} 实现，
    * 解析 {@link Around} 注解配置后包装为 {@link ArroundHandler} 并按优先级排序后缓存。
    * </p>
    *
    * @return 环绕处理器列表
     */
    private List<ArroundHandler> aroundHandlers() {
        if (!enableArround) {
            return List.of();
        }
        if (aroundHandlers == null) {
            synchronized (this) {
                if (aroundHandlers == null) {
                    ServiceProvider<MethodArroundIntercept> provider = getAroundSpiProvider();
                    var intercepts = provider.collectNew();
                    aroundHandlers = intercepts.stream()
                            .map(ArroundHandler::of)
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparingInt(ArroundHandler::order))
                            .toList();
                }
            }
        }
        return aroundHandlers;
    }

    /**
    * 获取 SPI 提供者（环绕拦截器），懒加载。
    *
    * @return ServiceProvider 实例
     */
    private ServiceProvider<MethodArroundIntercept> getAroundSpiProvider() {
        if (aroundSpiProvider == null) {
            synchronized (this) {
                if (aroundSpiProvider == null) {
                    aroundSpiProvider = ServiceProvider.of(MethodArroundIntercept.class);
                }
            }
        }
        return aroundSpiProvider;
    }

    /**
    * 环绕处理器，封装了环绕拦截器的匹配和执行逻辑。
    * <p>
    * 将一个 {@link MethodArroundIntercept} 实例解析 {@link Around} 注解配置后
    * 包装为处理器，提供方法签名匹配（{@link #matches(String, Method)}）
    * 和执行（{@link #invoke(ProxyMethod, MethodInvocation)}）的统一接口。
    * </p>
    * @author CH
    * @since 4.0.0
     */
    private static class ArroundHandler {
        /** Intercept */
        private final MethodArroundIntercept intercept;
        /** 模式 */
        private final String[] patterns;
        /** 匹配类型 */
        private final MatchUtils.MatchType matchType;
        /** 排序 */
        private final int order;

        /**
        * 构造环绕处理器。
        *
        * @param intercept 环绕拦截器实例
        * @param patterns  方法签名匹配模式数组（空 或空数组表示全方法匹配）
        * @param matchType 匹配类型
        * @param order     执行顺序值
         */
        private ArroundHandler(MethodArroundIntercept intercept, String[] patterns,
                               MatchUtils.MatchType matchType, int order) {
            this.intercept = intercept;
            this.patterns = patterns;
            this.matchType = matchType;
            this.order = order;
        }

        /**
        * 从 {@link MethodArroundIntercept} 创建 arround处理器。
        * <p>
        * 解析拦截器类上的 {@link Around} 注解，提取方法签名匹配模式
        * 和执行顺序配置。如果注解缺失则跳过；{@code value} 为空数组时表示全方法匹配。
        * </p>
        *
        * @param intercept 环绕拦截器实例
        * @return ArroundHandler 实例，如果无法创建则返回 空
         */
        static ArroundHandler of(MethodArroundIntercept intercept) {
            Around mapping = intercept.getClass().getAnnotation(Around.class);
            if (mapping == null) {
                return null;
            }
            String[] patterns = mapping.value();
            if (patterns != null && patterns.length > 0) {
                patterns = Arrays.stream(patterns)
                        .filter(s -> s != null && !s.isEmpty())
                        .toArray(String[]::new);
            } else {
 // 值 为空数组或 空 表示全方法匹配
                patterns = null;
            }
 // 订单: 取 @Around.订单 与拦截器接口 订单 的较小值
            int aroundOrder = mapping.order();
            int interfaceOrder = intercept.order();
            int effectiveOrder = Math.min(aroundOrder, interfaceOrder);
            return new ArroundHandler(intercept, patterns, mapping.matchType(), effectiveOrder);
        }

        /**
        * 判断是否匹配指定的方法签名或方法名。
        * <p>
        * 当 模式 为 空（未指定匹配模式）时匹配所有方法。
        * </p>
        *
        * @param signature 方法签名
        * @param method    方法对象
        * @return 如果匹配则返回 true
         */
        boolean matches(String signature, Method method) {
 // 模式 为 空 表示全方法匹配
            if (patterns == null) {
                return true;
            }
            for (String pattern : patterns) {
                if (MatchUtils.isMatch(pattern, signature, matchType) ||
                        MatchUtils.isMatch(pattern, method.getName(), matchType)) {
                    return true;
                }
            }
            return false;
        }

        /**
        * 执行环绕拦截逻辑。
        *
        * @param proxyMethod 代理方法信息
        * @param invocation  下一个调用环节
        * @return 方法执行结果
        * @throws Throwable 如果执行过程中发生异常
         */
        Object invoke(ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
            return intercept.invoke(proxyMethod, invocation);
        }

        /**
        * 获取此处理器的执行顺序值。
        *
        * @return 执行顺序值
         */
        int order() {
            return order;
        }
    }
}
