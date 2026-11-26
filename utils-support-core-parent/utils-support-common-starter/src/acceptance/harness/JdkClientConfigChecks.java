package com.chua.common.support.network.client;

import com.chua.common.support.reflection.ReflectUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * 无监听、无外部请求的 JDK 客户端配置、复用和资源边界回归。
 * 不作为网络吞吐或实际超时时长的证据。
 */
public final class JdkClientConfigChecks {

    private static int checks;

    private JdkClientConfigChecks() {
    }

    public static void main(String[] args) throws Exception {
        JdkHttpClientExecutor executor = new JdkHttpClientExecutor();
        List<HttpClient> retained = new ArrayList<>();
        try {
            Object defaults = lease(executor, request(30000));
            HttpClient defaultClient = client(defaults);
            retained.add(defaultClient);
            check("default connection timeout", defaultClient.connectTimeout().orElseThrow().toMillis() == 30000);
            check("default redirects", defaultClient.followRedirects() == HttpClient.Redirect.NORMAL);
            check("default lease is reused", defaults == lease(executor, request(30000)));

            ClientRequest keepAlive = request(30000);
            keepAlive.setKeepAliveTimeout(1);
            check("unsupported keep-alive does not split pools", defaults == lease(executor, keepAlive));
            ClientRequest irrelevantPort = request(30000);
            irrelevantPort.setProxyPort(1234);
            check("proxy port without host does not split pools", defaults == lease(executor, irrelevantPort));

            ClientRequest noRedirect = request(30000);
            noRedirect.setFollowRedirects(false);
            HttpClient direct = client(lease(executor, noRedirect));
            retained.add(direct);
            check("redirect settings remain isolated", direct != defaultClient
                    && direct.followRedirects() == HttpClient.Redirect.NEVER);

            Object hot = lease(executor, request(1471));
            HttpClient hotClient = client(hot);
            retained.add(hotClient);
            check("explicit connection timeout is consumed", hotClient.connectTimeout().orElseThrow().toMillis() == 1471);
            check("custom configuration is reused", hot == lease(executor, request(1471)));
            ((AutoCloseable) hot).close();
            check("returning a cached lease keeps pool open", !hotClient.isTerminated());

            ClientRequest proxy = request(1500);
            proxy.setProxyHost("127.0.0.1");
            proxy.setProxyPort(8080);
            HttpClient proxyClient = client(lease(executor, proxy));
            retained.add(proxyClient);
            Object address = proxyClient.proxy().orElseThrow().select(URI.create("http://example.invalid/")).getFirst().address();
            check("proxy and timeout are consumed", address.equals(new InetSocketAddress("127.0.0.1", 8080))
                    && proxyClient.connectTimeout().orElseThrow().toMillis() == 1500);

            try (var workers = Executors.newFixedThreadPool(8)) {
                List<Callable<Object>> same = new ArrayList<>();
                for (int i = 0; i < 64; i++) {
                    same.add(() -> lease(executor, request(1700)));
                }
                var shared = workers.invokeAll(same);
                Object first = shared.getFirst().get();
                boolean one = true;
                for (var value : shared) {
                    one &= value.get() == first;
                }
                check("concurrent same configuration has one client", one);
                List<Callable<Boolean>> pressure = new ArrayList<>();
                for (int i = 0; i < 48; i++) {
                    final int timeout = 2000 + i;
                    pressure.add(() -> {
                        Object handle = lease(executor, request(timeout));
                        HttpClient selected = client(handle);
                        boolean temporary = (boolean) call(handle, "temporary");
                        ((AutoCloseable) handle).close();
                        return !temporary || selected.awaitTermination(Duration.ofSeconds(5));
                    });
                }
                boolean released = true;
                for (var value : workers.invokeAll(pressure)) {
                    released &= value.get();
                }
                check("overflow clients are released", released);
            }
            Map<?, ?> cache = (Map<?, ?>) ReflectUtils.getField(executor, "customClients");
            check("concurrent configuration admission stays bounded", cache.size() == 16);
            check("overflow preserves hot pool identity", hot == lease(executor, request(1471)));
            check("overflow does not shut down hot pool", !hotClient.isTerminated());
            for (Object handle : cache.values()) {
                retained.add(client(handle));
            }
            executor.close();
            boolean terminated = true;
            for (HttpClient selected : retained) {
                terminated &= selected.awaitTermination(Duration.ofSeconds(5));
            }
            check("close releases all retained clients", terminated);
            check("close clears custom references", cache.isEmpty());
            executor.close();
            check("close is idempotent", true);
            boolean rejected = false;
            try {
                lease(executor, request(30000));
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            check("closed executor refuses new clients", rejected);
            System.out.println("RESULT jdkClientChecks=" + checks + " fail=0 networkRequests=0");
        } finally {
            executor.close();
        }
    }

    private static ClientRequest request(int timeout) {
        ClientRequest request = new ClientRequest();
        request.setConnectTimeout(timeout);
        return request;
    }

    private static Object lease(JdkHttpClientExecutor executor, ClientRequest request) throws Exception {
        return call(executor, "resolveHttpClient", request);
    }

    private static HttpClient client(Object lease) throws Exception {
        return (HttpClient) call(lease, "client");
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i].getClass();
        }
        var method = ReflectUtils.findDeclaredMethod(target.getClass(), name, types);
        if (method == null) {
            throw new NoSuchMethodException(name);
        }
        try {
            return ReflectUtils.invokeProxy(target, method, args);
        } catch (Exception failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
    }

    private static void check(String label, boolean success) {
        if (!success) {
            throw new AssertionError(label);
        }
        checks++;
        System.out.println("PASS " + label);
    }
}
