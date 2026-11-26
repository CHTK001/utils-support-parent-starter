package com.chua.okhttp.support.executor;

import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.reflection.ReflectUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.Buffer;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;

/** 无监听、无网络请求的真实 OkHttp 请求构建与配置消费回归。 */
public final class OkHttpClientConfigChecks {

    private static int checks;

    private OkHttpClientConfigChecks() {
    }

    public static void main(String[] args) throws Exception {
        OkHttpExecutor executor = new OkHttpExecutor();
        try {
            for (HttpMethod method : HttpMethod.values()) {
                ClientRequest input = request(method);
                Request output = (Request) call(executor, "toRequest", input);
                boolean required = method == HttpMethod.POST || method == HttpMethod.PUT
                        || method == HttpMethod.PATCH || method == HttpMethod.PROPPATCH;
                check("construct " + method, output.method().equals(method.name())
                        && (required ? output.body() != null && output.body().contentLength() == 0 : output.body() == null));
            }
            ClientRequest json = request(HttpMethod.POST);
            json.setBody("{\"value\":\"请求体\"}");
            json.getHeaders().add("content-type", "application/json; charset=utf-8");
            json.getHeaders().add("X-Request-Id", "config-check");
            Request jsonRequest = (Request) call(executor, "toRequest", json);
            Buffer jsonBytes = new Buffer();
            jsonRequest.body().writeTo(jsonBytes);
            check("POST string body survives conversion", json.getBody().equals(jsonBytes.readUtf8()));
            check("declared media type survives conversion", jsonRequest.body().contentType().toString()
                    .equals("application/json; charset=utf-8"));
            check("headers survive conversion", "config-check".equals(jsonRequest.header("X-Request-Id")));

            ClientRequest binary = request(HttpMethod.PUT);
            byte[] bytes = {0, 1, 2, (byte) 255};
            binary.setBody(bytes);
            Request binaryRequest = (Request) call(executor, "toRequest", binary);
            Buffer binaryBytes = new Buffer();
            binaryRequest.body().writeTo(binaryBytes);
            check("PUT binary body survives conversion", Arrays.equals(bytes, binaryBytes.readByteArray()));
            check("binary content type defaults correctly", binaryRequest.body().contentType().toString()
                    .equals("application/octet-stream"));

            ClientRequest config = request(HttpMethod.GET);
            config.setConnectTimeout(1432);
            config.setReadTimeout(2543);
            config.setWriteTimeout(3654);
            config.setFollowRedirects(false);
            config.setProxyHost("127.0.0.1");
            config.setProxyPort(8080);
            OkHttpClient base = (OkHttpClient) ReflectUtils.getField(executor, "client");
            OkHttpClient derived = (OkHttpClient) call(executor, "resolveClient", config);
            check("request connection timeout is consumed", derived.connectTimeoutMillis() == 1432);
            check("request read timeout is consumed", derived.readTimeoutMillis() == 2543);
            check("request write timeout is consumed", derived.writeTimeoutMillis() == 3654);
            check("redirect setting is consumed", !derived.followRedirects());
            check("request proxy is consumed", derived.proxy().type() == Proxy.Type.HTTP
                    && derived.proxy().address().equals(new InetSocketAddress("127.0.0.1", 8080)));
            check("derived clients share the pool", base.connectionPool() == derived.connectionPool());
            check("derived clients share the dispatcher", base.dispatcher() == derived.dispatcher());
            check("derived configuration does not mutate defaults", base.connectTimeoutMillis() == 30000
                    && base.readTimeoutMillis() == 30000 && base.followRedirects());
            System.out.println("RESULT okHttpClientChecks=" + checks + " fail=0 networkRequests=0");
        } finally {
            executor.close();
        }
    }

    private static ClientRequest request(HttpMethod method) {
        ClientRequest request = new ClientRequest();
        request.setUrl("https://example.invalid/config-check");
        request.setMethod(method);
        return request;
    }

    private static Object call(Object target, String name, ClientRequest request) throws Exception {
        var method = ReflectUtils.findDeclaredMethod(target.getClass(), name, ClientRequest.class);
        if (method == null) {
            throw new NoSuchMethodException(name);
        }
        try {
            return ReflectUtils.invokeProxy(target, method, new Object[]{request});
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
