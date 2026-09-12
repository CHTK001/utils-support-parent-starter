package com.chua.rpc.support.json;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.proxy.ProxyUtils;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.intercept.DelegateMethodIntercept;
import com.chua.common.support.network.rpc.RpcClient;
import com.chua.common.support.network.rpc.RpcConsumerConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
* JSON-RPC 2.0 客户端实现。
*
* <p>与 {@link JsonRpcServer} 的多服务路由配套：每个目标接口持有独立的
* {@link JsonRpcHttpClient}，并在请求体中以 {@code service} 字段携带接口全限定名，
* 供服务端路由到对应的 处理器。版本 / 分组以请求头 {@code X-RPC-Version} /
* {@code X-RPC-Group} 传递，服务端据此做服务治理校验。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("json")
@Slf4j
public class JsonRpcClient implements RpcClient {

    /**
    * 未配置时的默认重试间隔（毫秒）
     */
    private static final int DEFAULT_RETRY_DELAY = 100;

    /**
    * 目标服务地址
     */
    private final URL serviceUrl;

    /**
    * 消费者全局配置
     */
    private final RpcConsumerConfig consumerConfig;

    /**
    * 目标接口 → 独立 HTTP 客户端（避免 {@code service} 路由字段跨接口串扰）
     */
    private final Map<Class<?>, JsonRpcHttpClient> clientCache = new ConcurrentHashMap<>();

    /**
    * 目标接口 → 动态代理缓存
     */
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

    /**
    * 创建 jsonrpc客户端 实例
    * @param rpcRegistryConfigs rpcregistry配置
    * @param consumerConfig rpcconsumer配置
    * @param name 字符串
    * @param consumerConfig consumer配置
    * @param name 名称
     */
    public JsonRpcClient(List<RpcRegistryConfig> rpcRegistryConfigs, RpcConsumerConfig consumerConfig, String name) {
        this.consumerConfig = consumerConfig;
        String address = rpcRegistryConfigs != null && !rpcRegistryConfigs.isEmpty()
                ? rpcRegistryConfigs.get(0).getAddress() : "http://localhost:8080";
        String urlStr = address.startsWith("http") ? address : "http://" + address;
        try {
            this.serviceUrl = new URL(urlStr);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid JSON-RPC URL: " + urlStr, e);
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
    * 获取
    *
    * @param targetType Target类型
    * @return 获取的结果
     */
    public <T> T get(Class<T> targetType) {
        return (T) proxyCache.computeIfAbsent(targetType, type -> {
            Class<T> t = (Class<T>) type;
            return ProxyUtils.newProxy(t, t.getClassLoader(),
                    new DelegateMethodIntercept<>(t, new RpcInvoker(t)));
        });
    }

    /**
    * 获取（或创建）目标接口对应的独立 HTTP 客户端。
    *
    * @param targetType 目标接口
    * @return 独立客户端实例
     */
    private JsonRpcHttpClient ensureClient(Class<?> targetType) {
        return clientCache.computeIfAbsent(targetType, type -> {
            JsonRpcHttpClient client = new JsonRpcHttpClient(serviceUrl);
            client.setConnectionTimeoutMillis(defaultConnectTimeout());
            client.setReadTimeoutMillis(defaultTimeout());
            return client;
        });
    }

    /**
    * 读取连接超时（毫秒），未配置时取默认 {@code 3000}。
    *
    * @return 连接超时毫秒数
     */
    private int defaultConnectTimeout() {
        return consumerConfig != null && consumerConfig.getConnectTimeout() != null
                ? consumerConfig.getConnectTimeout() : 3000;
    }

    /**
    * 读取调用超时（毫秒），未配置时取默认 {@code 10000}。
    *
    * @return 调用超时毫秒数
     */
    private int defaultTimeout() {
        return consumerConfig != null && consumerConfig.getTimeout() != null
                ? consumerConfig.getTimeout() : 10000;
    }

    /**
    * 远程调用实现：携带 {@code service} 路由字段 + 服务治理请求头，
    * 并按消费者配置进行有限次网络重试。
    * @author CH
    * @since 4.0.0
     */
    private class RpcInvoker implements Function<ProxyMethod, Object> {

        /**
        * 目标接口（用于路由与超时配置）
         */
        private final Class<?> targetType;

        RpcInvoker(Class<?> targetType) {
            this.targetType = targetType;
        }

        @Override
        /** 应用 */
        public Object apply(ProxyMethod proxyMethod) {
            int maxRetries = consumerConfig != null && Boolean.FALSE.equals(consumerConfig.getRetryEnabled())
                    ? 0 : (consumerConfig != null && consumerConfig.getRetries() != null ? consumerConfig.getRetries() : 0);
            int retryDelay = consumerConfig != null && consumerConfig.getRetryDelay() != null
                    ? consumerConfig.getRetryDelay() : DEFAULT_RETRY_DELAY;
            Throwable lastError = null;
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    return doInvoke(proxyMethod);
                } catch (Throwable e) {
                    Throwable cause = unwrap(e);
                    // 业务异常：直接抛出，不重试（避免放大非幂等接口副作用）
                    if (cause instanceof com.googlecode.jsonrpc4j.JsonRpcClientException) {
                        throw com.chua.common.support.network.rpc.RpcException.business(
                                "JSON-RPC business failure: " + proxyMethod.getMethod().getName(), e);
                    }
                    lastError = e;
                    if (attempt < maxRetries) {
                        log.warn("JSON-RPC retry {}/{}, method={}", attempt + 1, maxRetries, proxyMethod.getMethod().getName());
                        try {
                            Thread.sleep(retryDelay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            throw com.chua.common.support.network.rpc.RpcException.transport(
                    "JSON-RPC failed: " + proxyMethod.getMethod().getName(), lastError);
        }

        /**
        * 解包异常链，找到最底层原因（jsonrpc4j 会把业务异常包装为 jsonrpc客户端异常）。
        *
        * @param throwable 原始异常
        * @return 最底层异常
         */
        private Throwable unwrap(Throwable throwable) {
            Throwable cur = throwable;
            while (cur.getCause() != null && cur.getCause() != cur) {
                cur = cur.getCause();
            }
            return cur;
        }

        /**
        * 执行调用
        *
        * @param proxyMethod 代理方法
        * @return 执行invoke的结果
         */
        private Object doInvoke(ProxyMethod proxyMethod) throws Throwable {
            JsonRpcHttpClient client = ensureClient(targetType);
            Map<String, String> headers = buildHeaders();
            if (!headers.isEmpty()) {
                client.setHeaders(headers);
            }
            Map<String, Object> additional = new HashMap<>(2);
            additional.put(JsonRpcServer.SERVICE_FIELD, targetType.getName());
            client.setAdditionalJsonContent(additional);
            Method method = proxyMethod.getMethod();
            return client.invoke(method.getName(), proxyMethod.getArgs(), method.getReturnType());
        }
    }

    /**
    * 构造服务治理请求头（版本 / 分组 / 令牌）。
    *
    * @return 请求头集合，可能为空
     */
    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>(16);
        if (consumerConfig != null) {
            if (consumerConfig.getVersion() != null) {
                headers.put(JsonRpcServer.HEADER_VERSION, consumerConfig.getVersion());
            }
            if (consumerConfig.getGroup() != null) {
                headers.put(JsonRpcServer.HEADER_GROUP, consumerConfig.getGroup());
            }
            if (consumerConfig.getToken() != null) {
                headers.put(JsonRpcServer.HEADER_TOKEN, consumerConfig.getToken());
            }
        }
        return headers;
    }

    @Override
    /** 关闭 */
    public void close() {
        clientCache.clear();
        proxyCache.clear();
        log.info("JsonRpcClient closed");
    }
}
