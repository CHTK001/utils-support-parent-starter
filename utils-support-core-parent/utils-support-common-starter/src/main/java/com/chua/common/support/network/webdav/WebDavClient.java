// [P3C A20 豁免] A20/A30/A31: 含日志字符串等非业务魔法值场景，豁免。
package com.chua.common.support.network.webdav;

import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.chua.common.support.utils.StringUtils;

/**
 * WebDAV 客户端（基于 {@link HttpClientFactory} SPI HTTP 客户端）。
 *
 * <p>与 {@link com.chua.common.support.network.ftp.FtpClient} 同风格：
 * Builder 构建客户端，随后以链式 DSL 执行 WebDAV 操作。除高层操作
 * （upload/download/mkdir/delete/copy/move/list/exists/lock 等）外，
 * 提供 {@link #request(String, String)} 任意方法低层入口。
 * HTTP 传输经 SPI 装配（okhttp &gt; httpclient5 &gt; httpclient &gt; jdk 自动选择），
 * 网络异常统一以 {@link IOException} 抛出。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * WebDavClient client = WebDavClient.builder()
 *         .baseUrl("http://127.0.0.1:8080")
 *         .credentials("alice", "secret")
 *         .build();
 * client.upload("/dir/hello.txt", "hello".getBytes(StandardCharsets.UTF_8));
 * byte[] data = client.download("/dir/hello.txt");
 * client.close();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
public class WebDavClient implements AutoCloseable {

    /**
     * 基础地址（如 http://127.0.0.1:8080）
     */
    private final String baseUrl;

    /**
     * 认证头（Basic，可为 null）
     */
    private final String authorization;

    /**
     * 连接超时（毫秒）
     */
    private final long connectTimeoutMillis;

    /**
     * 创建客户端。
     *
     * @param builder 构建器
     */
    private WebDavClient(Builder builder) {
        String base = builder.baseUrl == null ? "" : builder.baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        if (builder.username != null) {
            String token = builder.username + ":" + (builder.password == null ? "" : builder.password);
            this.authorization = "Basic " + Base64.getEncoder()
                    .encodeToString(token.getBytes(StandardCharsets.UTF_8));
        } else {
            this.authorization = null;
        }
        this.connectTimeoutMillis = builder.connectTimeout * 1000L;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 低层入口：构造任意方法的 WebDAV 请求。
     *
     * @param method HTTP/WebDAV 方法（如 PROPFIND）
     * @param path   资源路径
     * @return 请求构建器
     */
    public WebDavRequest request(String method, String path) {
        return new WebDavRequest(method, path);
    }

    /**
     * 将方法名字符串解析为 {@link HttpMethod}。
     *
     * @param method 方法名（如 PROPFIND）
     * @return HttpMethod 枚举
     */
    private static HttpMethod toHttpMethod(String method) {
        try {
            return HttpMethod.valueOf(method);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的 HTTP/WebDAV 方法: " + method, e);
        }
    }

    /**
     * 上传字节到指定路径（PUT）。
     *
     * @param path 目标路径
     * @param data 字节数据
     * @return 响应结果
     * @throws IOException 网络异常
     */
    public WebDavResult upload(String path, byte[] data) throws IOException {
        return request("PUT", path).body(data).exec();
    }

    /**
     * 上传文件（PUT）。
     *
     * @param path 目标路径
     * @param file 本地文件
     * @return 响应结果
     * @throws IOException 网络异常
     */
    public WebDavResult upload(String path, File file) throws IOException {
        return upload(path, Files.readAllBytes(file.toPath()));
    }

    /**
     * 下载资源内容（GET）。
     *
     * @param path 资源路径
     * @return 字节数据
     * @throws IOException 网络异常或状态非 2xx
     */
    public byte[] download(String path) throws IOException {
        WebDavResult result = request("GET", path).exec();
        if (!result.isSuccessful()) {
            throw new IOException("WebDAV GET failed: " + result.getStatus() + " " + path);
        }
        return result.getBody();
    }

    /**
     * 下载资源到本地文件（GET）。
     *
     * @param path     资源路径
     * @param target   本地目标文件
     * @return 响应结果
     * @throws IOException 网络异常或状态非 2xx
     */
    public WebDavResult downloadToFile(String path, File target) throws IOException {
        byte[] data = download(path);
        Files.write(target.toPath(), data);
        return new WebDavResult(200, null, data);
    }

    /**
     * 创建集合/目录（MKCOL）。
     *
     * @param path 集合路径
     * @return 响应结果
     * @throws IOException 网络异常
     */
    public WebDavResult mkdir(String path) throws IOException {
        return request("MKCOL", path).exec();
    }

    /**
     * 删除资源（DELETE，服务端递归）。
     *
     * @param path 资源路径
     * @return 响应结果
     * @throws IOException 网络异常
     */
    public WebDavResult delete(String path) throws IOException {
        return request("DELETE", path).exec();
    }

    /**
     * 复制资源（COPY）。
     *
     * @param source      源路径
     * @param destination 目标路径
     * @return 响应结果
     * @throws IOException 网络异常
     */
    public WebDavResult copy(String source, String destination) throws IOException {
        return request("COPY", source).destination(destination).exec();
    }

    /**
     * 移动/重命名资源（MOVE）。
     *
     * @param source      源路径
     * @param destination 目标路径
     * @return 响应结果
     * @throws IOException 网络异常
     */
    public WebDavResult move(String source, String destination) throws IOException {
        return request("MOVE", source).destination(destination).exec();
    }

    /**
     * 资源是否存在（PROPFIND 状态判定）。
     *
     * @param path 资源路径
     * @return 是否存在
     */
    public boolean exists(String path) {
        try {
            return request("PROPFIND", path).depth("0").exec().getStatus() < 400;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 资源大小（PROPFIND）。
     *
     * @param path 资源路径
     * @return 字节数；不存在返回 -1
     */
    public long size(String path) {
        WebDavResource resource = propfind(path);
        return resource == null || resource.isDirectory() ? -1 : resource.getContentLength();
    }

    /**
     * 查询单个资源属性（PROPFIND Depth 0）。
     *
     * @param path 资源路径
     * @return 资源信息；不存在返回 {@code null}
     */
    public WebDavResource propfind(String path) {
        List<WebDavResource> resources = list(path, "0");
        return resources.isEmpty() ? null : resources.get(0);
    }

    /**
     * 列出集合内容（PROPFIND Depth 1，不含自身）。
     *
     * @param path 集合路径
     * @return 子资源列表
     */
    public List<WebDavResource> list(String path) {
        List<WebDavResource> all = list(path, "1");
        return all.size() <= 1 ? new ArrayList<>() : all.subList(1, all.size());
    }

    /**
     * PROPFIND 查询并解析 multistatus。
     *
     * @param path  资源路径
     * @param depth Depth 头
     * @return 资源列表（Depth 0 时仅自身）
     */
    private List<WebDavResource> list(String path, String depth) {
        try {
            WebDavResult result = request("PROPFIND", path).depth(depth).exec();
            if (result.getStatus() >= 400) {
                return new ArrayList<>();
            }
            return parseMultistatus(result.getBodyString());
        } catch (IOException e) {
            log.warn("WebDAV PROPFIND 失败: {} - {}", path, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 加锁（LOCK，咨询式）。
     *
     * @param path 资源路径
     * @return 锁令牌；失败返回 {@code null}
     */
    public String lock(String path) {
        try {
            WebDavResult result = request("LOCK", path)
                    .body("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                            + "<D:lockinfo xmlns:D=\"DAV:\"><D:locktype><D:write/>"
                            + "</D:locktype><D:lockscope><D:exclusive/></D:lockscope>"
                            + "<D:depth>0</D:depth></D:lockinfo>")
                    .contentType("application/xml;charset=UTF-8")
                    .exec();
            if (result.getStatus() >= 400) {
                return null;
            }
            Matcher matcher = Pattern.compile("<D:href>([^<]+)</D:href>")
                    .matcher(result.getBodyString());
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            log.warn("WebDAV LOCK 失败: {} - {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 解锁（UNLOCK）。
     *
     * @param path  资源路径
     * @param token 锁令牌
     * @return 响应结果
     * @throws IOException 网络异常
     */
    public WebDavResult unlock(String path, String token) throws IOException {
        return request("UNLOCK", path).lockToken(token).exec();
    }

    /**
     * 解析 multistatus XML（正则级解析，够用于属性列表）。
     *
     * @param xml 响应体
     * @return 资源列表
     */
    private List<WebDavResource> parseMultistatus(String xml) {
        List<WebDavResource> resources = new ArrayList<>();
        if (StringUtils.isEmpty(xml)) {
            return resources;
        }
        Matcher responses = Pattern.compile("<[a-zA-Z0-9]*:?response[\\s\\S]*?</[a-zA-Z0-9]*:?response>")
                .matcher(xml);
        while (responses.find()) {
            String block = responses.group();
            String href = firstTag(block, "href");
            if (href == null) {
                continue;
            }
            String decoded = URLDecoder.decode(href, StandardCharsets.UTF_8);
            String name = decoded.endsWith("/")
                    ? stripTrailing(decoded.substring(0, decoded.length() - 1))
                    : decoded;
            int slash = name.lastIndexOf('/');
            name = slash >= 0 ? name.substring(slash + 1) : name;
            if (name.isEmpty()) {
                name = "/";
            }
            boolean directory = block.matches("(?s).*<[a-zA-Z0-9]*:?resourcetype>\\s*"
                    + "<[a-zA-Z0-9]*:?collection\\s*/>.*") || decoded.endsWith("/");
            String lengthText = firstTag(block, "getcontentlength");
            String modifiedText = firstTag(block, "getlastmodified");
            String contentType = firstTag(block, "getcontenttype");
            String etag = firstTag(block, "getetag");
            resources.add(new WebDavResource(name, href, directory,
                    lengthText == null ? -1 : parseLong(lengthText),
                    modifiedText, contentType, etag));
        }
        return resources;
    }

    /**
     * 去掉结尾分隔符。
     */
    private String stripTrailing(String value) {
        String v = value;
        while (v.endsWith("/") && v.length() > 1) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /**
     * 解析长整数。
     */
    private long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 取块内第一个指定标签文本。
     */
    private String firstTag(String block, String tag) {
        Matcher matcher = Pattern.compile("(?s)<[a-zA-Z0-9]*:?" + tag + "[^>]*>(.*?)</[a-zA-Z0-9]*:?" + tag + ">")
                .matcher(block);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    @Override
    public void close() {
        // SPI HTTP 客户端无需显式关闭；保留 AutoCloseable 语义以便与 FtpClient 用法对齐
    }

    /**
     * WebDAV 资源属性。
     */
    @Getter
    @ToString
    public static class WebDavResource {

        /**
         * 资源名（不含路径）
         */
        private final String name;

        /**
         * 服务端返回的 href
         */
        private final String href;

        /**
         * 是否为集合（目录）
         */
        private final boolean directory;

        /**
         * 内容长度（目录为 -1）
         */
        private final long contentLength;

        /**
         * 最后修改时间（HTTP 日期原文）
         */
        private final String lastModified;

        /**
         * 内容类型
         */
        private final String contentType;

        /**
         * ETag
         */
        private final String etag;

        private WebDavResource(String name, String href, boolean directory, long contentLength,
                                String lastModified, String contentType, String etag) {
            this.name = name;
            this.href = href;
            this.directory = directory;
            this.contentLength = contentLength;
            this.lastModified = lastModified;
            this.contentType = contentType;
            this.etag = etag;
        }
    }

    /**
     * WebDAV 响应结果。
     */
    @Getter
    @ToString
    public static class WebDavResult {

        /**
         * HTTP 状态码
         */
        private final int status;

        /**
         * 响应头
         */
        private final Map<String, String> headers;

        /**
         * 响应体
         */
        private final byte[] body;

        private WebDavResult(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
            this.body = body == null ? new byte[0] : body;
        }

        /**
         * 是否成功（2xx）。
         *
         * @return 是否成功
         */
        public boolean isSuccessful() {
            return status >= 200 && status < 300;
        }

        /**
         * 获取响应体文本。
         *
         * @return UTF-8 文本
         */
        public String getBodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /**
         * 获取指定响应头。
         *
         * @param name 头名
         * @return 头值；不存在返回 {@code null}
         */
        public String getHeader(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    /**
     * 请求级链式构建器。
     */
    public class WebDavRequest {

        /**
         * 方法
         */
        private final String method;

        /**
         * 资源路径
         */
        private final String path;

        /**
         * 附加头
         */
        private final Map<String, String> headers = new LinkedHashMap<>();

        /**
         * 请求体
         */
        private byte[] body;

        private WebDavRequest(String method, String path) {
            this.method = method;
            this.path = path == null ? "/" : path;
        }

        /**
         * 添加请求头。
         *
         * @param name  头名
         * @param value 头值
         * @return 当前请求
         */
        public WebDavRequest header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /**
         * 设置 Destination 头（COPY/MOVE）。
         *
         * @param destination 目标路径或绝对 URI
         * @return 当前请求
         */
        public WebDavRequest destination(String destination) {
            String target = destination;
            if (target != null && !target.startsWith("http://") && !target.startsWith("https://")) {
                target = baseUrl + (target.startsWith("/") ? target : "/" + target);
            }
            headers.put("Destination", target);
            headers.put("Overwrite", "T");
            return this;
        }

        /**
         * 设置 Depth 头（PROPFIND）。
         *
         * @param depth 0/1/infinity
         * @return 当前请求
         */
        public WebDavRequest depth(String depth) {
            headers.put("Depth", depth);
            return this;
        }

        /**
         * 设置 Lock-Token 头（UNLOCK）。
         *
         * @param token 锁令牌
         * @return 当前请求
         */
        public WebDavRequest lockToken(String token) {
            if (token != null && !token.isBlank()) {
                headers.put("Lock-Token", token.startsWith("<") ? token : "<" + token + ">");
            }
            return this;
        }

        /**
         * 设置 Content-Type 头。
         *
         * @param contentType 内容类型
         * @return 当前请求
         */
        public WebDavRequest contentType(String contentType) {
            headers.put("Content-Type", contentType);
            return this;
        }

        /**
         * 设置请求体。
         *
         * @param data 字节数据
         * @return 当前请求
         */
        public WebDavRequest body(byte[] data) {
            this.body = data;
            return this;
        }

        /**
         * 设置文本请求体。
         *
         * @param text 文本
         * @return 当前请求
         */
        public WebDavRequest body(String text) {
            return body(text == null ? new byte[0]
                    : text.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * 执行请求。
         *
         * <p>底层经 {@link HttpClientFactory} SPI HTTP 客户端发送；SPI 执行失败
         * （连接拒绝、超时等以 {@link RuntimeException} 抛出）统一包装为
         * {@link IOException}，保持与 JDK HttpClient 实现一致的受检异常语义。</p>
         *
         * @return 响应结果
         * @throws IOException 网络异常
         */
        public WebDavResult exec() throws IOException {
            String target = path.startsWith("/") ? path : "/" + path;
            HttpClientBuilder requestBuilder = HttpClientFactory.of(baseUrl)
                    .path(target)
                    .connectTimeout(connectTimeoutMillis)
                    .method(toHttpMethod(method));
            if (authorization != null) {
                requestBuilder.authorization(authorization);
            }
            requestBuilder.headers(headers);
            if (body != null) {
                requestBuilder.body(body);
            }
            if (log.isDebugEnabled()) {
                log.debug("WebDAV 请求: {} {}", method, baseUrl + target);
            }
            ClientResponse response;
            try {
                response = requestBuilder.execute();
            } catch (RuntimeException e) {
                throw new IOException("WebDAV 请求失败: " + method + " " + baseUrl + target
                        + " - " + e.getMessage(), e);
            }
            Map<String, String> headerMap = new LinkedHashMap<>();
            response.getHeaders().toMap()
                    .forEach((name, value) -> headerMap.put(name.toLowerCase(), value));
            return new WebDavResult(response.getStatusCode(), headerMap, response.getBody());
        }
    }

    /**
     * WebDAV 客户端构建器。
     */
    public static class Builder {

        /**
         * 基础地址
         */
        private String baseUrl;

        /**
         * 用户名
         */
        private String username;

        /**
         * 密码
         */
        private String password;

        /**
         * 连接超时（秒）
         */
        private int connectTimeout = 10;

        /**
         * 是否跟随重定向。
         *
         * <p>注意：当前 SPI {@link HttpClientBuilder} 未提供关闭自动重定向的公开入口
         * （默认跟随），本配置暂不生效，仅保留 API 兼容。</p>
         */
        private boolean followRedirects = true;

        /**
         * 设置基础地址。
         *
         * @param baseUrl 如 http://127.0.0.1:8080
         * @return 构建器
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置 Basic 认证凭据。
         *
         * @param username 用户名
         * @param password 密码
         * @return 构建器
         */
        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        /**
         * 设置连接超时。
         *
         * @param connectTimeout 秒
         * @return 构建器
         */
        public Builder connectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * 设置是否跟随重定向。
         *
         * @param followRedirects 布尔值
         * @return 构建器
         */
        public Builder followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        /**
         * 构建客户端。
         *
         * @return WebDAV 客户端
         */
        public WebDavClient build() {
            return new WebDavClient(this);
        }
    }
}
