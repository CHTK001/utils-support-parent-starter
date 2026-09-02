package com.chua.common.support.network.download;

import com.chua.common.support.lang.process.ProgressBar;
import com.chua.common.support.lang.process.ProgressBarBuilder;
import com.chua.common.support.lang.process.ProgressBarStyle;
import com.chua.common.support.network.download.extractor.Extractor;
import com.chua.common.support.network.download.extractor.ExtractorFactory;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 通用文件下载器 — 链式 API，支持并发分片、断点续传、MD5 校验、限速、代理、自动解压。
 *
 * <h3>用法示例</h3>
 * <pre>
 * // 基础用法
 * Downloader.create()
 *     .url("https://example.com/file.zip")
 *     .target(Path.of("/tmp/downloads"))
 *     .expectedMd5("abc123")
 *     .autoExtract(true)
 *     .execute();
 *
 * // 高级用法：并发分片 + 限速 + 代理
 * Downloader.create()
 *     .url("https://example.com/large-file.tar.gz")
 *     .target(Path.of("/tmp/downloads"))
 *     .concurrency(4)
 *     .maxSpeed(1024 * 1024)
 *     .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy host", 8080)))
 *     .autoExtract(true)
 *     .extractTo(Path.of("/tmp/extracted"))
 *     .execute();
 *
 * // 使用 aria2c 协议
 * Downloader.create()
 *     .url("https://example.com/large-file.zip")
 *     .target(Path.of("/tmp/downloads"))
 *     .protocol(DownloadProtocol.ARIA2)
 *     .execute();
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class Downloader {

    // ===== 链式配置字段 =====
    private String url;
    private Path targetDir;
    private String filename;
    private String expectedMd5;
    private int concurrency = 1;
    private long maxSpeed = 0;
    private Proxy proxy;
    private boolean autoExtract = false;
    private Path extractTo;
    private boolean skipMd5Check = false;
    private boolean forceDownload = false;
    private boolean showProgress = true;
    private int connectTimeoutMs = 15_000;
    private int readTimeoutMs = 60_000;
    private java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
    private DownloadProtocol protocol = DownloadProtocol.DEFAULT;

    // ===== 构造 =====
    private Downloader() {}

    /** 创建 Downloader 实例 */
    public static Downloader create() { return new Downloader(); }

    // ===== 链式配置方法 =====

    public Downloader url(String url) { this.url = url; return this; }
    public Downloader target(Path targetDir) { this.targetDir = targetDir; return this; }
    public Downloader filename(String filename) { this.filename = filename; return this; }

    /** 设置期望的 MD5 值（null 则跳过校验） */
    public Downloader expectedMd5(String md5) { this.expectedMd5 = md5; return this; }
    public Downloader concurrency(int threads) { this.concurrency = Math.max(1, threads); return this; }
    public Downloader maxSpeed(long bytesPerSecond) { this.maxSpeed = bytesPerSecond; return this; }
    public Downloader proxy(Proxy proxy) { this.proxy = proxy; return this; }
    public Downloader autoExtract(boolean autoExtract) { this.autoExtract = autoExtract; return this; }
    public Downloader extractTo(Path extractTo) { this.extractTo = extractTo; return this; }
    public Downloader skipMd5Check(boolean skip) { this.skipMd5Check = skip; return this; }
    public Downloader forceDownload(boolean force) { this.forceDownload = force; return this; }
    public Downloader showProgress(boolean show) { this.showProgress = show; return this; }
    public Downloader connectTimeout(int ms) { this.connectTimeoutMs = ms; return this; }
    public Downloader readTimeout(int ms) { this.readTimeoutMs = ms; return this; }

    /** 添加自定义请求头 */
    public Downloader header(String name, String value) { this.headers.put(name, value); return this; }

    /**
     * 设置下载协议。
     * <ul>
     *   <li>{@link DownloadProtocol#DEFAULT} — 内置 HTTP/HTTPS（单线程/并发分片/断点续传）</li>
     *   <li>{@link DownloadProtocol#ARIA2} — 委托本机 aria2c</li>
     * </ul>
     */
    public Downloader protocol(DownloadProtocol protocol) { this.protocol = protocol; return this; }

    // ===== 执行下载 =====

    /**
     * 执行下载任务。
     *
     * @return 下载结果
     * @throws DownloadException 下载失败
     */
    public DownloadResult execute() throws DownloadException, IOException {
        validate();

        String resolvedFilename = resolveFilename();
        Path resolvedTargetDir = targetDir != null ? targetDir : Path.of(".");
        Path targetFile = resolvedTargetDir.resolve(resolvedFilename);

        // 构建下载配置
        DownloadConfig config = DownloadConfig.builder()
                .url(url)
                .targetDir(resolvedTargetDir)
                .filename(resolvedFilename)
                .expectedMd5(expectedMd5)
                .concurrency(concurrency)
                .maxSpeed(maxSpeed)
                .proxy(proxy)
                .autoExtract(autoExtract)
                .extractTo(extractTo)
                .skipMd5Check(skipMd5Check)
                .forceDownload(forceDownload)
                .showProgress(showProgress)
                .connectTimeoutMs(connectTimeoutMs)
                .readTimeoutMs(readTimeoutMs)
                .headers(headers)
                .build();

        // 获取协议实现
        String serviceKey = protocol == DownloadProtocol.ARIA2 ? "aria2" : "default";
        DownloadService service = ServiceProvider.of(DownloadService.class).getExtension(serviceKey);
        if (service == null) {
            throw new DownloadException("未找到下载服务实现: " + serviceKey);
        }

        // 执行下载
        DownloadResult result = service.execute(config);

        // MD5 校验（aria2c 内部已校验，若非 force 且文件存在则跳过重复校验）
        if (!skipMd5Check && expectedMd5 != null && !expectedMd5.isBlank() && !result.isSkipped()) {
            String actualMd5 = computeMd5(targetFile);
            if (!expectedMd5.equalsIgnoreCase(actualMd5)) {
                Files.deleteIfExists(targetFile);
                throw new DownloadException("MD5 校验失败: expected=" + expectedMd5 + " actual=" + actualMd5);
            }
            log.info("[Downloader] MD5 校验通过: {}", actualMd5);
        }

        // 自动解压
        if (autoExtract && !result.isSkipped()) {
            Path dest = extractTo != null ? extractTo : resolvedTargetDir;
            Files.createDirectories(dest);
            Extractor extractor = ExtractorFactory.getExtractor(targetFile.toString());
            if (extractor != null) {
                log.info("[Downloader] 自动解压: {} -> {}", targetFile.getFileName(), dest);
                extractor.extract(targetFile.toFile(), dest.toFile());
            } else {
                log.warn("[Downloader] 不支持的压缩格式，跳过解压: {}", targetFile.getFileName());
            }
        }

        return result;
    }

    // ======================== 内部方法 ========================

    private void validate() throws DownloadException {
        if (url == null || url.isBlank()) {
            throw new DownloadException("URL 不能为空");
        }
        if (targetDir == null) {
            targetDir = Path.of(".");
        }
    }

    private String resolveFilename() {
        if (filename != null && !filename.isBlank()) return filename;
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return URLDecoder.decode(path.substring(lastSlash + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return "download";
    }

    private String computeMd5(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) { md.update(buf, 0, n); }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 不可用", e);
        } catch (IOException e) {
            log.warn("[Downloader] 计算 MD5 失败: {}", e.getMessage());
            return "";
        }
    }

    // ===== 结果 & 异常 =====

    @lombok.Builder
    @lombok.Data
    public static class DownloadResult {
        /** 是否成功 */
        private boolean success;
        /** 文件路径 */
        private Path file;
        /** 是否跳过（文件已存在且校验通过） */
        private boolean skipped;
        /** 跳过/成功原因 */
        private String reason;
        /** MD5 */
        private String md5;
    }

    public static class DownloadException extends IOException {
        public DownloadException(String message) { super(message); }
        public DownloadException(String message, Throwable cause) { super(message, cause); }
    }
}
