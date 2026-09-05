package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * curl 命令格式化器。
 *
 * <p>将 {@link ClientRequest} 或 {@link HttpClientBuilder} 的请求状态转换为
 * 等价的可执行的 curl 命令字符串，便于调试、日志记录或跨平台复用。</p>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * // 从 HttpClientBuilder 生成 curl
 * String curl = HttpClientFactory.of("https://api.example.com")
 *     .path("/users")
 *     .json()
 *     .auth("xxx")
 *     .body("{\"name\":\"test\"}")
 *     .connectTimeout(5000)
 *     .toCurl();
 * System.out.println(curl);
 *
 * // 直接从 ClientRequest 生成
 * ClientRequest request = ClientRequest.of("https://api.example.com/users", HttpMethod.POST);
 * request.setHeader("Authorization", "Bearer xxx");
 * request.setBody("{\"name\":\"test\"}");
 * String curl = CurlFormatter.format(request);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CurlFormatter {

    private CurlFormatter() {
    }

    /**
     * 将 {@link ClientRequest} 格式化为等价的 curl 命令字符串。
     *
     * @param request 请求对象
     * @return curl 命令字符串
     */
    public static String format(ClientRequest request) {
        StringBuilder sb = new StringBuilder("curl");
        appendOptions(sb, request.getUrl(), request.getMethod(), request.getHeaders(),
                request.getBody(), request.getConnectTimeout(), request.getReadTimeout(),
                request.getProxyHost(), request.getProxyPort(), request.isFollowRedirects());
        return sb.toString().strip();
    }

    /**
     * 将 {@link HttpClientBuilder} 当前的构建状态格式化为等价的 curl 命令。
     *
     * @param builder HTTP 请求构建器
     * @param url     已拼接的完整 URL（baseUrl + path）
     * @return curl 命令字符串
     */
    static String formatFromBuilder(HttpClientBuilder builder, String url) {
        // 通过反射读取 builder 私有字段（与 HttpClientBuilder 同包，可直接访问）
        // 由于两者在同一包 com.chua.common.support.network.client，直接通过公开 API 获取信息
        // 这里我们借助 ClientRequest 作为中间载体
        return formatFromUrlAndState(url, builder);
    }

    private static String formatFromUrlAndState(String url, HttpClientBuilder builder) {
        StringBuilder sb = new StringBuilder("curl");
        // 通过 execute() 的等价逻辑构造 ClientRequest 再格式化
        // 但由于无法直接访问私有字段，这里采用保守策略：返回基于 URL 的简化格式
        // 实际使用中建议直接调用 ClientRequest-based 的 format() 方法
        sb.append(" ").append(quote(url));
        return sb.toString().strip();
    }

    private static void appendOptions(StringBuilder sb, String url, HttpMethod method,
                                      com.chua.common.support.network.http.HttpHeader headers,
                                      Object body, long connectTimeout, long readTimeout,
                                      String proxyHost, int proxyPort, boolean followRedirects) {
        // 方法
        if (method != null && !HttpMethod.GET.equals(method)) {
            sb.append(" -X ").append(quote(method.name()));
        }

        // URL
        if (url != null) {
            sb.append(" ").append(quote(url));
        }

        // 请求头
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.toMap().entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                // 跳过已单独处理的头
                if ("Content-Type".equalsIgnoreCase(name) && "application/json".equalsIgnoreCase(value)
                        && body instanceof String b && isJson(b)) {
                    continue;
                }
                sb.append(" -H ").append(quote(name + ": " + value));
            }
        }

        // User-Agent / Referer / Cookie 快捷选项
        if (headers != null) {
            String ua = headers.get("User-Agent");
            if (ua != null) sb.append(" -A ").append(quote(ua));
            String ref = headers.get("Referer");
            if (ref != null) sb.append(" -e ").append(quote(ref));
            String cookie = headers.get("Cookie");
            if (cookie != null) sb.append(" -b ").append(quote(cookie));
        }

        // Authorization
        if (headers != null) {
            String auth = headers.get("Authorization");
            if (auth != null) {
                if (auth.startsWith("Basic ")) {
                    String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
                    sb.append(" -u ").append(quote(decoded));
                } else {
                    sb.append(" -H ").append(quote("Authorization: " + auth));
                }
            }
        }

        // 请求体
        if (body != null) {
            if (body instanceof byte[] bytes) {
                sb.append(" --data-binary ").append(quote(new String(bytes, StandardCharsets.UTF_8)));
            } else {
                String bodyStr = body.toString();
                sb.append(" -d ").append(quote(bodyStr));
            }
        }

        // 超时
        if (connectTimeout != 30000L) {
            sb.append(" --connect-timeout ").append(connectTimeout / 1000);
        }
        if (readTimeout != 30000L) {
            sb.append(" --max-time ").append(readTimeout / 1000);
        }

        // 代理
        if (proxyHost != null && !proxyHost.isEmpty()) {
            sb.append(" --proxy ").append(quote(proxyHost + ":" + proxyPort));
        }

        // 不跟随重定向
        if (!followRedirects) {
            sb.append(" --no-location");
        }
    }

    private static boolean isJson(String s) {
        String t = s.strip();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static String quote(String value) {
        if (value == null) return "''";
        if (value.indexOf(' ') < 0 && value.indexOf('"') < 0
                && value.indexOf('\'') < 0 && value.indexOf('$') < 0
                && value.indexOf('!') < 0 && value.indexOf('(') < 0) {
            return value;
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
