package com.chua.common.support.network.download;

import com.chua.common.support.lang.process.ProgressBar;
import com.chua.common.support.lang.process.ProgressBarBuilder;
import com.chua.common.support.lang.process.ProgressBarStyle;
import com.chua.common.support.utils.DigestUtils;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 内置 HTTP/HTTPS 下载实现。
 *
 * <p>支持：单线程、并发分片、断点续传、限速、进度条显示。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("default")
public class DefaultDownloadService implements DownloadService {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    @Override
    public DownloadResult execute(DownloadConfig config) throws DownloadException, IOException {
        String filename = DownloadUtils.resolveFilename(config.getUrl(), config.getFilename());
        Path targetDir = config.getTargetDir() != null ? config.getTargetDir() : Path.of(".");
        Path targetFile = targetDir.resolve(filename);

        // 1. 本地缓存检查
        if (!config.isForceDownload() && Files.isRegularFile(targetFile)) {
            if (!config.isSkipMd5Check() && config.getExpectedMd5() != null && !config.getExpectedMd5().isBlank()) {
                String actualMd5 = DownloadUtils.computeMd5(targetFile);
                if (config.getExpectedMd5().equalsIgnoreCase(actualMd5)) {
                    log.info("[DownloadService] MD5 匹配，跳过下载: {} ({})", filename, actualMd5);
                    return buildResult(targetFile, true, "md5_match", config.getExpectedMd5());
                }
                log.info("[DownloadService] MD5 不匹配: expected={} actual={}，重新下载", config.getExpectedMd5(), actualMd5);
            } else {
                log.info("[DownloadService] 文件已存在（无 MD5 校验），跳过: {}", targetFile);
                return buildResult(targetFile, true, "file_exists", config.getExpectedMd5());
            }
        }

        // 2. 断点续传
        long existingSize = Files.isRegularFile(targetFile) ? targetFile.toFile().length() : 0;
        boolean resumeSupported = checkResumeSupport(config);
        long resumeOffset = (resumeSupported && existingSize > 0) ? existingSize : 0;

        // 3. 执行下载
        Files.createDirectories(targetDir);
        if (resumeOffset > 0) {
            log.info("[DownloadService] 断点续传: offset={} bytes", resumeOffset);
        }

        if (config.getConcurrency() > 1 && resumeOffset == 0) {
            downloadWithConcurrency(targetFile, config, resumeOffset);
        } else {
            downloadSingle(targetFile, config, resumeOffset);
        }

        return buildResult(targetFile, false, "downloaded", config.getExpectedMd5());
    }

    // ======================== 单线程下载 ========================

    /**
     * 单线程顺序下载。
     *
     * @param targetFile   目标文件路径
     * @param config       下载配置
     * @param resumeOffset 断点续传起始偏移字节
     * @throws IOException 当网络或文件系统操作失败时
     */
    private void downloadSingle(Path targetFile, DownloadConfig config, long resumeOffset) throws IOException {
        ProgressBar bar = null;
        try {
            HttpURLConnection conn = openConnection(config);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            applyHeaders(conn, config);

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

            if (config.isShowProgress() && totalSize > 0) {
                bar = ProgressBarBuilder.builder()
                        .setTaskName(targetFile.getFileName().toString())
                        .setInitialMax(totalSize)
                        .setStyle(ProgressBarStyle.PYTHON_DOWNLOAD)
                        .showSpeed()
                        .startsFrom(resumeOffset, Duration.ZERO)
                        .build();
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(targetFile.toFile(), isPartial);
                 FileChannel channel = fos.getChannel()) {

                ThrottledInputStream throttled = new ThrottledInputStream(in, config.getMaxSpeed());
                ReadableByteChannel rbc = Channels.newChannel(throttled);

                var buf = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
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

            log.info("[DownloadService] 下载完成: {} ({} bytes)", targetFile.getFileName(), Files.size(targetFile));
        } catch (DownloadException e) {
            throw e;
        } catch (IOException e) {
            throw new DownloadException("下载失败: " + e.getMessage(), e);
        }
    }

    // ======================== 并发分片下载 ========================

    /**
     * 并发分片下载，将文件等分成多个块由不同线程同时下载，最后合并。
     *
     * @param targetFile   目标文件路径
     * @param config       下载配置
     * @param resumeOffset 断点续传起始偏移（当前不支持并发+断点续传混合）
     * @throws DownloadException 当并发下载或合并分片失败时
     */
    private void downloadWithConcurrency(Path targetFile, DownloadConfig config, long resumeOffset) throws DownloadException {
        try {
            HttpURLConnection headConn = openConnection(config);
            headConn.setRequestMethod("HEAD");
            headConn.setConnectTimeout(config.getConnectTimeoutMs());
            headConn.setReadTimeout(config.getReadTimeoutMs());
            applyHeaders(headConn, config);
            headConn.connect();
            long totalSize = headConn.getContentLengthLong();
            headConn.disconnect();

            if (totalSize <= 0) {
                log.warn("[DownloadService] 无法获取文件大小，降级为单线程下载");
                downloadSingle(targetFile, config, 0);
                return;
            }

            ProgressBar totalBar = null;
            if (config.isShowProgress()) {
                totalBar = ProgressBarBuilder.builder()
                        .setTaskName(targetFile.getFileName().toString())
                        .setInitialMax(totalSize)
                        .setStyle(ProgressBarStyle.PYTHON_DOWNLOAD)
                        .showSpeed()
                        .startsFrom(resumeOffset, Duration.ZERO)
                        .build();
            }

            int concurrency = config.getConcurrency();
            ExecutorService pool = ThreadUtils.newFixedThreadPool(concurrency);
            var futures = new ArrayList<Future<?>>();

            long chunkSize = totalSize / concurrency;
            for (int i = 0; i < concurrency; i++) {
                long start = resumeOffset + i * chunkSize;
                long end = (i == concurrency - 1) ? totalSize - 1 : start + chunkSize - 1;
                int partIndex = i;
                ProgressBar finalBar = totalBar;
                futures.add(pool.submit(() -> downloadChunk(targetFile, start, end, partIndex, config, finalBar)));
            }

            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();

            mergeParts(targetFile, concurrency);

            if (totalBar != null) {
                totalBar.close();
            }

            log.info("[DownloadService] 并发下载完成: {} ({} bytes, {} threads)",
                    targetFile.getFileName(), Files.size(targetFile), concurrency);
        } catch (ExecutionException e) {
            throw new DownloadException("并发下载失败: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("并发下载被中断", e);
        } catch (IOException e) {
            throw new DownloadException("并发下载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载单个分片。
     *
     * @param targetFile  目标文件路径（用于生成分片临时文件名）
     * @param start       分片起始字节
     * @param end         分片结束字节（含）
     * @param partIndex   分片索引
     * @param config      下载配置
     * @param totalBar    全局进度条（可为 null）
     */
    private void downloadChunk(Path targetFile, long start, long end, int partIndex,
                                DownloadConfig config, ProgressBar totalBar) {
        String partFile = targetFile + ".part" + partIndex;
        try {
            HttpURLConnection conn = openConnection(config);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            applyHeaders(conn, config);
            conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != 206) {
                conn.disconnect();
                throw new IOException("HTTP " + responseCode + " for chunk " + partIndex);
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(partFile)) {
                ThrottledInputStream throttled = new ThrottledInputStream(in, config.getMaxSpeed());
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
        } catch (Exception e) {
            log.error("[DownloadService] 分片 {} 下载失败: {}", partIndex, e.getMessage());
            throw new RuntimeException("Chunk " + partIndex + " failed", e);
        }
    }

    /**
     * 合并所有分片文件为目标文件，并删除分片临时文件。
     *
     * @param targetFile 目标文件路径
     * @param partCount  分片总数
     * @throws IOException 当合并或删除失败时
     */
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

    // ======================== 工具方法 ========================

    /**
     * 打开 HTTP 连接，根据配置决定是否使用代理。
     *
     * @param config 下载配置
     * @return 已建立连接的 HttpURLConnection
     * @throws IOException 当 URL 解析或连接建立失败时
     */
    private HttpURLConnection openConnection(DownloadConfig config) throws IOException {
        URL u = new URL(config.getUrl());
        if (config.getProxy() != null) {
            return (HttpURLConnection) u.openConnection(config.getProxy());
        }
        return (HttpURLConnection) u.openConnection();
    }

    /**
     * 将配置中的自定义请求头应用到 HttpURLConnection。
     *
     * @param conn   目标连接
     * @param config 下载配置
     */
    private void applyHeaders(HttpURLConnection conn, DownloadConfig config) {
        for (java.util.Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 检查服务端是否支持断点续传（通过 HEAD 请求探测 Accept-Ranges 头）。
     *
     * @param config 下载配置
     * @return true 表示服务端支持断点续传
     */
    private boolean checkResumeSupport(DownloadConfig config) {
        try {
            HttpURLConnection conn = openConnection(config);
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            applyHeaders(conn, config);
            conn.connect();
            boolean acceptRange = "bytes".equalsIgnoreCase(conn.getHeaderField("Accept-Ranges"));
            conn.disconnect();
            return acceptRange;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建 DownloadResult，统一封装成功结果。
     *
     * @param file   下载完成的文件路径
     * @param skipped 是否跳过下载（本地已有且校验通过）
     * @param reason 跳过或完成原因
     * @param md5    期望的 MD5 值
     * @return 下载结果
     */
    private static DownloadResult buildResult(Path file, boolean skipped, String reason, String md5) {
        return DownloadResult.builder()
                .success(true)
                .file(file)
                .skipped(skipped)
                .reason(reason)
                .md5(md5)
                .build();
    }

    // ======================== 限速 InputStream ========================

    /**
     * 限速 InputStream — 通过令牌桶算法控制读取速率。
     *
     * <p>每读取指定字节数后，若令牌不足则阻塞等待令牌补充，从而实现限速。
     */
    private static class ThrottledInputStream extends InputStream {
        /**
         * 底层输入流
        */
        private final InputStream delegate;
        /**
         * 每秒可消耗的毫秒级速率（bytesPerSecond / 1000）
         */
        private final long bytesPerMs;
        /**
         * 当前可用令牌数（字节）
        */
        private long tokens;
        /**
         * 上次令牌补充时间（毫秒时间戳）
        */
        private long lastRefill;

        /**
         * 创建限速输入流。
         *
         * @param delegate       底层输入流
         * @param bytesPerSecond 限速字节/秒，0 表示不限速
         */
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

        /**
         * 令牌桶节流：当令牌不足时阻塞等待补充。
         *
         * @param bytes 本次请求读取的字节数
         */
        private void throttle(int bytes) {
            if (bytesPerMs <= 0) {
                return;
            }
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            tokens = Math.min(tokens + elapsed * bytesPerMs, bytesPerMs * 1000);
            lastRefill = now;

            while (tokens < bytes) {
                ThreadUtils.sleep(1);
                now = System.currentTimeMillis();
                elapsed = now - lastRefill;
                tokens = Math.min(tokens + elapsed * bytesPerMs, bytesPerMs * 1000);
                lastRefill = now;
            }
            tokens -= bytes;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
