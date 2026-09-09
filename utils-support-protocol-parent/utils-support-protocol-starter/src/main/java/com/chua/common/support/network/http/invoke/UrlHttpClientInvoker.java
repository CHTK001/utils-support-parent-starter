package com.chua.common.support.network.http.invoke;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.io.binary.ByteSource;
import com.chua.common.support.network.http.*;
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.core.utils.CollectionUtils;
import com.chua.common.support.core.utils.IoUtils;
import com.chua.common.support.core.utils.StringUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.chua.common.support.core.constant.NumberConstant.DEFAULT_INITIAL_CAPACITY;
import static com.chua.common.support.network.http.HttpConstant.HTTP_HEADER_CONTENT_TYPE;
import static com.chua.common.support.network.http.HttpMethod.*;
import static com.chua.common.support.media.MediaType.OCTET_STREAM;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 执行器
 *
 * @author CH
 */
@Slf4j
@Spi(value = { "httpclient", "url" }, order = 1)
public class UrlHttpClientInvoker extends AbstractHttpClientInvoker {

    public UrlHttpClientInvoker(HttpRequest request, HttpMethod httpMethod) {
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
        HttpURLConnection connection = null;
        try {
            doAnalysisUrl(method);
            if (log.isDebugEnabled()) {
                log.debug("==================================================");
            }
            log.info("发送的URL: {}", url);
            connection = urlConnection();
            // 设定请求的方法，默认是GET
            doAnalysisRequestMethod(connection, method);
            // 设置消息头
            doAnalysisHeader(connection);
            // 设置认证
            doAnalysisBasicAuth(connection);
            // 设置缓存
            doAnalysisCache(connection, method);
            // 设置消息体
            doAnalysisBody(connection);
            // 设置配置
            doAnalysisRequestConfig(connection);
            // 建立实际的连接
            // 打开到此 URL 引用的资源的通信链接（如果尚未建立这样的连接）
            // 如果在已打开连接（此时 connected 字段的值为 true）的情况下调用 connect 方法，则忽略该调用

            connection.connect();
            // 获取结果
            int code = connection.getResponseCode();
            Map<String, List<String>> fields = connection.getHeaderFields();
            HttpHeader header = new HttpHeader();
            for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
                header.addHeader(entry.getKey(), CollectionUtils.findFirst(entry.getValue()));
            }
            if (code == 302) {
                if (retry > request.getRetry()) {
                    return HttpResponse.builder().code(500).build();
                }

                String location = header.getHeader("Location");
                if (StringUtils.isNotBlank(location)) {
                    connection.disconnect();
                    request.setUrl(location);
                    this.url = location;
                    return executeMethod(method, retry + 1);
                }
            }
            Object content;
            byte[] bytes;
            try (InputStream stream = connection.getInputStream()) {
                bytes = IoUtils.toByteArray(stream);
                content = contentEncoding(bytes, header.getHeader("content-encoding"));
            }
            log.info("接收到URL: {}, 响应码: {}", url, code);
            // 回写缓存（仅GET且启用缓存）
            if (method == GET && request.isUseCache()) {
                try {
                    String cacheKey = this.url;
                    HttpCacheRegistry.getCache(request.getCacheTtlMillis(), request.getCacheMaxSize())
                            .put(cacheKey,
                                    HttpResponse.builder().code(code).content(content).httpHeader(header).build());
                } catch (Exception ignore) {
                }
            }

            return HttpResponse.builder().code(code).content(content).httpHeader(header).build();
        } catch (Throwable e) {
            log.error("", e);
            if (retry < request.getRetry()) {
                return executeMethod(method, retry + 1);
            }
            return HttpResponse.builder().code(500).message(e.getMessage()).build();
        } finally {
            IoUtils.closeQuietly(connection);
        }
    }

    /**
     * 获取链接
     *
     * @return HttpURLConnection
     * @throws IOException IOException
     */
    private HttpURLConnection urlConnection() throws IOException {
        HttpURLConnection connection;
        URL realUrl;
        if (!isHttps) {
            realUrl = new URL(url);
            // 打开和URL之间的连接
            if (StringUtils.isNotEmpty(request.getProxy())) {
                connection = (HttpURLConnection) realUrl.openConnection(
                        ServiceProvider.of(Proxy.class).getNewExtension(request.getProxy()));
            } else {
                connection = (HttpURLConnection) realUrl.openConnection();
            }
        } else {
            Object sslSocketFactory = request.getSslSocketFactory();
            if (sslSocketFactory instanceof SSLSocketFactory) {
                HttpsURLConnection.setDefaultSSLSocketFactory((SSLSocketFactory) sslSocketFactory);
            } else {
                HttpsURLConnection.setDefaultSSLSocketFactory(HttpClientUtils.createSslSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier(HttpClientUtils.createDefaultHostnameVerifier());
            }
            realUrl = new URL(url);
            HttpsURLConnection urlConnection = null;
            if (StringUtils.isNotEmpty(request.getProxy())) {
                urlConnection = (HttpsURLConnection) realUrl.openConnection(
                        ServiceProvider.of(Proxy.class).getNewExtension(request.getProxy()));
            } else {
                urlConnection = (HttpsURLConnection) realUrl.openConnection();
            }
            if (sslSocketFactory instanceof SSLSocketFactory) {
                urlConnection.setSSLSocketFactory((SSLSocketFactory) sslSocketFactory);
            }
            connection = urlConnection;
        }
        return connection;
    }

    /**
     * 设置方法类型
     *
     * @param connection 链接
     * @param method     方法
     */
    private void doAnalysisRequestMethod(HttpURLConnection connection, HttpMethod method) throws ProtocolException {
        // 设置是否从httpUrlConnection读入，默认情况下是true;
        connection.setDoInput(true);
        connection.setRequestMethod(method.name());
    }

    /**
     * 设置消息头
     *
     * @param connection connection
     */
    private void doAnalysisHeader(URLConnection connection) {
        if (isHttps && connection instanceof HttpURLConnection) {
            ((HttpURLConnection) connection).setInstanceFollowRedirects(false);
        }

        HttpHeader header = request.getHeaders();

        if (!header.isEmpty()) {
            header.forEach(connection::setRequestProperty);
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
     * @param connection 连接
     */
    private void doAnalysisBasicAuth(HttpURLConnection connection) {
        if (request.getBasicAuth().isEmpty()) {
            return;
        }
        String encoding = "";
        for (Map.Entry<String, String> entry : request.getBasicAuth().entrySet()) {
            encoding = Base64.getEncoder()
                    .encodeToString((entry.getKey() + ":" + entry.getValue()).getBytes(StandardCharsets.UTF_8));
        }
        connection.setRequestProperty("Authorization", String.format("Basic %s", encoding));
    }

    /**
     * Post 请求不能使用缓存
     *
     * @param connection 链接
     * @param method     方法
     */
    private void doAnalysisCache(HttpURLConnection connection, HttpMethod method) {
        // Post 请求不能使用缓存
        if (Objects.equals(GET, method)) {
            connection.setUseCaches(true);
            return;
        }
        connection.setUseCaches(false);
    }

    /**
     * 设置消息体
     *
     * @param connection 链接
     */
    private void doAnalysisBody(HttpURLConnection connection) {

        try {
            connection.setDoOutput(true);
        } catch (Exception ignored) {
        }

        if (request.isForm()) {
            doAnalysisForm(connection);
            return;
        }

        BodyData bodyData = request.getBodyData();
        if (bodyData.isEmpty()) {
            return;
        }

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(bodyData.toByteArray());
            outputStream.flush();
        } catch (IOException e) {
            log.error("", e);
        }

    }

    private void doAnalysisForm(HttpURLConnection connection) {
        if (request.isFormData()) {
            // 上传文件
            try {
                formData(connection, request.getFormData());
            } catch (IOException e) {
                log.error("", e);
            }

            return;
        }

        // 非上传文件
        String contentType = request.getHeaders().getHeader(HTTP_HEADER_CONTENT_TYPE, "*");
        try (OutputStream outputStream = connection.getOutputStream()) {
            byte[] bytes = request.getFormData().toByteArray();
            if (log.isDebugEnabled()) {
                log.debug("消息体: \r\n{}", StringUtils.utf8Str(bytes));
                if (log.isDebugEnabled()) {
                    log.debug("==================================================");
                }
            }
            outputStream.write(bytes);
            outputStream.flush();
        } catch (IOException e) {
            log.error("", e);
        }
    }

    /**
     * 文件 & 表单
     *
     * @param connection 链接
     * @param formData   请求条件
     */
    private void formData(HttpURLConnection connection, FormData formData) throws IOException {
        String boundary = "---------------------------";
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        OutputStream out = new DataOutputStream(connection.getOutputStream());
        Map<String, Object> textMap = new HashMap<>(DEFAULT_INITIAL_CAPACITY);
        Map<String, ByteSource> fileBin = new HashMap<>(DEFAULT_INITIAL_CAPACITY);

        analysisParams(formData, textMap, fileBin);

        intoOutStream(boundary, out, textMap, fileBin);

        byte[] endData = ("\r\n--" + boundary + "--\r\n").getBytes();
        out.write(endData);
        out.flush();
        out.close();
    }

    /**
     * 分析参数
     *
     * @param formData 请求体
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
     * @param fileBin  文件
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
     * @param connection connection
     */
    private void doAnalysisRequestConfig(URLConnection connection) {
        long timeout = request.getConnectTimeoutMill();
        // 设置一个指定的超时值（以毫秒为单位）
        if (timeout > 0L) {
            connection.setConnectTimeout(((Long) timeout).intValue());
        }

        long readTimeout = request.getReadTimeoutMill();
        // 将读超时设置为指定的超时，以毫秒为单位。
        if (readTimeout > 0L) {
            connection.setReadTimeout(((Long) readTimeout).intValue());
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