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
 * @author CH
 * @since 1.0.0
 */
@Spi("json")
@Slf4j
public class JsonRpcClient implements RpcClient {

    private static final int DEFAULT_RETRY_DELAY = 100;

    private final URL serviceUrl;
    private final RpcConsumerConfig consumerConfig;
    private volatile JsonRpcHttpClient client;
    private final Map<Class<?>, Object> proxyCache = new ConcurrentHashMap<>();

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
    public <T> T get(Class<T> targetType) {
        return (T) proxyCache.computeIfAbsent(targetType, type -> {
            Class<T> t = (Class<T>) type;
            return ProxyUtils.newProxy(t, t.getClassLoader(),
                    new DelegateMethodIntercept<>(t, new RpcInvoker()));
        });
    }

    private class RpcInvoker implements Function<ProxyMethod, Object> {
        @Override
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
                    lastError = e;
                    if (attempt < maxRetries) {
                        log.warn("JSON-RPC retry {}/{}, method={}", attempt + 1, maxRetries, proxyMethod.getMethod().getName());
                        try { Thread.sleep(retryDelay); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            throw new IllegalStateException("JSON-RPC failed: " + proxyMethod.getMethod().getName(), lastError);
        }
    }

    private Object doInvoke(ProxyMethod proxyMethod) throws Throwable {
        ensureClient();
        client.setHeaders(buildHeaders());
        Method method = proxyMethod.getMethod();
        return client.invoke(method.getName(), proxyMethod.getArgs(), method.getReturnType());
    }

    private void ensureClient() {
        if (null == client) {
            synchronized (this) {
                if (null == client) {
                    client = new JsonRpcHttpClient(serviceUrl);
                }
            }
        }
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>(16);
        if (consumerConfig != null) {
            if (consumerConfig.getVersion() != null) { headers.put("X-RPC-Version", consumerConfig.getVersion()); }
            if (consumerConfig.getGroup() != null)   { headers.put("X-RPC-Group", consumerConfig.getGroup()); }
        }
        return headers;
    }

    @Override
    public void close() {
        client = null;
        proxyCache.clear();
        log.info("JsonRpcClient closed");
    }
}