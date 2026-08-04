package com.chua.common.support.network.client;

import com.chua.common.support.lang.placeholder.PlaceholderSupport;
import com.chua.common.support.lang.placeholder.StringValuePropertyResolver;
import com.chua.common.support.network.annotations.RequestMethod;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.lang.json.Json;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NullUnmarked;

/**
 * JDK 动态代理处理器，将接口方法调用转换为 HTTP 请求。
 *
 * <p>复用已有基础设施：</p>
 * <ul>
 *   <li>{@link PlaceholderSupport} / {@link StringValuePropertyResolver} — 占位符 {@code ${key:default}} 解析</li>
 *   <li>{@link HttpClientFactory} / {@link HttpClientBuilder} — HTTP 请求构建与执行</li>
 *   <li>{@link Json} — 响应体 JSON 反序列化</li>
 *   <li>Spring MVC 注解 — {@code @GetMapping}、{@code @PostMapping}、{@code @RequestMapping} 等（反射访问，无编译期依赖）</li>
 *   <li>{@link RequestMethod} — 项目自有 HTTP 方法注解</li>
 *   <li>Spring 参数注解 — {@code @PathVariable}、{@code @RequestParam}、{@code @RequestBody}、{@code @RequestHeader}</li>
 * </ul>
 *
 * @author CH
 * @see HttpApiFactory
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class HttpApiInvocationHandler implements InvocationHandler {

    /** Spring MVC 方法级注解 → HTTP 方法映射 */
    private static final Map<String, HttpMethod> SPRING_METHOD_ANNOTATIONS = new LinkedHashMap<>();

    /** Spring MVC 类级注解（提取 baseUrl） */
    private static final String[] CLASS_LEVEL_ANNOTATIONS = {
            "org.springframework.web.bind.annotation.RequestMapping"
    };

    /** Spring 参数注解类名 */
    private static final String SPRING_PATH_VARIABLE   = "org.springframework.web.bind.annotation.PathVariable";
    private static final String SPRING_REQUEST_PARAM   = "org.springframework.web.bind.annotation.RequestParam";
    private static final String SPRING_REQUEST_BODY    = "org.springframework.web.bind.annotation.RequestBody";
    private static final String SPRING_REQUEST_HEADER  = "org.springframework.web.bind.annotation.RequestHeader";
    private static final String SPRING_REQUEST_ATTRIBUTE = "org.springframework.web.bind.annotation.RequestAttribute";

    static {
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.GetMapping",    HttpMethod.GET);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.PostMapping",   HttpMethod.POST);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.PutMapping",    HttpMethod.PUT);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.DeleteMapping", HttpMethod.DELETE);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.PatchMapping",  HttpMethod.PATCH);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.RequestMapping", null);
    }

    private final Class<?> apiClass;
    private final String baseUrl;
    private final StringValuePropertyResolver propertyResolver;
    private final ConcurrentMap<Method, MethodMetadata> methodCache = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    /**
     * @param apiClass 要代理的接口类
     */
    public HttpApiInvocationHandler(Class<?> apiClass) {
        this.apiClass = apiClass;

        PlaceholderSupport ps = new PlaceholderSupport();
        this.propertyResolver = new StringValuePropertyResolver(ps);
        this.httpClient = HttpClientFactory.getClient();
        this.baseUrl = resolveBaseUrl(apiClass);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }
        MethodMetadata meta = methodCache.computeIfAbsent(method, this::parseMethod);
        return execute(meta, args);
    }

    @Override
    public String toString() {
        return "HttpApiProxy{" + apiClass.getSimpleName() + ", baseUrl='" + baseUrl + "'}";
    }

    // ==================== 核心执行 ====================

    private Object execute(MethodMetadata meta, Object[] args) {
        // 1. 解析路径模板中的占位符（支持 ${...} 环境变量/系统属性）
        String path = propertyResolver.resolvePlaceholders(meta.pathTemplate);

        // 2. 解析参数
        Map<String, String> queryParams = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Object body = null;

        if (args != null) {
            Parameter[] parameters = meta.method.getParameters();
            for (int i = 0; i < args.length && i < parameters.length; i++) {
                ParamAnnotation pa = meta.paramAnnotations[i];
                Object arg = args[i];
                if (arg == null && pa.required) continue;
                String sv = arg != null ? arg.toString() : pa.defaultValue;
                if (sv == null || sv.isEmpty()) continue;

                switch (pa.type) {
                    case PATH_VARIABLE ->
                        path = path.replace("{" + pa.name + "}", encodePathSegment(sv));
                    case REQUEST_PARAM ->
                        queryParams.put(pa.name, sv);
                    case REQUEST_HEADER ->
                        headers.put(pa.name, sv);
                    case REQUEST_BODY ->
                        body = arg;
                    case UNKNOWN ->
                        queryParams.put(pa.name, sv);
                }
            }
        }

        // 3. 拼接完整 URL
        String fullUrl = baseUrl;
        if (!path.isEmpty()) {
            fullUrl = baseUrl.endsWith("/") || path.startsWith("/")
                    ? baseUrl + path
                    : baseUrl + "/" + path;
        }

        // 4. 构建并执行请求
        HttpClientBuilder builder = HttpClientFactory.of(fullUrl);
        headers.forEach(builder::header);
        queryParams.forEach(builder::query);

        if (body != null) {
            builder.json().body(Json.toJson(body));
        }

        ClientResponse resp = switch (meta.httpMethod) {
            case GET     -> builder.get();
            case POST    -> builder.post();
            case PUT     -> builder.put();
            case DELETE  -> builder.delete();
            case PATCH   -> builder.patch();
            case HEAD    -> builder.head();
            case OPTIONS -> builder.options();
        };

        // 5. 响应转换
        return convertResponse(resp, meta);
    }

    // ==================== 基础 URL 解析 ====================

    private String resolveBaseUrl(Class<?> clazz) {
        for (String annClass : CLASS_LEVEL_ANNOTATIONS) {
            try {
                Class<?> cl = Class.forName(annClass);
                Annotation ann = clazz.getAnnotation(cl.asSubclass(Annotation.class));
                if (ann != null) {
                    String v = extractAnnotationValue(ann);
                    if (v != null && !v.isEmpty()) {
                        return trimSlash(propertyResolver.resolvePlaceholders(v));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        RequestMethod rm = clazz.getAnnotation(RequestMethod.class);
        if (rm != null && !rm.value().isEmpty()) {
            return trimSlash(propertyResolver.resolvePlaceholders(rm.value()));
        }
        return "";
    }

    // ==================== 方法元数据解析 ====================

    private MethodMetadata parseMethod(Method method) {
        HttpMethod httpMethod = null;
        String pathTemplate = "";

        for (Map.Entry<String, HttpMethod> entry : SPRING_METHOD_ANNOTATIONS.entrySet()) {
            try {
                Class<?> annClass = Class.forName(entry.getKey());
                Annotation ann = method.getAnnotation(annClass.asSubclass(Annotation.class));
                if (ann != null) {
                    if (entry.getValue() != null) {
                        httpMethod = entry.getValue();
                    } else {
                        httpMethod = extractRequestMappingMethod(ann);
                    }
                    pathTemplate = extractAnnotationValue(ann);
                    if (pathTemplate == null) pathTemplate = "";
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        if (httpMethod == null) {
            RequestMethod rm = method.getAnnotation(RequestMethod.class);
            if (rm != null) {
                String val = rm.value();
                String meth = rm.method();
                if (!meth.isEmpty()) {
                    httpMethod = HttpMethod.valueOf(meth.toUpperCase());
                    pathTemplate = val;
                } else {
                    int sp = val.indexOf(' ');
                    if (sp > 0) {
                        httpMethod = HttpMethod.valueOf(val.substring(0, sp).toUpperCase());
                        pathTemplate = val.substring(sp + 1);
                    } else {
                        pathTemplate = val;
                    }
                }
            }
        }

        if (httpMethod == null) {
            throw new IllegalStateException("方法 " + method.getName()
                    + " 缺少 HTTP 方法注解 (@GetMapping/@PostMapping/@RequestMethod 等)");
        }

        Parameter[] params = method.getParameters();
        ParamAnnotation[] pas = new ParamAnnotation[params.length];
        for (int i = 0; i < params.length; i++) {
            pas[i] = resolveParamAnnotation(params[i]);
        }

        return new MethodMetadata(method, httpMethod, pathTemplate, pas);
    }

    private ParamAnnotation resolveParamAnnotation(Parameter param) {
        ParamAnnotation pa;

        pa = resolveSpringParam(param, SPRING_PATH_VARIABLE,
                (ann, n) -> new ParamAnnotation(ParamType.PATH_VARIABLE, n != null ? n : param.getName()));
        if (pa != null) return pa;

        pa = resolveSpringParam(param, SPRING_REQUEST_PARAM, (ann, n) -> {
            boolean req = getBoolean(ann, "required", true);
            String def = getString(ann, "defaultValue", "");
            return new ParamAnnotation(ParamType.REQUEST_PARAM, n != null ? n : param.getName(), req, def);
        });
        if (pa != null) return pa;

        pa = resolveSpringParam(param, SPRING_REQUEST_BODY,
                (ann, n) -> new ParamAnnotation(ParamType.REQUEST_BODY, ""));
        if (pa != null) return pa;

        pa = resolveSpringParam(param, SPRING_REQUEST_HEADER,
                (ann, n) -> new ParamAnnotation(ParamType.REQUEST_HEADER, n != null ? n : param.getName()));
        if (pa != null) return pa;

        pa = resolveSpringParam(param, SPRING_REQUEST_ATTRIBUTE,
                (ann, n) -> new ParamAnnotation(ParamType.REQUEST_PARAM, n != null ? n : param.getName()));
        if (pa != null) return pa;

        // 无注解参数：按 @RequestParam 推断（参数名作为查询参数名）
        return new ParamAnnotation(ParamType.REQUEST_PARAM, param.getName());
    }

    // ==================== 响应转换 ====================

    @SuppressWarnings("unchecked")
    private Object convertResponse(ClientResponse resp, MethodMetadata meta) {
        Class<?> returnType = meta.method.getReturnType();
        if (returnType == void.class || returnType == Void.class) return null;
        if (returnType == ClientResponse.class) return resp;
        if (returnType == String.class) return resp.getBodyString();
        if (returnType == byte[].class) return resp.getBody();

        if (returnType == CompletableFuture.class) {
            Type genericReturn = meta.method.getGenericReturnType();
            if (genericReturn instanceof ParameterizedType pt) {
                Type actualArg = pt.getActualTypeArguments()[0];
                if (actualArg instanceof Class<?> actualClass) {
                    if (actualClass == ClientResponse.class) return CompletableFuture.completedFuture(resp);
                    if (actualClass == String.class) return CompletableFuture.completedFuture(resp.getBodyString());
                    return CompletableFuture.completedFuture(Json.fromJson(resp.getBodyString(), actualClass));
                }
            }
            return CompletableFuture.completedFuture(resp);
        }

        if (!resp.isSuccess() || resp.getBody() == null || resp.getBody().length == 0) {
            return null;
        }
        return Json.fromJson(resp.getBodyString(), returnType);
    }

    // ==================== 反射工具 ====================

    private static String extractAnnotationValue(Annotation ann) {
        try {
            Method m = ann.getClass().getMethod("value");
            Object r = m.invoke(ann);
            if (r instanceof String s) return s;
            if (r instanceof String[] a && a.length > 0) return a[0];
        } catch (Exception ignored) {
        }
        return "";
    }

    private static HttpMethod extractRequestMappingMethod(Annotation ann) {
        try {
            Method m = ann.getClass().getMethod("method");
            Object r = m.invoke(ann);
            if (r instanceof Object[] a && a.length > 0) {
                String name = a[0].toString();
                int dot = name.lastIndexOf('.');
                return HttpMethod.valueOf(dot > 0 ? name.substring(dot + 1) : name);
            }
        } catch (Exception ignored) {
        }
        return HttpMethod.GET;
    }

    private static String getString(Annotation ann, String attr, String def) {
        try {
            Method m = ann.getClass().getMethod(attr);
            Object r = m.invoke(ann);
            return r != null ? r.toString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean getBoolean(Annotation ann, String attr, boolean def) {
        try {
            Method m = ann.getClass().getMethod(attr);
            Object r = m.invoke(ann);
            return r instanceof Boolean b ? b : def;
        } catch (Exception e) {
            return def;
        }
    }

    private ParamAnnotation resolveSpringParam(Parameter param, String className, AnnResolver resolver) {
        try {
            Class<?> ac = Class.forName(className);
            Annotation ann = param.getAnnotation(ac.asSubclass(Annotation.class));
            if (ann != null) {
                String n = getString(ann, "value", "");
                if (n.isEmpty()) n = getString(ann, "name", "");
                return resolver.resolve(ann, n.isEmpty() ? null : n);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 路径段编码：空格 → %20，保留 / 不变 */
    private static String encodePathSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    private static String trimSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    // ==================== 内部类型 ====================

    enum ParamType {
        PATH_VARIABLE, REQUEST_PARAM, REQUEST_BODY, REQUEST_HEADER, UNKNOWN
    }

    static class ParamAnnotation {
        final ParamType type;
        final String name;
        final boolean required;
        final String defaultValue;

        ParamAnnotation(ParamType type, String name) {
            this(type, name, true, "");
        }

        ParamAnnotation(ParamType type, String name, boolean required, String defaultValue) {
            this.type = type;
            this.name = name;
            this.required = required;
            this.defaultValue = defaultValue;
        }
    }

    static class MethodMetadata {
        final Method method;
        final HttpMethod httpMethod;
        final String pathTemplate;
        final ParamAnnotation[] paramAnnotations;

        MethodMetadata(Method method, HttpMethod httpMethod, String pathTemplate,
                       ParamAnnotation[] paramAnnotations) {
            this.method = method;
            this.httpMethod = httpMethod;
            this.pathTemplate = pathTemplate;
            this.paramAnnotations = paramAnnotations;
        }
    }

    @FunctionalInterface
    interface AnnResolver {
        ParamAnnotation resolve(Annotation annotation, String name);
    }
}
