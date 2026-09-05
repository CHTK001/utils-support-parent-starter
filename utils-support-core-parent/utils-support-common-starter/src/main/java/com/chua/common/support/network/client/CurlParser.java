package com.chua.common.support.network.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * curl 命令解析器。
 *
 * <p>将 curl 命令字符串解析为 {@link HttpClientBuilder}，支持以下选项：</p>
 * <ul>
 *   <li><b>方法：</b>{@code -X}, {@code --request}, {@code -G}, {@code --get}, {@code -I}, {@code --head}</li>
 *   <li><b>请求体：</b>{@code -d}, {@code --data}, {@code --data-raw}, {@code --data-binary}, {@code --data-ascii}, {@code --data-urlencode}</li>
 *   <li><b>请求头：</b>{@code -H}, {@code --header}, {@code -A}, {@code --user-agent}, {@code -e}, {@code --referer}, {@code -b}, {@code --cookie}</li>
 *   <li><b>认证：</b>{@code -u}, {@code --user}（Basic Auth）</li>
 *   <li><b>超时：</b>{@code --connect-timeout}, {@code --max-time}</li>
 *   <li><b>代理：</b>{@code --proxy}, {@code --proxy-user}</li>
 *   <li><b>重定向：</b>{@code -L}, {@code --location}</li>
 *   <li><b>SSL：</b>{@code -k}, {@code --insecure}</li>
 *   <li><b>表单/上传：</b>{@code --form}, {@code -F}, {@code -T}, {@code --upload-file}</li>
 *   <li><b>其他：</b>{@code --compressed}, {@code --url}</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * HttpClientBuilder builder = CurlParser.fromCurl(
 *     "curl -X POST https://api.example.com/users " +
 *     "-H 'Content-Type: application/json' " +
 *     "-H 'Authorization: Bearer xxx' " +
 *     "-d '{\"name\":\"test\"}'"
 * );
 * ClientResponse resp = builder.post();
 *
 * // 或直接执行
 * ClientResponse resp = CurlParser.curl(
 *     "curl https://api.example.com/users?page=1"
 * );
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CurlParser {

    private static final Pattern DATA_URL_ENCODE_PATTERN = Pattern.compile("^([^=]+)=(.*)$");

    private CurlParser() {
    }

    /**
     * 将 curl 命令字符串解析为 {@link HttpClientBuilder}。
     *
     * @param curl curl 命令字符串，可以是完整命令（含 {@code curl} 前缀），也可以是选项部分
     * @return 构建好的 HttpClientBuilder，可用于链式配置后执行
     */
    public static HttpClientBuilder fromCurl(String curl) {
        if (curl == null || curl.isBlank()) {
            throw new IllegalArgumentException("curl command must not be blank");
        }
        String raw = curl.stripLeading().startsWith("curl") ? curl.stripLeading() : "curl " + curl;
        List<String> tokens = tokenize(raw);
        BuilderState state = new BuilderState();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("curl".equals(token) || "--".equals(token)) continue;
            i = consumeToken(token, tokens, i, state);
        }
        if (state.baseUrl == null) {
            throw new IllegalArgumentException("curl command must contain a URL");
        }
        HttpClientBuilder builder = new HttpClientBuilder(state.baseUrl);
        applyState(builder, state);
        return builder;
    }

    /**
     * 直接执行 curl 命令并返回响应。
     *
     * @param curl curl 命令字符串
     * @return HTTP 响应
     */
    public static ClientResponse curl(String curl) {
        return fromCurl(curl).execute();
    }

    // ==================== Token 解析 ====================

    static List<String> tokenize(String command) {
        String normalized = command.replaceAll("(?m)\\\\\n\\s*", " ");
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && !inSingleQuote) {
                escaped = true;
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (!inDoubleQuote && !inSingleQuote && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static int consumeToken(String token, List<String> tokens, int idx, BuilderState state) {
        if (token.startsWith("-") && token.length() == 2 && !token.startsWith("--")) {
            return handleShortOption(token, tokens, idx, state);
        }
        switch (token) {
            case "-X":
            case "--request":
                state.method = up(next(tokens, idx));
                break;
            case "-H":
            case "--header": {
                String h = next(tokens, idx);
                int colon = h.indexOf(':');
                if (colon > 0) {
                    state.headers.put(h.substring(0, colon).strip(), h.substring(colon + 1).strip());
                } else {
                    state.headers.put(h, "");
                }
                break;
            }
            case "-A":
            case "--user-agent":
                state.headers.put("User-Agent", next(tokens, idx));
                break;
            case "-e":
            case "--referer":
                state.headers.put("Referer", next(tokens, idx));
                break;
            case "-b":
            case "--cookie":
                state.headers.put("Cookie", next(tokens, idx));
                break;
            case "-u":
            case "--user": {
                String auth = next(tokens, idx);
                int colon = auth.indexOf(':');
                if (colon > 0) {
                    state.basicAuth = auth;
                } else {
                    state.bearerToken = auth;
                }
                break;
            }
            case "-d":
            case "--data":
            case "--data-raw":
            case "--data-binary":
            case "--data-ascii":
                state.body = next(tokens, idx);
                state.bodyKind = BodyKind.DATA;
                break;
            case "--data-urlencode": {
                String d = next(tokens, idx);
                Matcher m = DATA_URL_ENCODE_PATTERN.matcher(d);
                if (m.matches()) {
                    if (state.formData == null) state.formData = new LinkedHashMap<>();
                    state.formData.put(java.net.URLEncoder.encode(m.group(1), StandardCharsets.UTF_8),
                            java.net.URLEncoder.encode(m.group(2), StandardCharsets.UTF_8));
                } else {
                    state.body = d;
                    state.bodyKind = BodyKind.DATA;
                }
                break;
            }
            case "-G":
            case "--get":
                state.getFlag = true;
                break;
            case "-I":
            case "--head":
                state.method = "HEAD";
                break;
            case "-L":
            case "--location":
                state.followRedirects = true;
                break;
            case "-k":
            case "--insecure":
                state.insecure = true;
                break;
            case "--connect-timeout":
                state.connectTimeout = Long.parseLong(next(tokens, idx));
                break;
            case "--max-time":
                state.readTimeout = Long.parseLong(next(tokens, idx)) * 1000;
                break;
            case "--proxy": {
                String proxy = next(tokens, idx);
                int pp = proxy.lastIndexOf(':');
                if (pp > 0) {
                    state.proxyHost = proxy.substring(0, pp);
                    state.proxyPort = Integer.parseInt(proxy.substring(pp + 1));
                } else {
                    state.proxyHost = proxy;
                    state.proxyPort = 8080;
                }
                break;
            }
            case "--proxy-user": {
                String pu = next(tokens, idx);
                int pc = pu.indexOf(':');
                if (pc > 0) {
                    state.proxyAuth = pu;
                }
                break;
            }
            case "-F":
            case "--form": {
                String f = next(tokens, idx);
                if (state.formFields == null) state.formFields = new ArrayList<>();
                state.formFields.add(f);
                break;
            }
            case "-T":
            case "--upload-file":
                state.uploadFile = next(tokens, idx);
                break;
            case "--compressed":
                state.headers.put("Accept-Encoding", "gzip, deflate");
                break;
            case "--url":
                state.baseUrl = next(tokens, idx);
                break;
            default:
                if (!token.startsWith("-")) {
                    if (state.baseUrl == null) state.baseUrl = token;
                }
                break;
        }
        return idx;
    }

    private static int handleShortOption(String opt, List<String> tokens, int idx, BuilderState state) {
        String arg;
        switch (opt) {
            case "-X":
            case "--request":
                state.method = up(next(tokens, ++idx));
                break;
            case "-H":
            case "--header":
                arg = next(tokens, ++idx);
                int colon = arg.indexOf(':');
                if (colon > 0) {
                    state.headers.put(arg.substring(0, colon).strip(), arg.substring(colon + 1).strip());
                } else {
                    state.headers.put(arg, "");
                }
                break;
            case "-d":
            case "--data":
            case "--data-raw":
            case "--data-binary":
            case "--data-ascii":
                state.body = next(tokens, ++idx);
                state.bodyKind = BodyKind.DATA;
                break;
            case "-G":
            case "--get":
                state.getFlag = true;
                break;
            case "-I":
            case "--head":
                state.method = "HEAD";
                break;
            case "-A":
            case "--user-agent":
                state.headers.put("User-Agent", next(tokens, ++idx));
                break;
            case "-e":
            case "--referer":
                state.headers.put("Referer", next(tokens, ++idx));
                break;
            case "-b":
            case "--cookie":
                state.headers.put("Cookie", next(tokens, ++idx));
                break;
            case "-u":
            case "--user":
                arg = next(tokens, ++idx);
                int ac = arg.indexOf(':');
                state.basicAuth = ac > 0 ? arg : null;
                state.bearerToken = ac <= 0 ? arg : null;
                break;
            case "--connect-timeout":
                state.connectTimeout = Long.parseLong(next(tokens, ++idx));
                break;
            case "--max-time":
                state.readTimeout = Long.parseLong(next(tokens, ++idx)) * 1000;
                break;
            case "--proxy":
                arg = next(tokens, ++idx);
                int pp = arg.lastIndexOf(':');
                if (pp > 0) {
                    state.proxyHost = arg.substring(0, pp);
                    state.proxyPort = Integer.parseInt(arg.substring(pp + 1));
                } else {
                    state.proxyHost = arg;
                    state.proxyPort = 8080;
                }
                break;
            case "--proxy-user":
                arg = next(tokens, ++idx);
                int pc = arg.indexOf(':');
                if (pc > 0) state.proxyAuth = arg;
                break;
            case "-F":
            case "--form":
                arg = next(tokens, ++idx);
                if (state.formFields == null) state.formFields = new ArrayList<>();
                state.formFields.add(arg);
                break;
            case "-T":
            case "--upload-file":
                state.uploadFile = next(tokens, ++idx);
                break;
            case "-k":
            case "--insecure":
                state.insecure = true;
                break;
            case "-L":
            case "--location":
                state.followRedirects = true;
                break;
            case "--compressed":
                state.headers.put("Accept-Encoding", "gzip, deflate");
                break;
            default:
                break;
        }
        return idx;
    }

    // ==================== 状态应用到 Builder ====================

    private static void applyState(HttpClientBuilder builder, BuilderState state) {
        // URL / path
        if (state.path != null && !state.path.isEmpty()) {
            builder.path(state.path);
        }

        // Headers
        for (Map.Entry<String, String> entry : state.headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        // Auth
        if (state.basicAuth != null) {
            int c = state.basicAuth.indexOf(':');
            builder.authBasic(state.basicAuth.substring(0, c), state.basicAuth.substring(c + 1));
        } else if (state.bearerToken != null) {
            builder.auth(state.bearerToken);
        }

        // Proxy auth header
        if (state.proxyAuth != null) {
            int c = state.proxyAuth.indexOf(':');
            if (c > 0) {
                String creds = state.proxyAuth.substring(0, c) + ":" + state.proxyAuth.substring(c + 1);
                builder.header("Proxy-Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8)));
            }
        }

        // Method
        if (state.method != null) {
            setMethod(builder, state.method);
        } else if (state.getFlag) {
            builder.get();
        }

        // Body
        if (state.body != null) {
            if (state.bodyKind == BodyKind.DATA_URL_ENCODE && state.formData != null) {
                for (Map.Entry<String, String> e : state.formData.entrySet()) {
                    builder.formData(e.getKey(), e.getValue());
                }
            } else {
                builder.body(state.body);
            }
        }

        // Form fields (-F / --form)
        if (state.formFields != null) {
            for (String field : state.formFields) {
                if (field.startsWith("@")) {
                    String namePart = field.substring(1);
                    int eq = namePart.indexOf('=');
                    if (eq > 0) {
                        String fname = namePart.substring(0, eq);
                        String fpath = namePart.substring(eq + 1);
                        try {
                            byte[] content = java.nio.file.Files.readAllBytes(
                                    java.nio.file.Paths.get(fpath));
                            String fileName = java.nio.file.Paths.get(fpath).getFileName().toString();
                            builder.formData(fname, content, null, fileName);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to read upload file: " + fpath, e);
                        }
                    }
                } else if (field.contains("=")) {
                    int eq = field.indexOf('=');
                    builder.formData(field.substring(0, eq), field.substring(eq + 1));
                } else {
                    builder.body(field);
                }
            }
        }

        // Upload file (-T)
        if (state.uploadFile != null) {
            try {
                byte[] content = java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(state.uploadFile));
                builder.body(content);
                setMethod(builder, "PUT");
            } catch (Exception e) {
                throw new RuntimeException("Failed to read upload file: " + state.uploadFile, e);
            }
        }

        // Timeouts
        if (state.connectTimeout != null) builder.connectTimeout(state.connectTimeout);
        if (state.readTimeout != null) builder.readTimeout(state.readTimeout);

        // Proxy
        if (state.proxyHost != null) builder.proxy(state.proxyHost, state.proxyPort);

        // Redirects
        if (!state.followRedirects) {
            builder.onRedirect(null); // onRedirect(null) sets followRedirects=true, override via request
        }
    }

    private static void setMethod(HttpClientBuilder builder, String method) {
        switch (method.toUpperCase()) {
            case "GET" -> builder.get();
            case "POST" -> builder.post();
            case "PUT" -> builder.put();
            case "DELETE" -> builder.delete();
            case "PATCH" -> builder.patch();
            case "HEAD" -> builder.head();
            case "OPTIONS" -> builder.options();
            default -> builder.method(com.chua.common.support.network.http.HttpMethod.valueOf(method.toUpperCase()));
        }
    }

    // ==================== 内部类 ====================

    private static class BuilderState {
        String baseUrl;
        String path;
        String method;
        boolean getFlag;
        boolean followRedirects = true;
        boolean insecure;
        Map<String, String> headers = new LinkedHashMap<>();
        String body;
        BodyKind bodyKind = BodyKind.NONE;
        Map<String, String> formData;
        List<String> formFields;
        String basicAuth;
        String bearerToken;
        String proxyAuth;
        String proxyHost;
        int proxyPort;
        Long connectTimeout;
        Long readTimeout;
        String uploadFile;
    }

    private enum BodyKind { NONE, DATA, DATA_URL_ENCODE }

    private static String up(String s) {
        return s == null ? null : s.toUpperCase();
    }

    private static String next(List<String> tokens, int idx) {
        if (idx + 1 < tokens.size()) return tokens.get(idx + 1);
        throw new IllegalArgumentException("Missing argument at token index " + idx);
    }
}
