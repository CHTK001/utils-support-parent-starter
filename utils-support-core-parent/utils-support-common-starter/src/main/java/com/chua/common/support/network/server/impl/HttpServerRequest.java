package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.ServerRequest;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.MultipartParser;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 基于 JDK {@link HttpExchange} 的 {@link ServerRequest} 实现。
 *
 * @author CH
 * @since 2026/07/16
 */
public class HttpServerRequest implements ServerRequest {

    /** Exchange */
    private final HttpExchange exchange;
    /** 最大值请求尺寸 */
    private final long maxRequestSize;
    /** 默认字符集 */
    private final Charset defaultCharset;
    /** Cached请求体 */
    private byte[] cachedBody;
    /** attributes */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
    * 创建 HttpServerRequest 实例
    * @param exchange exchange
    * @param long long
    * @param String String
    * @param maxRequestSize 最大值请求大小，不允许为 null
    * @param charset 字符集，不允许为 null
    */
    public HttpServerRequest(HttpExchange exchange, long maxRequestSize, String charset) {
        this.exchange = exchange;
        this.maxRequestSize = maxRequestSize;
        this.defaultCharset = Charset.forName(charset);
    }

    @Override
    /** 获取Uri */
    public String getUri() {
        return exchange.getRequestURI().toString();
    }

    @Override
    /** 获取Path */
    public String getPath() {
        return exchange.getRequestURI().getPath();
    }

    @Override
    /** 获取Method */
    public HttpMethod getMethod() {
        try {
            return HttpMethod.valueOf(exchange.getRequestMethod().toUpperCase());
        } catch (IllegalArgumentException e) {
            return HttpMethod.OPTIONS;
        }
    }

    @Override
    /** 获取Header */
    public String getHeader(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    @Override
    /** 获取Headers */
    public HttpHeader getHeaders() {
        HttpHeader h = HttpHeader.create();
        exchange.getRequestHeaders().forEach((k, v) -> h.add(k, String.join(",", v)));
        return h;
    }

    @Override
    /** 获取Params */
    public Map<String, String> getParams() {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length > 0) {
                map.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
            }
        }
        return map;
    }

    @Override
    /** 获取Param */
    public String getParam(String name) {
        return getParams().get(name);
    }

    @Override
    /** 获取ContentType */
    public String getContentType() {
        return exchange.getRequestHeaders().getFirst("Content-Type");
    }

    @Override
    /** 获取Content获取长度 */
    public long getContentLength() {
        String len = exchange.getRequestHeaders().getFirst("Content-Length");
        if (len == null) {
            return -1;
        }
        try {
            return Long.parseLong(len);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    /** 获取Body */
    public byte[] getBody() {
        if (cachedBody == null) {
            try (InputStream is = exchange.getRequestBody()) {
                cachedBody = is.readAllBytes();
                if (cachedBody.length > maxRequestSize) {
                    throw new IllegalArgumentException("请求体超过最大限制: " + maxRequestSize);
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取请求体失败", e);
            }
        }
        return cachedBody;
    }

    @Override
    /** 获取BodyString */
    public String getBodyString() {
        return new String(getBody(), resolveCharset());
    }

    @Override
    /** 获取InputStream */
    public InputStream getInputStream() {
        return new java.io.ByteArrayInputStream(getBody());
    }

    @Override
    /** 获取RemoteAddress */
    public String getRemoteAddress() {
        return exchange.getRemoteAddress().getHostString();
    }

    @Override
    /** 获取RemotePort */
    public int getRemotePort() {
        return exchange.getRemoteAddress().getPort();
    }

    @Override
    /** 获取Attributes */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    /** 获取Attribute */
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    /** 设置Attribute */
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    @Override
    /** 获取FormData */
    public Map<String, String> getFormData() {
        String ct = getContentType();
        if (ct == null) {
            return Collections.emptyMap();
        }
        String lower = ct.toLowerCase();
        if (lower.startsWith("application/x-www-form-urlencoded")) {
            String body = getBodyString();
            if (body == null || body.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length > 0) {
                    form.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
                }
            }
            return form;
        }
        if (lower.startsWith("multipart/form-data")) {
            MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");
            if (parser != null) {
                return parser.parseFormFields(getBody(), ct);
            }
        }
        return Collections.emptyMap();
    }

    @Override
    /** 获取Files */
    public List<FormFile> getFiles() {
        String ct = getContentType();
        if (ct == null || !ct.toLowerCase().startsWith("multipart/form-data")) {
            return Collections.emptyList();
        }
        MultipartParser parser = ServiceProvider.of(MultipartParser.class).getExtension("fileupload");
        if (parser == null) {
            return Collections.emptyList();
        }
        return parser.parse(getBody(), ct);
    }

    /**
    * 解码 URL 查询参数片段。
    *
    * @param value 原始参数片段
    * @return 解码后的参数
    */
    private String decode(String value) {
        return URLDecoder.decode(value, defaultCharset);
    }

    /**
     * 从 Content-Type 解析字符集，未声明时回退到服务器配置。
     *
     * @return 请求体字符集
     */
    private Charset resolveCharset() {
        String contentType = getContentType();
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String value = part.trim();
                if (value.regionMatches(true, 0, "charset=", 0, 8)) {
                    try {
                        return Charset.forName(value.substring(8).trim());
                    } catch (Exception ignored) {
                        return defaultCharset;
                    }
                }
            }
        }
        return defaultCharset;
    }
}
