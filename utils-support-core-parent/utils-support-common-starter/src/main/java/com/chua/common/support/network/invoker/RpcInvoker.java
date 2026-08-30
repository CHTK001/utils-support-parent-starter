package com.chua.common.support.network.invoker;

import com.chua.common.support.network.annotations.RequestMethod;
import com.chua.common.support.network.invoker.annotations.InvokerService;
import com.chua.common.support.network.invoker.annotations.RemoteService;
import com.chua.common.support.network.rpc.RpcClient;
import com.chua.common.support.network.rpc.RpcConsumerConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.lang.annotation.Annotation;
import java.util.Collections;

/**
 * 基于 {@link RpcClient} SPI 的 RPC 调用器实现。
 *
 * <p>通过 {@code RpcClient.createClient()} 获取 RPC 客户端，支持 JSON-RPC、Dubbo、SOFA 等协议。
 * 接口级别通过 {@code @RequestMethod} 或 {@code @InvokerService} 注解指定注册中心地址。</p>
 *
 * <p>SPI 名称为 {@code "rpc"}，order=50，优先级高于 {@code HttpInvoker}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see RpcClient
 * @see Invoker
 */
@Spi(value = "rpc", order = 50)
public class RpcInvoker implements Invoker {

    /** Class_level_annotations */
    private static final String[] CLASS_LEVEL_ANNOTATIONS = {
            "org.springframework.web.bind.annotation.RequestMapping"
    };

    @Override
    /** 创建 */
    public <T> T create(Class<T> apiClass) {
        return createProxy(apiClass, false);
    }

    @Override
    /** 创建New */
    public <T> T createNew(Class<T> apiClass) {
        return createProxy(apiClass, true);
    }

    @SuppressWarnings("unchecked")
    /** 创建Proxy */
    private <T> T createProxy(Class<T> apiClass, boolean isNew) {
        String baseUrl = resolveBaseUrl(apiClass);
        if (StringUtils.isEmpty(baseUrl)) {
            throw new IllegalArgumentException("接口 " + apiClass.getName() + " 缺少 baseUrl，请标注 @RequestMethod 或 @RequestMapping");
        }

        RpcRegistryConfig registryConfig = new RpcRegistryConfig();
        registryConfig.setAddress(baseUrl);
        registryConfig.setProtocol("direct");

        RpcConsumerConfig consumerConfig = new RpcConsumerConfig();
        consumerConfig.setCheck(false);
        consumerConfig.setTimeout(30000);

        RpcClient client = RpcClient.createClient("json", Collections.singletonList(registryConfig), consumerConfig, "rpc-invoker");
        return client.get(apiClass);
    }

    /** 解析BaseUrl */
    private static String resolveBaseUrl(Class<?> clazz) {
        for (String annClass : CLASS_LEVEL_ANNOTATIONS) {
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

    /** ExtractAnnotationValue */
    private static String extractAnnotationValue(Annotation ann) {
        try {
            java.lang.reflect.Method m = ann.getClass().getMethod("value");
            Object r = m.invoke(ann);
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