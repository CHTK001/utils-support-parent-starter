package com.chua.common.support.network.http.invoke;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.io.binary.ByteSource;
import com.chua.common.support.network.http.*;
import com.chua.common.support.math.unit.name.NamingCase;
import com.chua.common.support.core.utils.CollectionUtils;
import com.chua.common.support.core.utils.StringUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.chua.common.support.core.constant.NumberConstant.DEFAULT_INITIAL_CAPACITY;
import static com.chua.common.support.network.http.HttpMethod.*;
import static com.chua.common.support.media.MediaType.OCTET_STREAM;
import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpRequest.Builder;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 基于 {@link java.net.http.HttpClient} 的 HTTP 执行器
 * <p>
 * 优化点：
 * <ul>
 *   <li>复用单例 HttpClient（连接池），避免每次请求重建</li>
 *   <li>connectTimeout / readTimeout 分离设置</li>
 *   <li>完整支持 PATCH / HEAD / OPTION 方法</li>
 *   <li>GET 缓存命中短路</li>
 * </ul>
 *
 * @author CH
 */
@Slf4j
@Spi(value = { "httpclient", "url" })
public class HttpClientlHttpClientInvoker extends AbstractHttpClientInvoker {

    /** 共享 HttpClient（连接池复用，无代理） */
    private static final java.net.http.HttpClient SHARED_CLIENT = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /** 带代理的 HttpClient 缓存（key: host:port） */
    private static final ConcurrentHashMap<String, java.net.http.HttpClient> PROXY_CLIENTS = new ConcurrentHashMap<>();

    public HttpClientlHttpClientInvoker(HttpRequest request, HttpMethod httpMethod) {
        super(request, httpMethod);
    }

    @Override
    protected HttpResponse executeDelete() {
        return executeMethod(DELETE, 1);
    }

    @Override
    protected HttpResponse executePut() {
        return executeMethod(PUT, 1);
    }

    @Override
    protected HttpResponse executePost() {
        return executeMethod(POST, 1);
    }

    @Override
    protected HttpResponse executeGet() {
        return executeMethod(GET, 1);
    }

    @Override
    protected HttpResponse executePatch() {
        return executeMethod(PATCH, 1);
    }

    @Override
    protected HttpResponse executeOption() {
        return executeMethod(OPTION, 1);
    }

    @Override
    protected HttpResponse executeHead() {
        return executeMethod(HEAD, 1);
    }

    /**
     * method请求
     *
     * @param retry 重试次数
     * @return ResponseEntity
     * @see HttpResponse
     */
    public HttpResponse executeMethod(final HttpMethod method, int retry) {
        // 设置缓存
        // 缓存命中尝试（仅GET）
        if (method == GET && request.isUseCache()) {
            String cacheKey = this.url;
            HttpResponse cached = HttpCacheRegistry.getCache(request.getCacheTtlMillis(), request.getCacheMaxSize())
                    .getIfPresent(cacheKey);
            if (cached != null) {
                if (log.isDebugEnabled()) {
                    log.debug("HTTP cache hit: {}", cacheKey);
                }
                return cached;
            }
        }
        try {
            doAnalysisUrl(method);
            if (log.isDebugEnabled()) {
                log.debug("==================================================");
            }
            log.info("发送的URL: {}", url);
            Builder newBuilder = newBuilder();
            // 设置消息头
            doAnalysisHeader(newBuilder);
            // 设置认证
            doAnalysisBasicAuth(newBuilder);
            doAnalysisCache(newBuilder, method);
            // 设置超时（readTimeout 作用于请求级别）
            doAnalysisRequestConfig(newBuilder);
            // 设定请求方法和请求体
            java.net.http.HttpRequest httpRequest = doAnalysisRequestMethod(newBuilder, method);
            // 优先使用外部传入的 client，其次按代理配置选择
            java.net.http.HttpClient client;
            if (request.getClient() instanceof java.net.http.HttpClient c) {
                client = c;
            } else if (request.getProxy() != null && !request.getProxy().isEmpty()) {
                client = PROXY_CLIENTS.computeIfAbsent(request.getProxy(), this::buildProxyClient);
            } else {
                client = SHARED_CLIENT;
            }
            java.net.http.HttpResponse<byte[]> httpResponse =
                    client.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofByteArray());            int code = httpResponse.statusCode();
            java.net.http.HttpHeaders headers = httpResponse.headers();
            HttpHeader header = new HttpHeader();
            for (Map.Entry<String, List<String>> entry : headers.map().entrySet()) {
                header.addHeader(NamingCase.toFirstUpperCase(entry.getKey()),
                        CollectionUtils.findFirst(entry.getValue()));
            }
            if (code == 302) {
                if (retry > request.getRetry()) {
                    return HttpResponse.builder().code(500).build();
                }

                String location = header.getHeader("Location");
                if (StringUtils.isNotBlank(location)) {
                    request.setUrl(location);
                    this.url = location;
                    return executeMethod(method, retry + 1);
                }
            }

            byte[] bytes = contentEncoding(httpResponse.body(), header.getHeader("content-encoding"));
            Object content = bytes;
            log.info("接收到URL: {}, 响应码: {}", url, code);
            return HttpResponse.builder().code(code).content(content).httpHeader(header).build();
        } catch (Throwable e) {
            log.error("", e);
            if (retry < request.getRetry()) {
                return executeMethod(method, retry + 1);
            }
            return HttpResponse.builder().code(500).message(e.getMessage()).build();
        }
    }

    /**
     * 获取链接
     *
     * @return HttpURLConnection
     * @throws IOException IOException
     */
    private Builder newBuilder() throws IOException {
        return java.net.http.HttpRequest.newBuilder();
    }

    /**
     * 根据代理地址构建 HttpClient
     * 支持格式：host:port 或 http://host:port
     *
     * @param proxy 代理地址
     * @return 带代理的 HttpClient
     */
    private java.net.http.HttpClient buildProxyClient(String proxy) {
        try {
            String host;
            int port;
            // 去掉协议前缀
            String addr = proxy.replaceFirst("(?i)^https?://", "");
            int colonIdx = addr.lastIndexOf(':');
            if (colonIdx > 0) {
                host = addr.substring(0, colonIdx);
                port = Integer.parseInt(addr.substring(colonIdx + 1));
            } else {
                host = addr;
                port = 80;
            }
            return java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                    .proxy(ProxySelector.of(new InetSocketAddress(host, port)))
                    .build();
        } catch (Exception e) {
            log.warn("[HttpClient] 代理配置解析失败: {}，使用直连", proxy);
            return SHARED_CLIENT;
        }
    }

    /**
     * 设置方法类型
     *
     * @param method 方法
     * @return
     */
    private java.net.http.HttpRequest doAnalysisRequestMethod(Builder builder, HttpMethod method) throws Exception {
        builder.uri(new URI(this.url));
        if (method == GET) {
            return builder.GET().build();
        }

        if (method == POST) {
            // 设置消息体
            return doAnalysisPostBody(builder);
        }

        if (method == PUT) {
            // 设置消息体
            return doAnalysisPutBody(builder);
        }

        if (method == DELETE) {
            return builder.DELETE().build();
        }

        if (method == PATCH) {
            return doAnalysisPatchBody(builder);
        }

        if (method == HEAD) {
            return builder.method("HEAD", BodyPublishers.noBody()).build();
        }

        if (method == OPTION) {
            return builder.method("OPTIONS", BodyPublishers.noBody()).build();
        }

        // 通用兜底
        return builder.method(method.name(), BodyPublishers.noBody()).build();
    }

    /**
     * 设置 PATCH 消息体
     */
    private java.net.http.HttpRequest doAnalysisPatchBody(Builder builder) throws IOException {
        if (request.isFormData()) {
            String boundary = "---------------------------";
            builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Map<String, Object> textMap = new HashMap<>(DEFAULT_INITIAL_CAPACITY);
                Map<String, com.chua.common.support.io.binary.ByteSource> fileBin = new HashMap<>(DEFAULT_INITIAL_CAPACITY);
                analysisParams(request.getFormData(), textMap, fileBin);
                intoOutStream(boundary, out, textMap, fileBin);
                out.write(("\r\n--" + boundary + "--\r\n").getBytes());
                return builder.method("PATCH", BodyPublishers.ofByteArray(out.toByteArray())).build();
            }
        }
        BodyData bodyData = request.getBodyData();
        if (bodyData instanceof BodyStringData bodyStringData) {
            return builder.method("PATCH", BodyPublishers.ofString(bodyStringData.getData())).build();
        }
        if (bodyData instanceof BodyByteArrayData bodyArray) {
            return builder.method("PATCH", BodyPublishers.ofByteArray(bodyArray.getData())).build();
        }
        if (!bodyData.isEmpty()) {
            return builder.method("PATCH", BodyPublishers.ofByteArray(bodyData.toByteArray())).build();
        }
        return builder.method("PATCH", BodyPublishers.noBody()).build();
    }

    /**
     * 设置消息头
     *
     * @param builder connection
     */
    private void doAnalysisHeader(Builder builder) {
        HttpHeader header = request.getHeaders();

        if (!header.isEmpty()) {
            try {
                header.forEach(builder::setHeader);
            } catch (Exception ignored) {
            }
        }

        if (log.isDebugEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("消息头设置完成, header!!");
                if (log.isDebugEnabled()) {
                    log.debug("=======================================================");
                }
                for (String key : header.keySet()) {
                    if (log.isDebugEnabled()) {
                        log.debug("{}: {}", key, header.getHeader(key));
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("=======================================================");
                }
            }
        }
    }

    /**
     * 设置认证
     *
     * @param httpRequest 连接
     */
    private void doAnalysisBasicAuth(Builder httpRequest) {
        if (request.getBasicAuth().isEmpty()) {
            return;
        }
        String encoding = "";
        for (Map.Entry<String, String> entry : request.getBasicAuth().entrySet()) {
            encoding = Base64.getEncoder()
                    .encodeToString((entry.getKey() + ":" + entry.getValue()).getBytes(StandardCharsets.UTF_8));
        }
        httpRequest.header("Authorization", String.format("Basic %s", encoding));
    }

    /**
     * Post 请求不能使用缓存
     *
     * @param builder 链接
     * @param method  方法
     */
    private void doAnalysisCache(Builder builder, HttpMethod method) {
    }

    /**
     * 设置消息体
     *
     * @param builder 链接
     * @return
     */
    private java.net.http.HttpRequest doAnalysisPostBody(Builder builder) throws IOException {

        if (request.isFormData()) {
            return formDataForPost(builder, request.getFormData());
        }

        BodyData bodyData = request.getBodyData();
        if (bodyData instanceof BodyStringData bodyStringData) {
            return builder.POST(BodyPublishers.ofString(bodyStringData.getData())).build();
        }

        if (bodyData instanceof BodyByteArrayData bodyArray) {
            return builder.POST(BodyPublishers.ofByteArray(bodyArray.getData())).build();
        }

        if (!bodyData.isEmpty()) {
            return builder.POST(BodyPublishers.ofByteArray(bodyData.toByteArray())).build();
        }

        return builder.POST(BodyPublishers.noBody()).build();

    }

    /**
     * 设置消息体
     *
     * @param builder 链接
     * @return
     */
    private java.net.http.HttpRequest doAnalysisPutBody(Builder builder) throws IOException {

        if (request.isFormData()) {
            return formDataForPut(builder, request.getFormData());
        }

        BodyData bodyData = request.getBodyData();
        if (bodyData instanceof BodyStringData bodyStringData) {
            return builder.PUT(BodyPublishers.ofString(bodyStringData.getData())).build();
        }

        if (bodyData instanceof BodyByteArrayData bodyArray) {
            return builder.PUT(BodyPublishers.ofByteArray(bodyArray.getData())).build();
        }

        if (!bodyData.isEmpty()) {
            return builder.PUT(BodyPublishers.ofByteArray(bodyData.toByteArray())).build();
        }

        return builder.PUT(BodyPublishers.noBody()).build();
    }

    /**
     * 文件 & 表单
     *
     * @param builder  链接
     * @param formData 请求条件
     * @return
     */
    private java.net.http.HttpRequest formDataForPut(Builder builder, FormData formData) throws IOException {
        String boundary = "---------------------------";
        builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Map<String, Object> textMap = new HashMap<>(DEFAULT_INITIAL_CAPACITY);
            Map<String, ByteSource> fileBin = new HashMap<>(DEFAULT_INITIAL_CAPACITY);

            analysisParams(formData, textMap, fileBin);

            intoOutStream(boundary, out, textMap, fileBin);

            byte[] endData = ("\r\n--" + boundary + "--\r\n").getBytes();
            out.write(endData);
            out.flush();
            out.close();
            return builder.PUT(BodyPublishers.ofByteArray(out.toByteArray())).build();
        }
    }

    /**
     * 文件 & 表单
     *
     * @param builder  链接
     * @param formData formData
     * @return
     */
    private java.net.http.HttpRequest formDataForPost(Builder builder, FormData formData) throws IOException {
        String boundary = "---------------------------";
        builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Map<String, Object> textMap = new HashMap<>(DEFAULT_INITIAL_CAPACITY);
            Map<String, ByteSource> fileBin = new HashMap<>(DEFAULT_INITIAL_CAPACITY);

            analysisParams(formData, textMap, fileBin);

            intoOutStream(boundary, out, textMap, fileBin);

            byte[] endData = ("\r\n--" + boundary + "--\r\n").getBytes();
            out.write(endData);
            out.flush();
            out.close();
            return builder.POST(BodyPublishers.ofByteArray(out.toByteArray())).build();
        }
    }

    /**
     * 分析参数
     *
     * @param formData formData
     * @param textMap  文本参数
     * @param fileBin  文件名
     */
    private void analysisParams(FormData formData, Map<String, Object> textMap, Map<String, ByteSource> fileBin) {
        formData.forEach((key, value) -> {
            if (null == value) {
                textMap.put(key, null);
                return;
            }
            if (value instanceof ByteSource byteSource) {
                fileBin.put(key, byteSource);
                return;
            }
            textMap.put(key, value);
        });
    }

    /**
     * 赋值
     *
     * @param boundary boundary
     * @param out      输出流
     * @param textMap  文本参数
     * @param fileBin  文件名
     */
    @SneakyThrows
    private void intoOutStream(String boundary, OutputStream out, Map<String, Object> textMap,
            Map<String, ByteSource> fileBin) {
        StringBuilder strBuf = new StringBuilder();
        for (Map.Entry<String, Object> entry : textMap.entrySet()) {
            strBuf.append("\r\n").append("--").append(boundary).append("\r\n");
            strBuf.append("Content-Disposition: form-data; name=\"").append(entry.getKey()).append("\"\r\n\r\n");
            strBuf.append(entry.getValue());
        }

        out.write(strBuf.toString().getBytes());
        for (Map.Entry<String, ByteSource> entry : fileBin.entrySet()) {
            String key = entry.getKey();
            ByteSource byteSource = fileBin.get(key);

            String fileBuf = "\r\n" + "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"; filename=\""
                    + byteSource.getFilename() + "\"\r\n" +
                    "Content-Type:" + OCTET_STREAM + "\r\n\r\n";
            out.write(fileBuf.getBytes());

            try (DataInputStream in = new DataInputStream(byteSource.getInputStream())) {
                int bytes;
                byte[] bufferOut = new byte[1024];
                while ((bytes = in.read(bufferOut)) != -1) {
                    out.write(bufferOut, 0, bytes);
                }
            }

        }
    }

    /**
     * 设置配置
     *
     * @param builder connection
     */
    private void doAnalysisRequestConfig(Builder builder) {
        long timeout = request.getConnectTimeoutMill();
        // 设置一个指定的超时值（以毫秒为单位）
        if (timeout > 0L) {
            builder.timeout(Duration.of(timeout, ChronoUnit.MILLIS));
        }

        long readTimeout = request.getReadTimeoutMill();
        // 将读超时设置为指定的超时，以毫秒为单位。
        if (readTimeout > 0L) {
            builder.timeout(Duration.of(readTimeout, ChronoUnit.MILLIS));
        }

        if (log.isDebugEnabled()) {
            log.debug("链接配置设置完成!!");
            if (log.isDebugEnabled()) {
                log.debug("=======================================================");
            }
            if (log.isDebugEnabled()) {
                log.debug("connectTimeout: {}", timeout);
            }
            if (log.isDebugEnabled()) {
                log.debug("readTimeout: {}", readTimeout);
            }
            if (log.isDebugEnabled()) {
                log.debug("=======================================================");
            }
        }
    }

}