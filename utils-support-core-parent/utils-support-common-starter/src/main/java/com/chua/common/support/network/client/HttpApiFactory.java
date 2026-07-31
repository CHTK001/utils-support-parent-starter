package com.chua.common.support.network.client;

import java.lang.reflect.Proxy;
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
 * @see HttpApiInvocationHandler
 */
public class HttpApiFactory {

    private static final ConcurrentMap<Class<?>, Object> PROXY_CACHE = new ConcurrentHashMap<>();

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
        if (!apiClass.isInterface()) {
            throw new IllegalArgumentException("只支持接口类型: " + apiClass.getName());
        }
        return (T) PROXY_CACHE.computeIfAbsent(apiClass, clazz ->
                Proxy.newProxyInstance(
                        clazz.getClassLoader(),
                        new Class[]{clazz},
                        new HttpApiInvocationHandler(clazz)
                )
        );
    }

    /**
     * 创建新代理实例（不缓存）。
     *
     * @param <T>      接口类型
     * @param apiClass 接口类
     * @return 新的动态代理实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T createNew(Class<T> apiClass) {
        if (!apiClass.isInterface()) {
            throw new IllegalArgumentException("只支持接口类型: " + apiClass.getName());
        }
        return (T) Proxy.newProxyInstance(
                apiClass.getClassLoader(),
                new Class[]{apiClass},
                new HttpApiInvocationHandler(apiClass)
        );
    }

    /**
     * 清空代理缓存。
     */
    public static void clearCache() {
        PROXY_CACHE.clear();
    }
}
