package com.chua.common.support.network.client;

import com.chua.common.support.reflection.ReflectUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
* 声明式 HTTP API 客户端工厂，为注解标注的接口生成 HTTP 客户端动态代理。
*
* <p>本类是模块的入口，完全复用已有基础设施：</p>
* <ul>
*   <li>{@link HttpClientFactory} / {@link HttpClientBuilder} — HTTP 请求执行</li>
*   <li>{@link com.chua.common.support.lang.placeholder.PlaceholderSupport} — 占位符解析</li>
*   <li>{@link com.chua.common.support.text.json.Json} — JSON 序列化/反序列化</li>
*   <li>Spring MVC 注解或 {@code @RequestMethod} — HTTP 方法和路径声明</li>
* </ul>
*
* <p><b>使用示例：</b></p>
* <pre>{@code
* // 1. 定义 API 接口
* \@RequestMethod("${api.base.url:http://localhost:8080}")
* public interface UserApi {
*
*     \@GetMapping("/api/users/{id}")
*     User getUser(@PathVariable("id") Long id, @RequestParam("fields") String fields);
*
*     \@PostMapping("/api/users")
*     User createUser(@RequestBody User user);
* }
*
* // 2. 创建代理
* UserApi api = HttpApiFactory.create(UserApi.class);
*
* // 3. 调用
* User user = api.getUser(1L, "name,email");
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see HttpApiInvocationHandler
 */
public class HttpApiFactory {

    private static final ConcurrentMap<Class<?>, Object> PROXY_CACHE = new ConcurrentHashMap<>();

    /** 创建 HttpApiFactory 实例 */
    private HttpApiFactory() {
    }

    /**
    * 创建接口的 HTTP API 客户端代理（带缓存）。
    *
    * @param <T>      接口类型
    * @param apiClass 接口类，方法上标注 {@code @GetMapping}、{@code @PostMapping}、
    *                 {@code @RequestMapping} 或 {@code @RequestMethod} 等注解
    * @return 动态代理实现
     */
@SuppressWarnings("unchecked")
    public static <T> T create(Class<T> apiClass) {
        return create(apiClass, null);
    }

    /**
    * 创建接口的 HTTP API 客户端代理（带缓存，支持自定义配置）。
    *
    * <p>通过 {@link HttpApiOptions} 指定自定义 baseUrl、底层 {@link HttpClient} 及拦截器，
    * 实现差异化集成与自定义。</p>
    *
    * <p><b>注意：</b>代理缓存以<b>接口类</b>为键，首次调用创建的代理会被复用。
    * 如需为同一接口使用<b>不同</b>配置（如不同 baseUrl/拦截器），请使用 {@link #createNew(Class, HttpApiOptions)}
    * 每次创建新代理，或在首次调用时就传入最终使用的配置。</p>
    *
    * @param <T>      接口类型
    * @param apiClass 接口类
    * @param options  自定义配置（baseUrl/客户端/拦截器），可为 null 表示使用默认配置
    * @return 动态代理实现
     */
    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> apiClass, HttpApiOptions options) {
        checkInterface(apiClass);
        HttpApiOptions safeOptions = options != null ? options : HttpApiOptions.of();
        return (T) PROXY_CACHE.computeIfAbsent(
                apiClass,
                clazz -> newProxy(clazz, safeOptions)
        );
    }

    /**
    * 创建新代理实例（不缓存）。
    *
    * @param <T>      接口类型
    * @param apiClass 接口类
    * @return 新的动态代理实例
     */
    public static <T> T createNew(Class<T> apiClass) {
        return createNew(apiClass, null);
    }

    /**
    * 创建新代理实例（不缓存，支持自定义配置）。
    *
    * <p>通过 {@link HttpApiOptions} 指定自定义 baseUrl、底层 {@link HttpClient} 及拦截器。
    * 每次调用都会创建新的代理实例，适用于需要为不同请求使用不同配置的场景。</p>
    *
    * @param <T>      接口类型
    * @param apiClass 接口类
    * @param options  自定义配置（baseUrl/客户端/拦截器），可为 null 表示使用默认配置
    * @return 新的动态代理实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T createNew(Class<T> apiClass, HttpApiOptions options) {
        checkInterface(apiClass);
        return (T) newProxy(
                apiClass,
                options != null ? options : HttpApiOptions.of()
        );
    }

    /**
    * 校验接口类型。
    *
    * @param apiClass 接口类
     */
    private static void checkInterface(Class<?> apiClass) {
        if (!apiClass.isInterface()) {
            throw new IllegalArgumentException("只支持接口类型: " + apiClass.getName());
        }
    }

    /**
    * 创建动态代理实例。
    *
    * @param clazz   接口类
    * @param options 自定义配置
    * @return 动态代理实例
     */
    private static Object newProxy(Class<?> clazz, HttpApiOptions options) {
        return ReflectUtils.newProxy(
                clazz.getClassLoader(),
                new Class[]{clazz},
                new HttpApiInvocationHandler(clazz, options)
        );
    }

    /**
    * 清空代理缓存。
     */
    public static void clearCache() {
        PROXY_CACHE.clear();
    }
}
