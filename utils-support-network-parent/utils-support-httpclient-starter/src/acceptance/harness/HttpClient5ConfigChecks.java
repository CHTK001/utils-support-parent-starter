package com.chua.httpclient.support.executor;

import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.reflection.ReflectUtils;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** 无监听、无网络请求的 Apache HttpClient 5 请求构造与配置消费回归。 */
public final class HttpClient5ConfigChecks {
    private static int checks;
    private static int failures;

    private HttpClient5ConfigChecks() {
    }

    public static void main(String[] args) throws Exception {
        HttpClient5Executor executor = new HttpClient5Executor();
        try {
            for (HttpMethod method : HttpMethod.values()) {
                ClientRequest input = request(method);
                HttpUriRequestBase output = (HttpUriRequestBase) invoke(executor, "toRequest", input);
                check("construct " + method, output.getMethod().equals(method.name()));
            }

            ClientRequest json = request(HttpMethod.POST);
            json.setBody("{\"message\":\"你好\"}");
            json.getHeaders().add("Content-Type", "application/json; charset=UTF-8");
            json.getHeaders().add("X-Request-Id", "hc5-config-check");
            HttpUriRequestBase jsonRequest = (HttpUriRequestBase) invoke(executor, "toRequest", json);
            HttpEntity jsonEntity = jsonRequest.getEntity();
            ByteArrayOutputStream jsonBytes = new ByteArrayOutputStream();
            jsonEntity.writeTo(jsonBytes);
            check("Unicode string body remains UTF-8", "{\"message\":\"你好\"}".equals(
                    jsonBytes.toString(StandardCharsets.UTF_8)));
            check("explicit JSON content type is preserved", "application/json; charset=UTF-8".equals(
                    jsonRequest.getFirstHeader("Content-Type").getValue()));
            check("custom header is preserved", "hc5-config-check".equals(
                    jsonRequest.getFirstHeader("X-Request-Id").getValue()));

            ClientRequest binary = request(HttpMethod.PUT);
            byte[] body = {0, 1, 2, (byte) 255};
            binary.setBody(body);
            HttpUriRequestBase binaryRequest = (HttpUriRequestBase) invoke(executor, "toRequest", binary);
            ByteArrayOutputStream binaryBytes = new ByteArrayOutputStream();
            binaryRequest.getEntity().writeTo(binaryBytes);
            check("binary body is preserved", java.util.Arrays.equals(body, binaryBytes.toByteArray()));
            check("binary body receives default media type", "application/octet-stream".equals(
                    binaryRequest.getEntity().getContentType()));

            ClientRequest configRequest = request(HttpMethod.GET);
            configRequest.setConnectTimeout(1432);
            configRequest.setReadTimeout(2543);
            configRequest.setKeepAliveTimeout(3654);
            configRequest.setFollowRedirects(false);
            configRequest.setProxyHost("127.0.0.1");
            configRequest.setProxyPort(8080);
            HttpUriRequestBase configured = (HttpUriRequestBase) invoke(executor, "toRequest", configRequest);
            RequestConfig config = configured.getConfig();
            check("connect timeout is consumed", config.getConnectTimeout().toMilliseconds() == 1432);
            check("response timeout is consumed", config.getResponseTimeout().toMilliseconds() == 2543);
            check("keep-alive timeout is consumed", config.getConnectionKeepAlive().toMilliseconds() == 3654);
            check("redirect policy is consumed", !config.isRedirectsEnabled());
            HttpHost proxy = config.getProxy();
            check("proxy host and port are consumed", proxy != null && "127.0.0.1".equals(proxy.getHostName())
                    && proxy.getPort() == 8080);

            ClientRequest customType = request(HttpMethod.PROPPATCH);
            customType.setBody("<propertyupdate/>");
            customType.getHeaders().add("Content-Type", "application/xml; charset=UTF-8");
            HttpUriRequestBase customTypeRequest = (HttpUriRequestBase) invoke(executor, "toRequest", customType);
            check("WebDAV request retains entity", customTypeRequest.getEntity() != null);
            check("WebDAV request preserves declared media type", "application/xml; charset=UTF-8".equals(
                    customTypeRequest.getFirstHeader("Content-Type").getValue()));
        } finally {
            executor.close();
        }
        System.out.println("RESULT httpClient5Checks=" + checks + " fail=" + failures + " networkRequests=0");
        if (failures != 0) {
            throw new AssertionError("HttpClient5 checks failed=" + failures);
        }
    }

    private static ClientRequest request(HttpMethod method) {
        ClientRequest request = new ClientRequest();
        request.setUrl("https://example.invalid/config-check");
        request.setMethod(method);
        return request;
    }

    private static Object invoke(Object target, String name, ClientRequest request) throws Exception {
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
        checks++;
        if (!success) {
            failures++;
        }
        System.out.println((success ? "PASS " : "FAIL ") + label);
    }
}
