// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.invoker;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.bean.BeanPath;
import com.chua.common.support.lang.placeholder.StringValuePropertyResolver;
import com.chua.common.support.network.annotations.RequestMethod;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.invoker.annotations.*;
import com.chua.common.support.network.invoker.filter.InjectCallback;
import com.chua.common.support.network.invoker.filter.InvocationContext;
import com.chua.common.support.network.invoker.filter.SharedInvocationContext;
import com.chua.common.support.network.ipc.annotations.IpcMethod;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import com.chua.common.support.reflection.ReflectUtils;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.chua.common.support.utils.ArrayUtils;

/**
 * 基于 IPC 协议的调用器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "ipc", order = 40)
public class IpcInvoker implements Invoker {

    private static final ConcurrentMap<Class<?>, Object> PROXY_CACHE = new ConcurrentHashMap<>();
    /**
     * 全局injectrules
     */
    private final List<SharedInvocationContext.InjectRule> globalInjectRules = Wraps.list();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> apiClass) {
        return (T) PROXY_CACHE.computeIfAbsent(apiClass, this::createProxy);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T createNew(Class<T> apiClass) {
        return (T) createProxy(apiClass);
    }

    /**
     * 创建Proxy。
     *
     * @param apiClass 方法入参 apiClass
     * @return 对象 对象
     */
    private <T> Object createProxy(Class<T> apiClass) {
        String baseUrl = resolveBaseUrl(apiClass);
        if (StringUtils.isEmpty(baseUrl)) {
            throw new IllegalArgumentException("接口 " + apiClass.getName() + " 缺少 baseUrl");
        }
        String namespace = resolveNamespace(apiClass);
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        List<ServerFilter> filters = ServiceProvider.of(ServerFilter.class).collect().stream()
                .sorted(Comparator.comparingInt(ServerFilter::getOrder))
                .toList();

        SharedInvocationContext shared = new SharedInvocationContext();
        for (SharedInvocationContext.InjectRule rule : globalInjectRules) {
            shared.addInjectRule(rule.target(), rule.callback());
        }

        return ReflectUtils.newProxy(
                apiClass.getClassLoader(),
                new Class<?>[]{apiClass},
                new IpcInvocationHandler(normalizedBase, namespace, filters, shared)
        );
    }

    /**
     * 解析BaseURL。
     *
     * @param clazz 类，不允许为 null
     * @return 结果字符串
     */
    private static String resolveBaseUrl(Class<?> clazz) {
        String[] classLevelAnnotations = {
                "org.springframework.web.bind.annotation.RequestMapping"
        };
        for (String annClass : classLevelAnnotations) {
            try {
                Class<?> cl = ReflectUtils.forName(annClass);
                Annotation ann = clazz.getAnnotation(cl.asSubclass(Annotation.class));
                if (ann != null) {
                    String v = extractAnnotationValue(ann);
                    if (!StringUtils.isEmpty(v)) {
                        return v;
                    }
                }
            } catch (Exception e) {
                log.debug("[ipc] 类注解解析失败，跳过: {}", e.getMessage());
            }
        }
        RequestMethod rm = clazz.getAnnotation(RequestMethod.class);
        if (rm != null && !StringUtils.isEmpty(rm.value())) {
            return rm.value();
        }
        InvokerService is = clazz.getAnnotation(InvokerService.class);
        if (is != null && !StringUtils.isEmpty(is.value())) {
            return is.value();
        }
        RemoteService rs = clazz.getAnnotation(RemoteService.class);
        if (rs != null && !StringUtils.isEmpty(rs.url())) {
            return rs.url();
        }
        return "";
    }

    /**
     * 解析Namespace。
     *
     * @param clazz 类，不允许为 null
     * @return 结果字符串
     */
    private static String resolveNamespace(Class<?> clazz) {
        IpcMethod im = clazz.getAnnotation(IpcMethod.class);
        if (im != null && !StringUtils.isEmpty(im.value())) {
            return im.value();
        }
        return "";
    }

    /**
     * 添加Inject
     * @param target target
     * @param callback callback
     */
    @Override
    public Invoker addInject(String target, InjectCallback callback) {
        globalInjectRules.add(new SharedInvocationContext.InjectRule(target, callback));
        return this;
    }

    /**
     * extractAnnotation值。
     *
     * @param ann 方法入参 ann
     * @return 结果字符串
     */
    private static String extractAnnotationValue(Annotation ann) {
        try {
Object r = ReflectUtils.invoke(ann, "value", Object.class, new Class<?>[0], new Object[0], new Object[0]);
            if (r instanceof String s) {
                return s;
            }
            if (r instanceof String[] a && a.length > 0) {
                return a[0];
            }
        } catch (Exception e) {
            log.debug("[ipc] 注解 value 取值失败，返回空串: {}", e.getMessage());
        }
        return "";
    }

    private static class IpcInvocationHandler implements InvocationHandler {

        /**
         * BaseURL
         */
        private final String baseUrl;
        /**
         * Namespace
         */
        private final String namespace;
        /**
         * 过滤器数组（按 order 排序后的快照，热路径免 List 取值）
         */
        private final ServerFilter[] filterArray;
        /**
         * 方法元数据缓存（键：Method；IpcMethod/RemoteMethod/RemoteHeader/RemoteInject
         * 注解运行期不可变，解析一次复用）
         */
        private final ConcurrentMap<Method, MethodMeta> methodCache = new ConcurrentHashMap<>();
        /**
         * Shared上下文
         */
        private final SharedInvocationContext sharedContext;
        /**
         * Property解析器
         */
        private final StringValuePropertyResolver propertyResolver = new StringValuePropertyResolver(null);

        IpcInvocationHandler(String baseUrl, String namespace, List<ServerFilter> filters,
                SharedInvocationContext sharedContext) {
            this.baseUrl = baseUrl;
            this.namespace = namespace;
            this.filterArray = filters.toArray(new ServerFilter[0]);
            this.sharedContext = sharedContext;
        }

        /**
         * 调用
         * @param proxy proxy
         * @param method method
         * @param args args
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                try {
                    return ReflectUtils.invoke(this, method.getName(), method.getReturnType(),
                                               method.getParameterTypes(), args);
                } catch (Exception e) {
                    return null;
                }
            }

            // 方法元数据（方法名/路径/URL/注解）解析一次并缓存，热路径免反射扫描
            MethodMeta meta = methodCache.computeIfAbsent(method, this::buildMethodMeta);

            InvocationContext ctx = new InvocationContext();
            ctx.setPath(meta.path);
            ctx.setAttribute("javaMethod", method);
            ctx.setAttribute("javaArgs", args);
            ctx.setAttribute("targetClass", method.getDeclaringClass());
            ctx.setAttribute("sharedContext", sharedContext);
            if (ArrayUtils.isNotEmpty(args)) {
                ctx.setBody(com.chua.common.support.lang.json.Json.toJson(args[0]));
            }

            sharedContext.applyTo(ctx);

            processRemoteHeaders(ctx, meta, args);

            if (filterArray.length > 0) {
                runFilterChain(ctx, filterArray);
            }

            processRemoteInject(ctx, meta, args);

            String body = ctx.getBody() != null
                    ? com.chua.common.support.lang.json.Json.toJson(ctx.getBody())
                    : "";

            var builder = HttpClientFactory.of(meta.url).json();
            ctx.forEachHeader(builder::header);
            String result = builder.body(body).post().getBodyString();
            ctx.setResult(result);
            return result;
        }

        /**
         * 方法级元数据：路径、URL 与方法/参数注解的解析期缓存。
         */
        private static final class MethodMeta {
            /**
             * IPC 请求路径（/ipc/[namespace/]methodName）
             */
            final String path;
            /**
             * 完整请求 URL（baseUrl + path）
             */
            final String url;
            /**
             * 方法级 @RemoteHeader 静态头
             */
            final RemoteHeader[] methodHeaders;
            /**
             * 形参列表
             */
            final Parameter[] parameters;
            /**
             * 参数级 @RemoteHeader（与 parameters 一一对应）
             */
            final RemoteHeader[] paramHeaders;
            /**
             * 方法级 @RemoteInject 注入规则
             */
            final RemoteInject[] injects;

            MethodMeta(String path, String url, RemoteHeader[] methodHeaders,
                       Parameter[] parameters, RemoteHeader[] paramHeaders, RemoteInject[] injects) {
                this.path = path;
                this.url = url;
                this.methodHeaders = methodHeaders;
                this.parameters = parameters;
                this.paramHeaders = paramHeaders;
                this.injects = injects;
            }
        }

        /**
         * 构建方法元数据（仅在 methodCache 未命中时调用一次）。
         *
         * @param method 接口方法
         * @return 方法元数据
         */
        private MethodMeta buildMethodMeta(Method method) {
            IpcMethod im = method.getAnnotation(IpcMethod.class);
            String methodName = im != null ? im.value() : resolveMethodName(method);
            String path = namespace.isEmpty() ? "/ipc/" + methodName : "/ipc/" + namespace + "/" + methodName;
            Parameter[] parameters = method.getParameters();
            RemoteHeader[] paramHeaders = new RemoteHeader[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                paramHeaders[i] = parameters[i].getAnnotation(RemoteHeader.class);
            }
            return new MethodMeta(path, baseUrl + path,
                    method.getAnnotationsByType(RemoteHeader.class),
                    parameters, paramHeaders,
                    method.getAnnotationsByType(RemoteInject.class));
        }

        /**
         * 处理RemoteHeaders
         * @param ctx ctx
         * @param method method
         * @param args args
         */
        private void processRemoteHeaders(InvocationContext ctx, MethodMeta meta, Object[] args) {
            for (RemoteHeader rh : meta.methodHeaders) {
                String value = resolvePlaceholders(rh.value());
                ctx.addHeader(rh.name(), value);
            }
            if (args != null) {
                for (int i = 0; i < meta.parameters.length; i++) {
                    RemoteHeader rh = meta.paramHeaders[i];
                    if (rh != null && args[i] != null) {
                        ctx.addHeader(rh.name(), args[i].toString());
                    }
                }
            }
        }

        /**
         * 处理RemoteInject
         * @param ctx ctx
         * @param method method
         * @param args args
         */
        private void processRemoteInject(InvocationContext ctx, MethodMeta meta, Object[] args) {
            RemoteInject[] injects = meta.injects;
            if (injects.length == 0) {
                return;
            }

            BeanPath beanPath = BeanPath.getInstance();
            if (beanPath == null) {
                return;
            }

            for (RemoteInject ri : injects) {
                Object sourceValue = resolveSource(ri.source(), ctx, args, beanPath);
                if (sourceValue == null) {
                    continue;
                }

                String value = sourceValue.toString();
                if (!StringUtils.isEmpty(ri.format())) {
                    value = ri.format().replace("{0}", value);
                }

                applyTarget(ri.target(), value, beanPath);
            }
        }

        /**
         * resolve Source
         * @param args 参数
         * @param beanPath 参数
         * @param ctx 参数
         * @param source 字符串
         * @return 返回值
        */
        private static Object resolveSource(String source, InvocationContext ctx, Object[] args, BeanPath beanPath) {
            if (source.startsWith("result.")) {
                return beanPath.getValue(ctx.getResult(), source.substring(7));
            }
            if (source.startsWith("args[")) {
                int end = source.indexOf(']');
                int idx = Integer.parseInt(source.substring(5, end));
                if (idx < 0 || idx >= (args != null ? args.length : 0)) {
                    return null;
                }
                String rest = source.substring(end + 1);
                if (rest.startsWith(".")) {
                    rest = rest.substring(1);
                }
                return rest.isEmpty() ? args[idx] : beanPath.getValue(args[idx], rest);
            }
            if (source.startsWith("attributes.")) {
                return ctx.getAttribute(source.substring(11));
            }
            return beanPath.getValue(ctx, source);
        }

        /**
         * 应用Target
         * @param target target
         * @param value value
         * @param beanPath beanPath
         */
        private void applyTarget(String target, String value, BeanPath beanPath) {
            if (target.startsWith("headers.")) {
                sharedContext.addDefaultHeader(target.substring(8), value);
            } else if (target.startsWith("attributes.")) {
                sharedContext.setAttribute(target.substring(11), value);
            }
        }

        /**
         * 解析Placeholders
         * @param text text
         * @return 返回值
        */
        private String resolvePlaceholders(String text) {
            if (StringUtils.isEmpty(text) || !text.contains("${")) {
                return text;
            }
            return propertyResolver.resolvePlaceholders(text);
        }

        /**
         * resolve Method Name
         * @param method 参数
         * @return 返回值
        */
        private static String resolveMethodName(Method method) {
            RemoteMethod rm = method.getAnnotation(RemoteMethod.class);
            if (rm != null && !StringUtils.isEmpty(rm.value())) {
                return rm.value();
            }
            return method.getName();
        }

        /**
         * run Filter Chain
         * @param ctx 参数
         * @param filters 参数
         * @return 返回值
        */
        private static void runFilterChain(InvocationContext ctx, ServerFilter[] filters) {
            try {
                new IpcFilterChain(filters).doFilter(ctx, ctx);
            } catch (Exception e) {
                throw new RuntimeException("Filter chain error", e);
            }
        }

        /**
         * IPC 过滤器链：数组 + 游标，避免每次调用创建捕获 List 的匿名类。
         */
        private static final class IpcFilterChain implements ServerFilterChain {

            /**
             * 按序排列的过滤器
             */
            private final ServerFilter[] filters;
            /**
             * 当前游标
             */
            private int index;

            IpcFilterChain(ServerFilter[] filters) {
                this.filters = filters;
            }

            /**
             * Do过滤
             * @param request request
             * @param response response
             */
            @Override
            public void doFilter(com.chua.common.support.network.server.request.ServerRequest request,
                                 com.chua.common.support.network.server.response.ServerResponse response) {
                if (index < filters.length) {
                    try {
                        filters[index++].doFilter(request, response, this);
                    } catch (Exception e) {
                        throw new RuntimeException("Filter chain error", e);
                    }
                }
            }
        }
    }
}