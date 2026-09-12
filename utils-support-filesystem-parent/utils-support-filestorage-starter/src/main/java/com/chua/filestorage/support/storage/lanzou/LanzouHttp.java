package com.chua.filestorage.support.storage.lanzou;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 蓝奏云专用 HTTP 客户端。
 *
 * <p>未复用项目统一的 {@code HttpClientFactory}，原因是蓝奏云协议对以下细节高度敏感，
 * 需要逐项精确控制：</p>
 * <ul>
 *   <li>响应强制 gzip/deflate 压缩，需自行解码；</li>
 *   <li>需要维护会话级 Cookie（登录态 Cookie + WAF 下发的 acw_sc__v2）；</li>
 *   <li>下载直链需要禁止自动重定向以取出 Location，或以流式方式透传大文件；</li>
 *   <li>WAF 挑战页需要求解后原样重放请求。</li>
 * </ul>
 *
 * <p>本类线程安全：Cookie 存放于 {@link ConcurrentHashMap}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class LanzouHttp {

    /**
      * 浏览器 用户-智能体，蓝奏云会对非浏览器 UA 返回异常页面。
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0";

    /**
     * WAF 挑战最大重试次数。
     */
    private static final int MAX_CHALLENGE_RETRY = 2;

    /**
     * 连接超时（毫秒）。
     */
    private final int connectTimeout;

    /**
     * 读取超时（毫秒）。
     */
    private final int readTimeout;

    /**
      * 会话 Cookie 容器，键 为 Cookie 名。
     */
    private final Map<String, String> cookies = new ConcurrentHashMap<>();

    /**
     * 构造 HTTP 客户端。
     *
     * @param rawCookie      初始 Cookie 串，形如 {@code ylogin=123; phpdisk_info=xxx}，可为 空
     * @param connectTimeout 连接超时（毫秒）
     * @param readTimeout    读取超时（毫秒）
     */
    LanzouHttp(String rawCookie, int connectTimeout, int readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        // 蓝奏云分享页要求携带这两个标记，否则会跳转到广告页
        this.cookies.put("codelen", "1");
        this.cookies.put("pc_ad1", "1");
        mergeCookie(rawCookie);
    }

    /**
     * 合并 Cookie 串到会话中。
     *
     * @param rawCookie Cookie 串，形如 {@code a=1; b=2}
     */
    final void mergeCookie(String rawCookie) {
        if (rawCookie == null || rawCookie.isEmpty()) {
            return;
        }
        for (String item : rawCookie.split(";")) {
            String pair = item.trim();
            int index = pair.indexOf('=');
            if (index > 0) {
                cookies.put(pair.substring(0, index).trim(), pair.substring(index + 1).trim());
            }
        }
    }

    /**
     * 读取指定 Cookie 值。
     *
     * @param name Cookie 名
     * @return Cookie 值，不存在返回 空
     */
    String getCookie(String name) {
        return cookies.get(name);
    }

    /**
     * 拼接当前会话的 Cookie 请求头。
     *
     * @return Cookie 头值
     */
    private String cookieHeader() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    /**
      * 发送 获取 请求并返回文本响应，自动处理 WAF 挑战。
     *
     * @param url     请求地址
     * @param referer Referer 头，可为 空
     * @return 响应正文
     */
    String get(String url, String referer) {
        for (int attempt = 0; attempt <= MAX_CHALLENGE_RETRY; attempt++) {
            String body = doText(url, "GET", null, referer);
            if (!LanzouAntiCrawler.isChallenge(body)) {
                return body;
            }
            String token = LanzouAntiCrawler.resolve(body);
            if (token == null) {
                throw new LanzouException("蓝奏云反爬挑战解析失败: " + url);
            }
            cookies.put("acw_sc__v2", token);
        }
        throw new LanzouException("蓝奏云反爬挑战重试超限: " + url);
    }

    /**
     * 发送表单 POST 请求并返回文本响应，自动处理 WAF 挑战。
     *
     * @param url     请求地址
     * @param params  表单参数，将以 {@code application/x-www-form-urlencoded} 编码
     * @param referer Referer 头，可为 空
     * @return 响应正文
     */
    String post(String url, Map<String, String> params, String referer) {
        byte[] payload = encodeForm(params).getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt <= MAX_CHALLENGE_RETRY; attempt++) {
            String body = doText(url, "POST", payload, referer);
            if (!LanzouAntiCrawler.isChallenge(body)) {
                return body;
            }
            String token = LanzouAntiCrawler.resolve(body);
            if (token == null) {
                throw new LanzouException("蓝奏云反爬挑战解析失败: " + url);
            }
            cookies.put("acw_sc__v2", token);
        }
        throw new LanzouException("蓝奏云反爬挑战重试超限: " + url);
    }

    /**
      * 以 multipart/form-数据 上传文件。
     *
     * @param url      上传地址
     * @param referer  Referer 头
     * @param fields   普通表单字段
     * @param fileKey  文件字段名
     * @param fileName 文件名
     * @param content  文件内容
     * @return 响应正文
     */
    String upload(String url, String referer, Map<String, String> fields,
                  String fileKey, String fileName, byte[] content) {
        String boundary = "----WebKitFormBoundary" + Long.toHexString(System.nanoTime());
        byte[] payload = buildMultipart(boundary, fields, fileKey, fileName, content);
        HttpURLConnection connection = null;
        try {
            connection = open(url, "POST", referer);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            return readText(connection);
        } catch (IOException e) {
            throw new LanzouException("蓝奏云上传请求失败: " + url, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 打开下载直链的输入流，跟随重定向。
     *
     * <p>调用方负责关闭返回的流。</p>
     *
     * @param url     直链地址
     * @param referer Referer 头
     * @return 文件内容输入流
     */
    InputStream openStream(String url, String referer) {
        String current = url;
        try {
            for (int hop = 0; hop < 5; hop++) {
                HttpURLConnection connection = open(current, "GET", referer);
                // 手动跟随重定向，保证跨域跳转时仍携带 UA 与 Cookie
                connection.setInstanceFollowRedirects(false);
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || location.isEmpty()) {
                        throw new LanzouException("蓝奏云下载重定向缺少 Location: " + current);
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    connection.disconnect();
                    throw new LanzouException("蓝奏云下载失败, HTTP " + code + ": " + current);
                }
                return decode(connection.getInputStream(), connection.getContentEncoding());
            }
        } catch (IOException e) {
            throw new LanzouException("蓝奏云下载请求失败: " + url, e);
        }
        throw new LanzouException("蓝奏云下载重定向次数过多: " + url);
    }

    /**
     * 执行请求并读取文本响应。
     *
     * @param url     请求地址
     * @param method  请求方法
     * @param payload 请求体，获取 时为 空
     * @param referer Referer 头
     * @return 响应正文
     */
    private String doText(String url, String method, byte[] payload, String referer) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, method, referer);
            if (payload != null) {
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }
            return readText(connection);
        } catch (IOException e) {
            throw new LanzouException("蓝奏云请求失败: " + url, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 创建并初始化连接，写入公共请求头。
     *
     * @param url     请求地址
     * @param method  请求方法
     * @param referer Referer 头
     * @return 已配置的连接
     * @throws IOException 网络异常
     */
    private HttpURLConnection open(String url, String method, String referer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("Connection", "keep-alive");
        connection.setRequestProperty("Cookie", cookieHeader());
        if (referer != null && !referer.isEmpty()) {
            connection.setRequestProperty("Referer", referer);
        }
        if ("POST".equals(method)) {
            connection.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        }
        return connection;
    }

    /**
     * 读取响应正文，处理压缩编码并回收下发的 Cookie。
     *
     * @param connection 连接
     * @return 响应正文
     * @throws IOException 网络异常
     */
    private String readText(HttpURLConnection connection) throws IOException {
        collectCookies(connection);
        int code = connection.getResponseCode();
        InputStream raw = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (raw == null) {
            return "";
        }
        try (InputStream input = decode(raw, connection.getContentEncoding())) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int size;
            while ((size = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, size);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
      * 根据 内容-编码 包装解压流。
     *
     * @param input    原始流
     * @param encoding 内容编码
     * @return 解压后的流
     * @throws IOException 解压异常
     */
    private InputStream decode(InputStream input, String encoding) throws IOException {
        if (encoding == null) {
            return input;
        }
        if (encoding.toLowerCase().contains("gzip")) {
            return new GZIPInputStream(input);
        }
        if (encoding.toLowerCase().contains("deflate")) {
            return new InflaterInputStream(input);
        }
        return input;
    }

    /**
      * 收集响应中的 设置-Cookie 并合并到会话。
     *
     * @param connection 连接
     */
    private void collectCookies(HttpURLConnection connection) {
        for (int index = 0; ; index++) {
            String key = connection.getHeaderFieldKey(index);
            String value = connection.getHeaderField(index);
            if (key == null && value == null) {
                break;
            }
            if (key != null && "set-cookie".equalsIgnoreCase(key) && value != null) {
                int end = value.indexOf(';');
                mergeCookie(end > 0 ? value.substring(0, end) : value);
            }
        }
    }

    /**
     * 将参数编码为 URL 表单串。
     *
     * @param params 参数
     * @return 编码结果
     */
    private String encodeForm(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    /**
      * 构造 multipart/form-数据 请求体。
     *
     * @param boundary 分隔串
     * @param fields   普通字段
     * @param fileKey  文件字段名
     * @param fileName 文件名
     * @param content  文件内容
     * @return 请求体字节
     */
    private byte[] buildMultipart(String boundary, Map<String, String> fields,
                                  String fileKey, String fileName, byte[] content) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            Map<String, String> safeFields = fields == null ? new LinkedHashMap<>() : fields;
            for (Map.Entry<String, String> entry : safeFields.entrySet()) {
                buffer.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                buffer.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                buffer.write((entry.getValue() == null ? "" : entry.getValue())
                        .getBytes(StandardCharsets.UTF_8));
                buffer.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            buffer.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            buffer.write(("Content-Disposition: form-data; name=\"" + fileKey
                    + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            buffer.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            buffer.write(content);
            buffer.write("\r\n".getBytes(StandardCharsets.UTF_8));
            buffer.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LanzouException("构造上传请求体失败", e);
        }
        return buffer.toByteArray();
    }
}
