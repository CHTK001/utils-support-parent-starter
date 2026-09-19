package com.chua.rpc.support.zmq;

import com.chua.common.support.network.annotations.RequestMethod;
import com.chua.common.support.network.invoker.Invoker;
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
 * 基于 zeromq RPC（jeromq）的 {@link Invoker} 实现。
 *
 * <p>通过 {@link RpcClient#createClient(String, java.util.List, RpcConsumerConfig, String)}
 * 以 {@code "zmq"} 协议创建 RPC 客户端代理，支持 {@code @RemoteService(protocol = "zmq")}
 * 注解接口的声明式远程调用。</p>
 *
 * <p>SPI 名称为 {@code "zmq"}，与 {@code RpcInvoker}（json）、RetrofitHttpInvoker（http）
 * 等平级，通过 {@code InvokerFactory.getInvoker("zmq")} 或接口标注
 * {@code @RemoteService(protocol = "zmq")} 自动路由。</p>
 *
 * <p><b>地址解析</b>：优先读取类级 {@code @RequestMapping} / {@code @RequestMethod} /
 * {@code @InvokerService} / {@code @RemoteService(url)} 注解中的端点地址；
 * 地址需为 ZMQ 端点格式（如 {@code tcp://127.0.0.1:5555}），或裸 {@code host:port}
 * 由 {@link ZmqRpcClient} 自动补全 {@code tcp://} 前缀。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see RpcClient
 * @see Invoker
 */
@Spi("zmq")
public class ZmqInvoker implements Invoker {

    /**
     * 类级地址注解集合（Spring MVC 请求mapping）
    */
    private static final String[] CLASS_LEVEL_ANNOTATIONS = {
            "org.springframework.web.bind.annotation.RequestMapping"
    };

    /**
     * 调用的注册中心配置协议
    */
    private static final String REGISTRY_PROTOCOL = "direct";

    /**
     * 客户端调用的应用名
    */
    private static final String APP_NAME = "zmq-invoker";

    @Override
    /**
     * 创建（带缓存）
    */
    public <T> T create(Class<T> apiClass) {
        return createProxy(apiClass, false);
    }

    @Override
    /**
     * 创建新（不缓存）
    */
    public <T> T createNew(Class<T> apiClass) {
        return createProxy(apiClass, true);
    }

    /**
     * 创建 ZMQ RPC 动态代理。
     *
     * @param apiClass 接口类型
     * @param isNew    是否创建新的 RPC 客户端（不复用缓存）
     * @param <T>      接口泛型
     * @return 远程代理实例
     */
    @SuppressWarnings("unchecked")
    private <T> T createProxy(Class<T> apiClass, boolean isNew) {
        String baseUrl = resolveBaseUrl(apiClass);
        if (StringUtils.isEmpty(baseUrl)) {
            throw new IllegalArgumentException("接口 " + apiClass.getName()
                    + " 缺少 baseUrl，请标注 @RequestMethod / @InvokerService / @RemoteService(url)");
        }

        RpcRegistryConfig registryConfig = new RpcRegistryConfig();
        registryConfig.setAddress(baseUrl);
        registryConfig.setProtocol(REGISTRY_PROTOCOL);

        RpcConsumerConfig consumerConfig = new RpcConsumerConfig();
        consumerConfig.setCheck(false);
        consumerConfig.setTimeout(30000);

        RpcClient client = RpcClient.createClient("zmq",
                Collections.singletonList(registryConfig), consumerConfig, APP_NAME);
        if (isNew) {
            return (T) client.get(apiClass);
        }
        return client.get(apiClass);
    }

    /**
     * 解析接口类级端点地址：按优先级读取 Spring MVC 请求mapping、
     * {@link RequestMethod}、{@link InvokerService}、{@link RemoteService#url()}。
     *
     * @param clazz 接口类型
     * @return 端点地址，未配置时返回空字符串
     */
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
                // 类路径缺少 Spring 注解时跳过
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
     * 提取注解 {@code value} 属性值（支持 字符串 与 字符串[] 类型）。
     *
     * @param ann 注解实例
     * @return 提取到的值，无法解析时返回空字符串
     */
    private static String extractAnnotationValue(Annotation ann) {
        Object r = ReflectUtils.invoke(ann, "value", Object.class);
        if (r instanceof String s) {
            return s;
        }
        if (r instanceof String[] a && a.length > 0) {
            return a[0];
        }
        return "";
    }
}
