package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.video.model.VideoDownload;
import com.chua.common.support.utils.StringUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 磁力类资源提供者的公共支撑。
 *
 * <p>集中处理三件事：</p>
 * <ol>
 *   <li><b>代理</b>：按 {@code video.search.proxy} 系统属性 →
 *       {@code VIDEO_SEARCH_PROXY} 环境变量 → 标准 {@code https.proxyHost/https.proxyPort}
 *       → {@code HTTPS_PROXY/HTTP_PROXY} 环境变量的顺序解析代理，
 *       便于访问需要代理的境外站点（如 nyaa、torrentkitty）；</li>
 *   <li><b>磁力拼接</b>：统一生成 {@code magnet:?xt=urn:btih:{hash}&dn={title}}；</li>
 *   <li><b>下载项组装</b>：把磁力同时写入 {@link VideoDownload#getVideoDownloadMagnetic()}。</li>
 * </ol>
 *
 * <p>代理示例：{@code -Dvideo.search.proxy=127.0.0.1:7897}，
 * 或设置环境变量 {@code VIDEO_SEARCH_PROXY=127.0.0.1:7897}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class MagnetSupport {

    /**
     * 模拟浏览器的 User-Agent
     */
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * 连接超时
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(12);

    /**
     * 请求超时
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 最大尝试次数（网络抖动/限流时重试一次）
     */
    private static final int MAX_ATTEMPTS = 2;

    private MagnetSupport() {
    }

    /**
     * 创建带代理的 HTTP 客户端。
     *
     * @return HTTP 客户端
     */
    static HttpClient client() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        ProxySelector selector = proxySelector();
        if (selector != null) {
            builder.proxy(selector);
        }
        return builder.build();
    }

    /**
     * 解析代理配置。
     *
     * @return 代理选择器，未配置返回 空
     */
    static ProxySelector proxySelector() {
        String value = System.getProperty("video.search.proxy");
        if (!StringUtils.hasText(value)) {
            value = System.getenv("VIDEO_SEARCH_PROXY");
        }
        if (!StringUtils.hasText(value)) {
            String host = System.getProperty("https.proxyHost", System.getProperty("http.proxyHost"));
            String port = System.getProperty("https.proxyPort", System.getProperty("http.proxyPort"));
            if (StringUtils.hasText(host) && StringUtils.hasText(port)) {
                value = host + ":" + port;
            }
        }
        if (!StringUtils.hasText(value)) {
            value = System.getenv("HTTPS_PROXY");
        }
        if (!StringUtils.hasText(value)) {
            value = System.getenv("HTTP_PROXY");
        }
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String raw = value.trim();
        int scheme = raw.indexOf("://");
        if (scheme >= 0) {
            raw = raw.substring(scheme + 3);
        }
        int slash = raw.indexOf('/');
        if (slash >= 0) {
            raw = raw.substring(0, slash);
        }
        int at = raw.lastIndexOf('@');
        if (at >= 0) {
            raw = raw.substring(at + 1);
        }
        int colon = raw.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            String host = raw.substring(0, colon);
            int port = Integer.parseInt(raw.substring(colon + 1).trim());
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * GET 请求并返回 UTF-8 文本。
     *
     * <p>代理链路偶发连接重置/握手中断，这里对网络异常与 429/5xx 自动重试一次，
     * 对 4xx（除 429）直接失败，避免无意义的重试。</p>
     *
     * @param url     请求地址
     * @param referer 来源页，可空
     * @return 响应文本
     * @throws IOException          请求失败或非 200
     * @throws InterruptedException 线程被中断
     */
    static String get(String url, String referer) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return getOnce(url, referer);
            } catch (IOException e) {
                last = e;
                if (attempt >= MAX_ATTEMPTS || !retryable(e)) {
                    throw e;
                }
                Thread.sleep(800L * attempt);
            }
        }
        throw last;
    }

    /**
     * 单次 GET 请求。
     *
     * @param url     请求地址
     * @param referer 来源页，可空
     * @return 响应文本
     * @throws IOException          请求失败或非 200
     * @throws InterruptedException 线程被中断
     */
    private static String getOnce(String url, String referer) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET();
        if (StringUtils.hasText(referer)) {
            builder.header("Referer", referer);
        }
        HttpResponse<byte[]> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new HttpStatusException(response.statusCode());
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * 判断异常是否值得重试。
     *
     * @param e 异常
     * @return 值得重试返回 true
     */
    private static boolean retryable(IOException e) {
        if (e instanceof HttpStatusException status) {
            return status.getStatus() == 429 || status.getStatus() >= 500;
        }
        return true;
    }

    /**
     * 非 200 响应异常（携带状态码，便于判断是否重试）。
     */
    private static final class HttpStatusException extends IOException {

        /**
         * HTTP 状态码
         */
        private final int status;

        /**
         * 创建 HTTP 状态异常
         *
         * @param status 状态码
         */
        private HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }

        /**
         * 获取状态码
         *
         * @return 状态码
         */
        private int getStatus() {
            return status;
        }
    }

    /**
     * 构建磁力链接。
     *
     * @param hash  infohash（40 位十六进制或 32 位 base32）
     * @param title 资源标题（作为 dn）
     * @return 磁力链接
     */
    static String magnet(String hash, String title) {
        return "magnet:?xt=urn:btih:" + hash + "&dn=" + encode(title);
    }

    /**
     * 构建下载项。
     *
     * @param title       资源标题
     * @param magnet      磁力链接
     * @param size        大小
     * @param platform    来源平台
     * @param shareTime   发布时间
     * @param quality     清晰度/格式
     * @return 下载项
     */
    static VideoDownload download(String title, String magnet, String size,
                                  String platform, String shareTime, String quality) {
        VideoDownload download = new VideoDownload();
        download.setVideoDownloadName(title);
        download.setVideoDownloadUrl(magnet);
        download.setVideoDownloadMagnetic(magnet);
        download.setVideoDownloadSize(size);
        download.setVideoDownloadType("magnet");
        download.setVideoDownloadPlatform(platform);
        download.setVideoDownloadShareTime(shareTime);
        download.setVideoDownloadQuality(quality);
        return download;
    }

    /**
     * URL 编码（空格编码为 %20）。
     *
     * @param value 原值
     * @return 编码后的值
     */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 清洗文本（去不换行空格、压缩空白）。
     *
     * @param value 原文本
     * @return 清洗后的文本
     */
    static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    /**
     * 提取正则首个分组。
     *
     * @param pattern 正则
     * @param text    文本
     * @return 命中分组，未命中返回 空字符串
     */
    static String group(Pattern pattern, String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? clean(matcher.group(1)) : "";
    }

    /**
     * 截断文本。
     *
     * @param value 原文本
     * @param max   最大长度
     * @return 截断后的文本
     */
    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
