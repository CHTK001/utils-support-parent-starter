package com.chua.common.support.network.invoker;

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

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import com.chua.common.support.reflection.ReflectUtils;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 IPC 协议的调用器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "ipc", order = 40)
public class IpcInvoker implements Invoker {

    private static final ConcurrentMap<Class<?>, Object> PROXY_CACHE = new ConcurrentHashMap<>();
    /** 全局injectrules */
    private final List<SharedInvocationContext.InjectRule> globalInjectRules = new java.util.ArrayList<>();

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
            } catch (Exception ignored) {
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

    private static String resolveNamespace(Class<?> clazz) {
        IpcMethod im = clazz.getAnnotation(IpcMethod.class);
        if (im != null && !StringUtils.isEmpty(im.value())) {
            return im.value();
        }
        return "";
    }

    @Override
    /**
     * 添加Inject
     * @param target target
     * @param callback callback
     */
    public Invoker addInject(String target, InjectCallback callback) {
        globalInjectRules.add(new SharedInvocationContext.InjectRule(target, callback));
        return this;
    }

    private static String extractAnnotationValue(Annotation ann) {
        try {
Object r = ReflectUtils.invoke(ann, "value", Object.class, new Class<?>[0], new Object[0], new Object[0]);
            if (r instanceof String s) {
                return s;
            }
            if (r instanceof String[] a && a.length > 0) {
                return a[0];
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static class IpcInvocationHandler implements InvocationHandler {

        /** BaseURL */
        private final String baseUrl;
        /** Namespace */
        private final String namespace;
        /** Filters */
        private final List<ServerFilter> filters;
        /** Shared上下文 */
        private final SharedInvocationContext sharedContext;
        /** Property解析器 */
        private final StringValuePropertyResolver propertyResolver = new StringValuePropertyResolver(null);

        IpcInvocationHandler(String baseUrl, String namespace, List<ServerFilter> filters, SharedInvocationContext sharedContext) {
            this.baseUrl = baseUrl;
            this.namespace = namespace;
            this.filters = filters;
            this.sharedContext = sharedContext;
        }

        @Override
        /**
         * 调用
         * @param proxy proxy
         * @param method method
         * @param args args
         */
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                try { return method.invoke(this, args); } catch (Exception e) { return null; }
            }

            IpcMethod im = method.getAnnotation(IpcMethod.class);
            String methodName = im != null ? im.value() : resolveMethodName(method);
            String path = namespace.isEmpty() ? "/ipc/" + methodName : "/ipc/" + namespace + "/" + methodName;
            String url = baseUrl + path;

            InvocationContext ctx = new InvocationContext();
            ctx.setPath(path);
            ctx.setAttribute("javaMethod", method);
            ctx.setAttribute("javaArgs", args);
            ctx.setAttribute("targetClass", method.getDeclaringClass());
            ctx.setAttribute("sharedContext", sharedContext);
            if (args != null && args.length > 0) {
                ctx.setBody(com.chua.common.support.lang.json.Json.toJson(args[0]));
            }

            sharedContext.applyTo(ctx);

            processRemoteHeaders(ctx, method, args);

            if (!filters.isEmpty()) {
                runFilterChain(ctx, filters);
            }

            processRemoteInject(ctx, method, args);

            String body = ctx.getBody() != null
                    ? com.chua.common.support.lang.json.Json.toJson(ctx.getBody())
                    : "";

            var builder = HttpClientFactory.of(url).json();
            ctx.getHeaders().toMap().forEach(builder::header);
            String result = builder.body(body).post().getBodyString();
            ctx.setResult(result);
            return result;
        }

        /**
         * 处理RemoteHeaders
         * @param ctx ctx
         * @param method method
         * @param args args
         */
        private void processRemoteHeaders(InvocationContext ctx, Method method, Object[] args) {
            RemoteHeader[] headers = method.getAnnotationsByType(RemoteHeader.class);
            for (RemoteHeader rh : headers) {
                String value = resolvePlaceholders(rh.value());
                ctx.addHeader(rh.name(), value);
            }
            if (args != null) {
                java.lang.reflect.Parameter[] params = method.getParameters();
                for (int i = 0; i < params.length; i++) {
                    RemoteHeader rh = params[i].getAnnotation(RemoteHeader.class);
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
        private void processRemoteInject(InvocationContext ctx, Method method, Object[] args) {
            RemoteInject[] injects = method.getAnnotationsByType(RemoteInject.class);
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
         */
        private String resolvePlaceholders(String text) {
            if (StringUtils.isEmpty(text) || !text.contains("${")) {
                return text;
            }
            return propertyResolver.resolvePlaceholders(text);
        }

        private static String resolveMethodName(Method method) {
            RemoteMethod rm = method.getAnnotation(RemoteMethod.class);
            if (rm != null && !StringUtils.isEmpty(rm.value())) {
                return rm.value();
            }
            return method.getName();
        }

        private static void runFilterChain(InvocationContext ctx, List<ServerFilter> filters) {
            ServerFilterChain chain = new ServerFilterChain() {
                /** 索引 */
                private int index = 0;
                @Override
                /**
                 * Do过滤
                 * @param request request
                 * @param response response
                 */
                public void doFilter(com.chua.common.support.network.server.request.ServerRequest request,
                                     com.chua.common.support.network.server.response.ServerResponse response) {
                    if (index < filters.size()) {
                        try {
                            filters.get(index++).doFilter(request, response, this);
                        } catch (Exception e) {
                            throw new RuntimeException("Filter chain error", e);
                        }
                    }
                }
            };
            try {
                chain.doFilter(ctx, ctx);
            } catch (Exception e) {
                throw new RuntimeException("Filter chain error", e);
            }
        }
    }
}
