package com.chua.common.support.network.client;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.placeholder.PlaceholderSupport;
import com.chua.common.support.lang.placeholder.StringValuePropertyResolver;
import com.chua.common.support.network.annotations.RequestMethod;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.invoker.annotations.RemoteService;
import com.chua.common.support.network.invoker.filter.InjectCallback;
import com.chua.common.support.network.invoker.filter.InvocationContext;
import com.chua.common.support.network.invoker.filter.SharedInvocationContext;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 * @since 4.0.0.42
 * @see HttpApiFactory
 */
@Slf4j
public class HttpApiInvocationHandler implements InvocationHandler {

    /**
     * Spring MVC 方法级注解 → HTTP 方法映射
     */
    private static final Map<String, HttpMethod> SPRING_METHOD_ANNOTATIONS = new LinkedHashMap<>();

    /**
     * Spring MVC 类级注解（提取 baseUrl）
     */
    private static final String[] CLASS_LEVEL_ANNOTATIONS = {
            "org.springframework.web.bind.annotation.RequestMapping"
    };

    /**
     * Spring 参数注解类名
     */
    private static final String SPRING_PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable";
    /** Spring_request_param */
    private static final String SPRING_REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam";
    /** Spring_request_body */
    private static final String SPRING_REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";
    /** Spring_request_header */
    private static final String SPRING_REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader";
    /** Spring_request_attribute */
    private static final String SPRING_REQUEST_ATTRIBUTE = "org.springframework.web.bind.annotation.RequestAttribute";

    /**
     * 反射访问的注解属性名
     */
    private static final String ANN_ATTR_VALUE = "value";
    /** Ann_attr_name */
    private static final String ANN_ATTR_NAME = "name";
    /** Ann_attr_method */
    private static final String ANN_ATTR_METHOD = "method";
    /** Ann_attr_required */
    private static final String ANN_ATTR_REQUIRED = "required";
    /** Ann_attr_default_value */
    private static final String ANN_ATTR_DEFAULT_VALUE = "defaultValue";

    /**
     * 默认 baseUrl（未声明类级注解时）
     */
    private static final String DEFAULT_BASE_URL = "";

    /**
     * 路径模板默认值
     */
    private static final String DEFAULT_PATH_TEMPLATE = "";

    static {
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.GetMapping", HttpMethod.GET);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.PostMapping", HttpMethod.POST);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.PutMapping", HttpMethod.PUT);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.DeleteMapping", HttpMethod.DELETE);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.PatchMapping", HttpMethod.PATCH);
        SPRING_METHOD_ANNOTATIONS.put("org.springframework.web.bind.annotation.RequestMapping", null);
    }

    /**
     * 要代理的接口类
     */
    private final Class<?> apiClass;

    /**
     * 基础 URL（解析自类级注解）
     */
    private final String baseUrl;

    /**
     * 占位符解析器
     */
    private final StringValuePropertyResolver propertyResolver;

    /**
     * 方法元数据缓存（按 Method 去重解析）
     */
    private final ConcurrentMap<Method, MethodMetadata> methodCache = new ConcurrentHashMap<>();

    /**
     * HTTP 客户端
     */
    private final HttpClient httpClient;

    /**
     * 请求级注入规则（与 {@code @RemoteInject} 注解功能一致的编程式注入）。
     *
     * <p>由 {@link HttpInvoker#addInject(String, InjectCallback)} 注册，每次远程调用前执行，
     * 将回调返回值注入到请求头或共享属性。不可变，构造时确定。</p>
     */
    private final List<SharedInvocationContext.InjectRule> injectRules;

    /**
     * 请求级默认配置（默认请求头/超时/重试/缓存/重定向/版本/代理）。
     *
     * <p>由 {@link HttpInvoker} 链式方法配置，每次远程调用前应用到 {@link RequestSpec}。
     * 可为 null（表示无额外默认配置）。</p>
     */
    private final HttpApiOptions options;

    /**
     * 构造处理器并预解析 baseUrl
     *
     * @param apiClass 要代理的接口类
     */
    public HttpApiInvocationHandler(Class<?> apiClass) {
        this(apiClass, null);
    }

    /**
     * 构造处理器并预解析 baseUrl（支持自定义配置）。
     *
     * <p>通过 {@link HttpApiOptions} 可覆盖接口类级注解解析出的 baseUrl，注入自定义
     * {@link HttpClient}（含拦截器）等。options 为 null 时使用默认配置。</p>
     *
     * @param apiClass 要代理的接口类
     * @param options  自定义配置（baseUrl/客户端/拦截器/注入规则/请求级默认配置），可为 null
     */
    public HttpApiInvocationHandler(Class<?> apiClass, HttpApiOptions options) {
        this.apiClass = apiClass;

        // 初始化占位符解析器与 HTTP 客户端
        PlaceholderSupport ps = new PlaceholderSupport();
        this.propertyResolver = new StringValuePropertyResolver(ps);
        this.httpClient = options != null ? options.resolveClient() : HttpClientFactory.getClient();
        this.baseUrl = resolveBaseUrl(apiClass, options);
        this.injectRules = options != null ? options.getInjectRules() : List.of();
        this.options = options;
    }

    /**
     * 拦截接口方法调用并转换为 HTTP 请求执行
     *
     * @param proxy  代理实例
     * @param method 被调用的方法
     * @param args   方法参数
     * @return HTTP 响应转换后的结果
     * @throws Throwable 反射或 HTTP 调用异常
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Object 类方法直接透传
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }
        // 命中缓存则复用，否则解析并存入缓存
        MethodMetadata meta = methodCache.computeIfAbsent(method, this::parseMethod);
        return execute(meta, args);
    }

    /**
     * 代理对象的字符串表示
     *
     * @return 包含接口名与 baseUrl 的描述
     */
    @Override
    public String toString() {
        return "HttpApiProxy{" + apiClass.getSimpleName() + ", baseUrl='" + baseUrl + "'}";
    }

    // ==================== 核心执行 ====================

    /**
     * 执行一次方法调用：解析参数、构建请求、转换响应
     *
     * @param meta 方法元数据
     * @param args 实际入参
     * @return 转换后的响应结果
     */
    private Object execute(MethodMetadata meta, Object[] args) {
        // 1. 解析路径模板中的占位符（支持 ${...} 环境变量/系统属性）
        String path = propertyResolver.resolvePlaceholders(meta.pathTemplate);

        // 2. 解析参数：查询参数、请求头、请求体
        Map<String, String> queryParams = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Object body = null;

        if (args != null) {
            Parameter[] parameters = meta.method.getParameters();
            for (int i = 0; i < args.length && i < parameters.length; i++) {
                ParamAnnotation pa = meta.paramAnnotations[i];
                Object arg = args[i];
                if (arg == null && pa.required) {
                    continue;
                }
                String sv = arg != null ? arg.toString() : pa.defaultValue;
                if (StringUtils.isEmpty(sv)) {
                    continue;
                }

                // 根据注解类型分发处理
                switch (pa.type) {
                    case PATH_VARIABLE -> path = path.replace("{" + pa.name + "}", encodePathSegment(sv));
                    case REQUEST_PARAM -> queryParams.put(pa.name, sv);
                    case REQUEST_HEADER -> headers.put(pa.name, sv);
                    case REQUEST_BODY -> body = arg;
                    case UNKNOWN -> queryParams.put(pa.name, sv);
                    default -> {
                    }
                }
            }
        }

        // 3. 应用编程式注入规则（与 @RemoteInject 功能一致，见 HttpInvoker#addInject）
        applyInjectRules(meta, args, headers);

        // 4. 拼接完整 URL（兼容 baseUrl 与 path 之间的斜杠）
        String fullUrl = baseUrl;
        if (!path.isEmpty()) {
            fullUrl = baseUrl.endsWith("/") || path.startsWith("/")
                    ? baseUrl + path
                    : baseUrl + "/" + path;
        }

        // 5. 构建并执行请求（走当前绑定客户端，确保注入规则/自定义 client 生效）
        RequestSpec spec = httpClient.request(fullUrl, meta.httpMethod);
        if (options != null) {
            // 应用请求级默认配置（默认请求头/超时/重试/缓存/重定向/版本/代理）
            options.applyTo(spec);
        }
        headers.forEach(spec::header);
        queryParams.forEach(spec::query);

        if (body != null) {
            spec.json().body(Json.toJson(body));
        }

        ClientResponse resp = spec.execute();

        // 6. 响应转换
        return convertResponse(resp, meta);
    }

    /**
     * 应用编程式注入规则，将回调返回值注入到请求头或共享属性。
     *
     * <p>与 {@code @RemoteInject} 注解功能一致，由 {@link HttpInvoker#addInject(String, InjectCallback)}
     * 注册。每次远程调用前执行，支持链式注入（先注入的 attributes 可被后续规则读取）。</p>
     *
     * @param meta    方法元数据
     * @param args    方法入参
     * @param headers 请求头容器（就地修改）
     * @return 本次调用注入的共享属性集合
     */
    private Map<String, Object> applyInjectRules(MethodMetadata meta, Object[] args, Map<String, String> headers) {
        if (injectRules.isEmpty()) {
            return Collections.emptyMap();
        }
        InvocationContext ctx = new InvocationContext();
        ctx.setPath(meta.pathTemplate);
        ctx.setAttribute("javaMethod", meta.method);
        ctx.setAttribute("javaArgs", args == null ? new Object[0] : args);
        ctx.setAttribute("targetClass", meta.method.getDeclaringClass());

        Map<String, Object> attributes = new LinkedHashMap<>();
        for (SharedInvocationContext.InjectRule rule : injectRules) {
            String value = rule.callback().apply(ctx);
            if (value == null) {
                continue;
            }
            String target = rule.target();
            if (target.startsWith("headers.")) {
                headers.put(target.substring(8), value);
            } else if (target.startsWith("attributes.")) {
                String key = target.substring(11);
                attributes.put(key, value);
                ctx.setAttribute(key, value);
            }
        }
        return attributes;
    }

    // ==================== 基础 URL 解析 ====================

    /**
     * 解析类级注解中的 baseUrl
     *
     * <p>优先级：{@link HttpApiOptions} 中自定义的 baseUrl &gt; 类级注解解析的 baseUrl。</p>
     *
     * @param clazz   接口类
     * @param options 自定义配置，可为 null
     * @return 解析后的 baseUrl
     */
    private String resolveBaseUrl(Class<?> clazz, HttpApiOptions options) {
        // 优先使用自定义配置中的 baseUrl
        if (options != null) {
            String custom = options.getBaseUrl();
            if (!StringUtils.isEmpty(custom)) {
                return trimSlash(propertyResolver.resolvePlaceholders(custom));
            }
        }
        // 优先尝试 Spring 类级注解
        for (String annClass : CLASS_LEVEL_ANNOTATIONS) {
            try {
                Class<?> cl = ReflectUtils.forName(annClass);
                Annotation ann = clazz.getAnnotation(cl.asSubclass(Annotation.class));
                if (ann != null) {
                    String v = extractAnnotationValue(ann);
                    if (!StringUtils.isEmpty(v)) {
                        return trimSlash(propertyResolver.resolvePlaceholders(v));
                    }
                }
            } catch (Exception ignored) {
                // 类路径中无 Spring 注解，跳过
            }
        }
        // 回退到项目自有注解
        RequestMethod rm = clazz.getAnnotation(RequestMethod.class);
        if (rm != null && !StringUtils.isEmpty(rm.value())) {
            return trimSlash(propertyResolver.resolvePlaceholders(rm.value()));
        }
        RemoteService rs = clazz.getAnnotation(RemoteService.class);
        if (rs != null && !StringUtils.isEmpty(rs.url())) {
            return trimSlash(propertyResolver.resolvePlaceholders(rs.url()));
        }
        return DEFAULT_BASE_URL;
    }

    // ==================== 方法元数据解析 ====================

    /**
     * 解析方法的 HTTP 方法、路径模板与参数注解
     *
     * @param method 方法
     * @return 方法元数据
     */
    private MethodMetadata parseMethod(Method method) {
        HttpMethod httpMethod = null;
        String pathTemplate = DEFAULT_PATH_TEMPLATE;

        // 遍历 Spring 方法注解，匹配首个命中
        for (Map.Entry<String, HttpMethod> entry : SPRING_METHOD_ANNOTATIONS.entrySet()) {
            try {
                Class<?> annClass = ReflectUtils.forName(entry.getKey());
                Annotation ann = method.getAnnotation(annClass.asSubclass(Annotation.class));
                if (ann != null) {
                    if (entry.getValue() != null) {
                        httpMethod = entry.getValue();
                    } else {
                        // @RequestMapping 需要从 method 属性推断
                        httpMethod = extractRequestMappingMethod(ann);
                    }
                    pathTemplate = extractAnnotationValue(ann);
                    if (pathTemplate == null) {
                        pathTemplate = DEFAULT_PATH_TEMPLATE;
                    }
                    break;
                }
            } catch (Exception ignored) {
                // 类路径中无该 Spring 注解，跳过
            }
        }

        // 未命中 Spring 注解时回退到项目自有注解
        if (httpMethod == null) {
            RequestMethod rm = method.getAnnotation(RequestMethod.class);
            if (rm != null) {
                String val = rm.value();
                String meth = rm.method();
                if (!StringUtils.isEmpty(meth)) {
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

        // 仍未解析到 HTTP 方法则抛出异常
        if (httpMethod == null) {
            throw new IllegalStateException("方法 " + method.getName()
                    + " 缺少 HTTP 方法注解 (@GetMapping/@PostMapping/@RequestMethod 等)");
        }

        // 解析每个形参的注解信息
        Parameter[] params = method.getParameters();
        ParamAnnotation[] pas = new ParamAnnotation[params.length];
        for (int i = 0; i < params.length; i++) {
            pas[i] = resolveParamAnnotation(params[i]);
        }

        return new MethodMetadata(method, httpMethod, pathTemplate, pas);
    }

    /**
     * 解析单个形参的注解类型与名称
     *
     * @param param 形参
     * @return 参数注解信息
     */
    private ParamAnnotation resolveParamAnnotation(Parameter param) {
        ParamAnnotation pa;

        // @PathVariable
        pa = resolveSpringParam(param, SPRING_PATH_VARIABLE,
                (ann, n) -> new ParamAnnotation(ParamType.PATH_VARIABLE, n != null ? n : param.getName()));
        if (pa != null) {
            return pa;
        }

        // @RequestParam
        pa = resolveSpringParam(param, SPRING_REQUEST_PARAM, (ann, n) -> {
            boolean req = getBoolean(ann, ANN_ATTR_REQUIRED, true);
            String def = getString(ann, ANN_ATTR_DEFAULT_VALUE, "");
            return new ParamAnnotation(ParamType.REQUEST_PARAM, n != null ? n : param.getName(), req, def);
        });
        if (pa != null) {
            return pa;
        }

        // @RequestBody
        pa = resolveSpringParam(param, SPRING_REQUEST_BODY,
                (ann, n) -> new ParamAnnotation(ParamType.REQUEST_BODY, ""));
        if (pa != null) {
            return pa;
        }

        // @RequestHeader
        pa = resolveSpringParam(param, SPRING_REQUEST_HEADER,
                (ann, n) -> new ParamAnnotation(ParamType.REQUEST_HEADER, n != null ? n : param.getName()));
        if (pa != null) {
            return pa;
        }

        // @RequestAttribute 当作 @RequestParam 处理
        pa = resolveSpringParam(param, SPRING_REQUEST_ATTRIBUTE,
                (ann, n) -> new ParamAnnotation(ParamType.REQUEST_PARAM, n != null ? n : param.getName()));
        if (pa != null) {
            return pa;
        }

        // 无注解参数：按 @RequestParam 推断（参数名作为查询参数名）
        return new ParamAnnotation(ParamType.REQUEST_PARAM, param.getName());
    }

    // ==================== 响应转换 ====================

    /**
     * 将 HTTP 响应转换为方法签名要求的返回类型
     *
     * @param resp HTTP 响应
     * @param meta 方法元数据
     * @return 转换后的结果
     */
    @SuppressWarnings("unchecked")
    private Object convertResponse(ClientResponse resp, MethodMetadata meta) {
        Class<?> returnType = meta.method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        if (returnType == ClientResponse.class) {
            return resp;
        }
        if (returnType == String.class) {
            return resp.getBodyString();
        }
        if (returnType == byte[].class) {
            return resp.getBody();
        }

        // CompletableFuture<T> 包装异步结果
        if (returnType == CompletableFuture.class) {
            Type genericReturn = meta.method.getGenericReturnType();
            if (genericReturn instanceof ParameterizedType pt) {
                Type actualArg = pt.getActualTypeArguments()[0];
                if (actualArg instanceof Class<?> actualClass) {
                    if (actualClass == ClientResponse.class) {
                        return CompletableFuture.completedFuture(resp);
                    }
                    if (actualClass == String.class) {
                        return CompletableFuture.completedFuture(resp.getBodyString());
                    }
                    return CompletableFuture.completedFuture(Json.fromJson(resp.getBodyString(), actualClass));
                }
            }
            return CompletableFuture.completedFuture(resp);
        }

        // JSON 反序列化兜底
        if (!resp.isSuccess() || resp.getBody() == null || resp.getBody().length == 0) {
            return null;
        }
        return Json.fromJson(resp.getBodyString(), returnType);
    }

    // ==================== 反射工具 ====================

    /**
     * 读取注解的 value 属性（支持 String 或 String[]）
     *
     * @param ann 注解实例
     * @return value 值，无值返回空串
     */
    private static String extractAnnotationValue(Annotation ann) {
        try {
Object r = ReflectUtils.invoke(ann, ANN_ATTR_VALUE, Object.class, new Class<?>[0], new Object[0], new Object[0]);
            if (r instanceof String s) {
                return s;
            }
            if (r instanceof String[] a && a.length > 0) {
                return a[0];
            }
        } catch (Exception ignored) {
            // 注解不含 value 属性，返回空
        }
        return "";
    }

    /**
     * 从 @RequestMapping 注解读取 method 属性并转换为 HttpMethod 枚举
     *
     * @param ann @RequestMapping 注解实例
     * @return 解析到的 HTTP 方法，默认 GET
     */
    private static HttpMethod extractRequestMappingMethod(Annotation ann) {
        try {
Object r = ReflectUtils.invoke(ann, ANN_ATTR_METHOD, Object.class, new Class<?>[0], new Object[0], new Object[0]);
            if (r instanceof Object[] a && a.length > 0) {
                String name = a[0].toString();
                int dot = name.lastIndexOf('.');
                return HttpMethod.valueOf(dot > 0 ? name.substring(dot + 1) : name);
            }
        } catch (Exception ignored) {
            // 注解不含 method 属性
        }
        return HttpMethod.GET;
    }

    /**
     * 反射读取注解的字符串属性
     *
     * @param ann  注解实例
     * @param attr 属性名
     * @param def  默认值
     * @return 属性值或默认值
     */
    private static String getString(Annotation ann, String attr, String def) {
        try {
Object r = ReflectUtils.invoke(ann, attr, Object.class, new Class<?>[0], new Object[0], new Object[0]);
            return r != null ? r.toString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 反射读取注解的布尔属性
     *
     * @param ann  注解实例
     * @param attr 属性名
     * @param def  默认值
     * @return 属性值或默认值
     */
    private static boolean getBoolean(Annotation ann, String attr, boolean def) {
        try {
Object r = ReflectUtils.invoke(ann, attr, Object.class, new Class<?>[0], new Object[0], new Object[0]);
            return r instanceof Boolean b ? b : def;
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 解析 Spring 参数注解
     *
     * @param param     形参
     * @param className 注解类全限定名
     * @param resolver  注解 → ParamAnnotation 转换器
     * @return 解析结果，未声明该注解返回 null
     */
    private ParamAnnotation resolveSpringParam(Parameter param, String className, AnnResolver resolver) {
        try {
            Class<?> ac = ReflectUtils.forName(className);
            Annotation ann = param.getAnnotation(ac.asSubclass(Annotation.class));
            if (ann != null) {
                String n = getString(ann, ANN_ATTR_VALUE, "");
                if (StringUtils.isEmpty(n)) {
                    n = getString(ann, ANN_ATTR_NAME, "");
                }
                return resolver.resolve(ann, StringUtils.isEmpty(n) ? null : n);
            }
        } catch (Exception ignored) {
            // 类路径中无该 Spring 注解
        }
        return null;
    }

    /**
     * 路径段编码：空格 → %20，保留 / 不变
     *
     * @param s 原始字符串
     * @return 编码后的路径段
     */
    private static String encodePathSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    /**
     * 去除字符串末尾的斜杠
     *
     * @param s 输入字符串
     * @return 处理后的字符串
     */
    private static String trimSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    // ==================== 内部类型 ====================

    /**
     * 参数注解类型枚举
     */
    enum ParamType {
        /**
         * 路径变量
         */
        PATH_VARIABLE,
        /**
         * 查询参数
         */
        REQUEST_PARAM,
        /**
         * 请求体
         */
        REQUEST_BODY,
        /**
         * 请求头
         */
        REQUEST_HEADER,
        /**
         * 未识别
         */
        UNKNOWN
    }

    /**
     * 参数注解解析结果
     */
    static class ParamAnnotation {
        /**
         * 注解类型
         */
        final ParamType type;
        /**
         * 参数名（来自注解 value/name，否则取形参名）
         */
        final String name;
        /**
         * 是否必传（仅 @RequestParam 有效）
         */
        final boolean required;
        /**
         * 默认值（仅 @RequestParam 有效）
         */
        final String defaultValue;

        /**
         * 构造（required=true, defaultValue=""）
         *
         * @param type 注解类型
         * @param name 参数名
         */
        ParamAnnotation(ParamType type, String name) {
            this(type, name, true, "");
        }

        /**
         * 构造
         *
         * @param type         注解类型
         * @param name         参数名
         * @param required     是否必传
         * @param defaultValue 默认值
         */
        ParamAnnotation(ParamType type, String name, boolean required, String defaultValue) {
            this.type = type;
            this.name = name;
            this.required = required;
            this.defaultValue = defaultValue;
        }
    }

    /**
     * 方法元数据缓存对象
     */
    static class MethodMetadata {
        /**
         * Java Method 引用
         */
        final Method method;
        /**
         * HTTP 方法
         */
        final HttpMethod httpMethod;
        /**
         * 路径模板（含占位符）
         */
        final String pathTemplate;
        /**
         * 形参注解解析结果数组
         */
        final ParamAnnotation[] paramAnnotations;

        /**
         * 构造
         *
         * @param method          Java Method 引用
         * @param httpMethod      HTTP 方法
         * @param pathTemplate    路径模板
         * @param paramAnnotations 形参注解数组
         */
        MethodMetadata(Method method, HttpMethod httpMethod, String pathTemplate,
                       ParamAnnotation[] paramAnnotations) {
            this.method = method;
            this.httpMethod = httpMethod;
            this.pathTemplate = pathTemplate;
            this.paramAnnotations = paramAnnotations;
        }
    }

    /**
     * 注解 → ParamAnnotation 转换函数式接口
     */
    @FunctionalInterface
    interface AnnResolver {
        /**
         * 将注解与名称转换为 ParamAnnotation
         *
         * @param annotation 注解实例
         * @param name       解析后的参数名（可能为 null）
         * @return ParamAnnotation
         */
        ParamAnnotation resolve(Annotation annotation, String name);
    }
}
