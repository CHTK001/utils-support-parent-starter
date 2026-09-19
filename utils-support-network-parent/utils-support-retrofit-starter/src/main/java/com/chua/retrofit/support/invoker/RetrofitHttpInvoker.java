package com.chua.retrofit.support.invoker;

import com.chua.common.support.network.annotations.RequestMethod;
import com.chua.common.support.network.invoker.Invoker;
import com.chua.common.support.network.invoker.annotations.RemoteService;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.lang.annotation.Annotation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 Retrofit2 的 HTTP 调用器实现。
 *
 * <p>使用 Retrofit2 框架为接口生成 HTTP 客户端代理，支持 Retrofit 原生注解
 * （{@code @GET}、{@code @POST}、{@code @Path}、{@code @Query} 等）以及
 * {@code @RequestMethod} 类级注解解析 baseUrl。</p>
 *
 * <p>SPI 名称为 {@code "http"}，order=100 优先级高于默认的 {@code HttpInvoker}，
 * 当 类路径 中存在 Retrofit2 依赖时自动生效。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see Invoker
 * @see Retrofit
 */
@Slf4j
@Spi(value = "http", order = 100)
@ConditionalOnClass("retrofit2.Retrofit")
public class RetrofitHttpInvoker implements Invoker {

    /**
     * 代理 缓存
     */
    private static final ConcurrentMap<Class<?>, Object> PROXY_CACHE = new ConcurrentHashMap<>();

    /**
     * 类 级别 注解
     */
    private static final String[] CLASS_LEVEL_ANNOTATIONS = {
            "org.springframework.web.bind.annotation.RequestMapping"
    };

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 创建
     *
     * @param apiClass api类
     * @return 创建的结果
     */
    public <T> T create(Class<T> apiClass) {
        if (!apiClass.isInterface()) {
            throw new IllegalArgumentException("只支持接口类型: " + apiClass.getName());
        }
        return (T) PROXY_CACHE.computeIfAbsent(apiClass, this::createProxy);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 创建新
     *
     * @param apiClass api类
     * @return 创建新的结果
     */
    public <T> T createNew(Class<T> apiClass) {
        if (!apiClass.isInterface()) {
            throw new IllegalArgumentException("只支持接口类型: " + apiClass.getName());
        }
        return (T) createProxy(apiClass);
    }

    /**
     * 为接口创建 Retrofit 动态代理。
     *
     * @param apiClass 接口类
     * @return Retrofit 动态代理实例
     */
    private <T> T createProxy(Class<T> apiClass) {
        String baseUrl = resolveBaseUrl(apiClass);
        if (StringUtils.isEmpty(baseUrl)) {
            throw new IllegalArgumentException("接口 " + apiClass.getName() + " 缺少 baseUrl，请标注 @RequestMethod 或 @RequestMapping");
        }
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(JacksonConverterFactory.create())
                .build();
        return retrofit.create(apiClass);
    }

    /**
     * 解析接口类级注解中的 baseurl。
     * 优先尝试 Spring {@code @RequestMapping}，回退到 {@code @RequestMethod}。
     *
     * @param clazz 接口类
     * @return 解析后的 baseurl，未声明返回空串
     */
    private static String resolveBaseUrl(Class<?> clazz) {
        // 优先尝试 Spring 类级注解
        for (String annClass : CLASS_LEVEL_ANNOTATIONS) {
            try {
                Class<?> cl = ReflectUtils.forName(annClass);
                Annotation ann = clazz.getAnnotation(cl.asSubclass(Annotation.class));
                if (ann != null) {
                    String v = extractAnnotationValue(ann);
                    if (!StringUtils.isEmpty(v)) {
                        return normalizeBaseUrl(v);
                    }
                }
            } catch (Exception ignored) {
                // 类路径中无 Spring 注解，跳过
            }
        }
        // 回退到项目自有注解
        RequestMethod rm = clazz.getAnnotation(RequestMethod.class);
        if (rm != null && !StringUtils.isEmpty(rm.value())) {
            return normalizeBaseUrl(rm.value());
        }
        RemoteService rs = clazz.getAnnotation(RemoteService.class);
        if (rs != null && !StringUtils.isEmpty(rs.url())) {
            return normalizeBaseUrl(rs.url());
        }
        return "";
    }

    /**
     * 标准化 baseurl：确保以 / 结尾（Retrofit 要求）。
     *
     * @param url 原始 URL
     * @return 以 / 结尾的 URL
     */
    private static String normalizeBaseUrl(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    /**
     * 反射读取注解的 值 属性。
     *
     * @param ann 注解实例
     * @return value 值，无值返回空串
     */
    private static String extractAnnotationValue(Annotation ann) {
        try {
            Object r = ReflectUtils.invoke(ann, "value", Object.class);
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
}
