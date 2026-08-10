package com.chua.common.support.network.download;

import com.chua.common.support.lang.process.ProgressBar;
import com.chua.common.support.lang.process.ProgressBarBuilder;
import com.chua.common.support.lang.process.ProgressBarStyle;
import com.chua.common.support.network.download.extractor.Extractor;
import com.chua.common.support.network.download.extractor.ExtractorFactory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *     .concurrency(4)                    // 4 线程并发下载
 *     .maxSpeed(1024 * 1024)             // 限速 1MB/s
 *     .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy host", 8080)))
 *     .autoExtract(true)
 *     .extractTo(Path.of("/tmp/extracted"))
 *     .execute();
 * </pre>
 *
 * @author CH
 */
@Slf4j
public class Downloader {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 60_000;

    // ===== 链式配置字段 =====
    /**
     * 地址
     */
    private String url;
    private Path targetDir;
    private String filename;
    private String expectedMd5;
    private int concurrency = 1;
    // ; // bytes per second, 0 = unlimited
    private long maxSpeed = 0;
    private Proxy proxy;
    private boolean autoExtract = false;
    private Path extractTo;
    private boolean skipMd5Check = false;
    private boolean forceDownload = false;
    private boolean showProgress = true;
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private Map<String, String> headers = new LinkedHashMap<>();

    // ===== 构造 =====

    private Downloader() {}

    public static Downloader create() {
        return new Downloader();
    }

    // ===== 链式配置方法 =====

    public Downloader url(String url) {
        this.url = url;
        return this;
    }

    public Downloader target(Path targetDir) {
        this.targetDir = targetDir;
        return this;
    }

    public Downloader filename(String filename) {
        this.filename = filename;
        return this;
    }

    /** 设置期望的 MD5 值（null 则跳过校验） */
    public Downloader expectedMd5(String md5) {
        this.expectedMd5 = md5;
        return this;
    }

    /** 设置并发下载线程数（默认 1，大于 1 时自动分片） */
    public Downloader concurrency(int threads) {
        this.concurrency = Math.max(1, threads);
        return this;
    }

    /** 设置下载限速（bytes/sec，0 = 不限速） */
    public Downloader maxSpeed(long bytesPerSecond) {
        this.maxSpeed = bytesPerSecond;
        return this;
    }

    /** 设置代理 */
    public Downloader proxy(Proxy proxy) {
        this.proxy = proxy;
        return this;
    }

    /** 下载完成后自动解压 */
    public Downloader autoExtract(boolean autoExtract) {
        this.autoExtract = autoExtract;
        return this;
    }

    /** 解压目标目录（默认与下载目录相同） */
    public Downloader extractTo(Path extractTo) {
        this.extractTo = extractTo;
        return this;
    }

    /** 跳过 MD5 校验 */
    public Downloader skipMd5Check(boolean skip) {
        this.skipMd5Check = skip;
        return this;
    }

    /** 强制重新下载（忽略本地缓存） */
    public Downloader forceDownload(boolean force) {
        this.forceDownload = force;
        return this;
    }

    /** 是否显示下载进度条（默认 true） */
    public Downloader showProgress(boolean show) {
        this.showProgress = show;
        return this;
    }

    /** 设置连接超时（毫秒） */
    public Downloader connectTimeout(int ms) {
        this.connectTimeoutMs = ms;
        return this;
    }

    /** 设置读取超时（毫秒） */
    public Downloader readTimeout(int ms) {
        this.readTimeoutMs = ms;
        return this;
    }

    /** 添加自定义请求头 */
    public Downloader header(String name, String value) {
        this.headers.put(name, value);
        return this;
    }

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

        // 1. 检查本地文件是否已存在且 MD5 匹配
        if (!forceDownload && Files.isRegularFile(targetFile)) {
            if (!skipMd5Check && expectedMd5 != null && !expectedMd5.isBlank()) {
                String actualMd5 = computeMd5(targetFile);
                if (expectedMd5.equalsIgnoreCase(actualMd5)) {
                    log.info("[Downloader] MD5 匹配，跳过下载: {} ({})", resolvedFilename, actualMd5);
                    return buildResult(targetFile, true, "md5_match");
                }
                log.info("[Downloader] MD5 不匹配: expected={} actual={}，重新下载", expectedMd5, actualMd5);
            } else {
                log.info("[Downloader] 文件已存在（无 MD5 校验），跳过: {}", targetFile);
                return buildResult(targetFile, true, "file_exists");
            }
        }

        // 2. 检查是否支持断点续传
        long existingSize = Files.isRegularFile(targetFile) ? targetFile.toFile().length() : 0;
        boolean resumeSupported = checkResumeSupport();
        long resumeOffset = (resumeSupported && existingSize > 0) ? existingSize : 0;

        // 3. 执行下载
        Files.createDirectories(resolvedTargetDir);
        if (resumeOffset > 0) {
            log.info("[Downloader] 断点续传: offset={} bytes", resumeOffset);
        }

        if (concurrency > 1 && resumeOffset == 0) {
            downloadWithConcurrency(targetFile, resumeOffset);
        } else {
            downloadSingle(targetFile, resumeOffset);
        }

        // 4. MD5 校验
        if (!skipMd5Check && expectedMd5 != null && !expectedMd5.isBlank()) {
            String actualMd5 = computeMd5(targetFile);
            if (!expectedMd5.equalsIgnoreCase(actualMd5)) {
                Files.deleteIfExists(targetFile);
                throw new DownloadException("MD5 校验失败: expected=" + expectedMd5 + " actual=" + actualMd5);
            }
            log.info("[Downloader] MD5 校验通过: {}", actualMd5);
        }

        // 5. 自动解压
        if (autoExtract) {
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

        return buildResult(targetFile, false, "downloaded");
    }

    // ===== 内部实现 =====

    private void validate() throws DownloadException {
        if (url == null || url.isBlank()) {
            throw new DownloadException("URL 不能为空");
        }
        if (targetDir == null) {
            targetDir = Path.of(".");
        }
    }

    private String resolveFilename() {
        if (filename != null && !filename.isBlank()) {
            return filename;
        }
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return URLDecoder.decode(path.substring(lastSlash + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return "download";
    }

    private boolean checkResumeSupport() {
        try {
            HttpURLConnection conn = openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            applyHeaders(conn);
            conn.connect();
            boolean acceptRange = "bytes".equalsIgnoreCase(conn.getHeaderField("Accept-Ranges"));
            conn.disconnect();
            return acceptRange;
        } catch (Exception e) {
            return false;
        }
    }

    private void downloadSingle(Path targetFile, long resumeOffset) throws DownloadException {
        ProgressBar bar = null;
        try {
            HttpURLConnection conn = openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            applyHeaders(conn);

            if (resumeOffset > 0) {
                conn.setRequestProperty("Range", "bytes=" + resumeOffset + "-");
            }

            conn.connect();

            int responseCode = conn.getResponseCode();
            boolean isPartial = (responseCode == 206);
            if (responseCode != 200 && !isPartial) {
                conn.disconnect();
                throw new DownloadException("HTTP " + responseCode + ": " + conn.getResponseMessage());
            }

            long contentLength = conn.getContentLengthLong();
            long totalSize = isPartial ? resumeOffset + contentLength : contentLength;

            // 创建进度条
            if (showProgress && totalSize > 0) {
                bar = ProgressBarBuilder.builder()
                        .setTaskName(resolveFilename())
                        .setInitialMax(totalSize)
                        .setStyle(ProgressBarStyle.PYTHON_DOWNLOAD)
                        .showSpeed()
                        .startsFrom(resumeOffset, Duration.ZERO)
                        .build();
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(targetFile.toFile(), isPartial);
                 FileChannel channel = fos.getChannel()) {

                ThrottledInputStream throttled = maxSpeed > 0 ? new ThrottledInputStream(in, maxSpeed) : new ThrottledInputStream(in, 0);
                ReadableByteChannel rbc = Channels.newChannel(throttled);

                ByteBuffer buf = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
                long downloaded = resumeOffset;
                int n;
                while ((n = rbc.read(buf)) != -1) {
                    buf.flip();
                    channel.write(buf);
                    buf.clear();
                    downloaded += n;
                    if (bar != null) {
                        bar.stepBy(n);
                    }
                }
            } finally {
                conn.disconnect();
                if (bar != null) {
                    bar.close();
                }
            }

            log.info("[Downloader] 下载完成: {} ({} bytes)", targetFile.getFileName(), Files.size(targetFile));
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException("下载失败: " + e.getMessage(), e);
        }
    }

    private void downloadWithConcurrency(Path targetFile, long resumeOffset) throws DownloadException {
        try {
            // 获取文件总大小
            HttpURLConnection headConn = openConnection();
            headConn.setRequestMethod("HEAD");
            headConn.setConnectTimeout(connectTimeoutMs);
            headConn.setReadTimeout(readTimeoutMs);
            applyHeaders(headConn);
            headConn.connect();
            long totalSize = headConn.getContentLengthLong();
            headConn.disconnect();

            if (totalSize <= 0) {
                log.warn("[Downloader] 无法获取文件大小，降级为单线程下载");
                downloadSingle(targetFile, 0);
                return;
            }

            // 创建总进度条
            ProgressBar totalBar = null;
            if (showProgress) {
                totalBar = ProgressBarBuilder.builder()
                        .setTaskName(resolveFilename())
                        .setInitialMax(totalSize)
                        .setStyle(ProgressBarStyle.PYTHON_DOWNLOAD)
                        .showSpeed()
                        .startsFrom(resumeOffset, Duration.ZERO)
                        .build();
            }

            // 分片计算
            long chunkSize = totalSize / concurrency;
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < concurrency; i++) {
                long start = resumeOffset + i * chunkSize;
                long end = (i == concurrency - 1) ? totalSize - 1 : start + chunkSize - 1;
                int partIndex = i;
                ProgressBar finalBar = totalBar;
                futures.add(pool.submit(() -> downloadChunk(targetFile, start, end, partIndex, finalBar)));
            }

            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();

            if (totalBar != null) {
                totalBar.close();
            }

            log.info("[Downloader] 并发下载完成: {} ({} bytes, {} threads)",
                    targetFile.getFileName(), Files.size(targetFile), concurrency);
        } catch (ExecutionException e) {
            throw new DownloadException("并发下载失败: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("并发下载被中断", e);
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException("并发下载失败: " + e.getMessage(), e);
        }
    }

    private void downloadChunk(Path targetFile, long start, long end, int partIndex, ProgressBar totalBar) {
        String partFile = targetFile + ".part" + partIndex;
        try {
            HttpURLConnection conn = openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            applyHeaders(conn);
            conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != 206) {
                conn.disconnect();
                throw new IOException("HTTP " + responseCode + " for chunk " + partIndex);
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(partFile)) {
                ThrottledInputStream throttled = maxSpeed > 0 ? new ThrottledInputStream(in, maxSpeed) : new ThrottledInputStream(in, 0);
                byte[] buf = new byte[DEFAULT_BUFFER_SIZE];
                int n;
                while ((n = throttled.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    if (totalBar != null) {
                        totalBar.stepBy(n);
                    }
                }
            } finally {
                conn.disconnect();
            }

            log.debug("[Downloader] 分片 {} 下载完成", partIndex);
        } catch (Exception e) {
            log.error("[Downloader] 分片 {} 下载失败: {}", partIndex, e.getMessage());
            throw new RuntimeException("Chunk " + partIndex + " failed", e);
        }
    }

    private void mergeParts(Path targetFile, int partCount) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(targetFile.toFile());
             FileChannel out = fos.getChannel()) {
            for (int i = 0; i < partCount; i++) {
                Path part = Path.of(targetFile + ".part" + i);
                if (Files.isRegularFile(part)) {
                    try (FileChannel in = FileChannel.open(part, StandardOpenOption.READ)) {
                        in.transferTo(0, in.size(), out);
                    }
                    Files.delete(part);
                }
            }
        }
    }

    private HttpURLConnection openConnection() throws IOException {
        URL u = new URL(url);
        HttpURLConnection conn;
        if (proxy != null) {
            conn = (HttpURLConnection) u.openConnection(proxy);
        } else {
            conn = (HttpURLConnection) u.openConnection();
        }
        return conn;
    }

    private void applyHeaders(HttpURLConnection conn) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
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

    private DownloadResult buildResult(Path file, boolean skipped, String reason) {
        return DownloadResult.builder()
                .success(true)
                .file(file)
                .skipped(skipped)
                .reason(reason)
                .md5(expectedMd5)
                .build();
    }

    // ===== 限速 InputStream =====

    /**
     * 限速 InputStream — 通过令牌桶算法控制读取速率。
     */
    private static class ThrottledInputStream extends InputStream {
        private final InputStream delegate;
        private final long bytesPerMs;
        private long tokens;
        private long lastRefill;

        ThrottledInputStream(InputStream delegate, long bytesPerSecond) {
            this.delegate = delegate;
            this.bytesPerMs = bytesPerSecond / 1000;
            this.tokens = bytesPerSecond;
            this.lastRefill = System.currentTimeMillis();
        }

        @Override
        public int read() throws IOException {
            throttle(1);
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            throttle(len);
            return delegate.read(b, off, len);
        }

        private void throttle(int bytes) {
            if (bytesPerMs <= 0) {
                return;
            }
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            tokens = Math.min(tokens + elapsed * bytesPerMs, bytesPerMs * 1000);
            lastRefill = now;

            while (tokens < bytes) {
                try { Thread.sleep(1); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                now = System.currentTimeMillis();
                elapsed = now - lastRefill;
                tokens = Math.min(tokens + elapsed * bytesPerMs, bytesPerMs * 1000);
                lastRefill = now;
            }
            tokens -= bytes;
        }

        @Override
        public void close() throws IOException { delegate.close(); }
    }

    // ===== 结果 & 异常 =====

    @lombok.Builder
    @lombok.Data
    public static class DownloadResult {
        /**
         * 是否成功
         */
        private boolean success;
        /**
         * 文件路径
         */
        private Path file;
        private boolean skipped;
        private String reason;
        private String md5;
    }

    public static class DownloadException extends IOException {
        public DownloadException(String message) { super(message); }
        public DownloadException(String message, Throwable cause) { super(message, cause); }
    }
}
