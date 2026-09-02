package com.chua.common.support.network.download;

import com.chua.common.support.spi.annotations.Spi;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * aria2c 下载实现。
 *
 * <p>通过 {@code ProcessBuilder} 调用本机 aria2c 完成下载，
 * 支持并发分片、限速、代理、请求头、断点续传、MD5 校验。
 *
 * <p>依赖：本机需安装 aria2（aria2c 可执行文件在 PATH 中）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("aria2")
public class Aria2DownloadService implements DownloadService {

    @Override
    public DownloadResult execute(DownloadConfig config) throws DownloadException, IOException {
        String filename = resolveFilename(config.getUrl(), config.getFilename());
        Path targetDir = config.getTargetDir() != null ? config.getTargetDir() : Path.of(".");
        Path targetFile = targetDir.resolve(filename);

        // forceDownload 时清除已有文件
        if (config.isForceDownload() && Files.isRegularFile(targetFile)) {
            try { Files.deleteIfExists(targetFile); } catch (IOException e) {
                log.warn("[Aria2DownloadService] 删除已有文件失败: {}", targetFile, e);
            }
        }

        // 检查断点续传
        long resumeOffset = 0;
        if (!config.isForceDownload() && Files.isRegularFile(targetFile)) {
            resumeOffset = Files.size(targetFile);
        }

        Files.createDirectories(targetDir);

        Aria2Options options = Aria2Options.builder()
                .url(config.getUrl())
                .targetFile(targetFile)
                .concurrency(config.getConcurrency())
                .maxSpeed(config.getMaxSpeed())
                .proxy(config.getProxy())
                .headers(config.getHeaders())
                .expectedMd5(!config.isSkipMd5Check() ? config.getExpectedMd5() : null)
                .resumeOffset(resumeOffset)
                .forceDownload(config.isForceDownload())
                .showProgress(config.isShowProgress())
                .connectTimeoutMs(config.getConnectTimeoutMs())
                .readTimeoutMs(config.getReadTimeoutMs())
                .build();

        download(options);

        long fileSize = Files.size(targetFile);
        log.info("[Aria2DownloadService] 下载完成: {} ({} bytes)", targetFile.getFileName(), fileSize);
        return DownloadResult.builder()
                .success(true)
                .file(targetFile)
                .skipped(false)
                .reason("downloaded")
                .md5(config.getExpectedMd5())
                .build();
    }

    private static void download(Aria2Options options) throws DownloadException {
        List<String> cmd = buildCommand(options);

        if (log.isDebugEnabled()) {
            log.debug("[Aria2] 命令: {}", cmd);
        }

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (options.isShowProgress()) {
                pb.inheritIO();
            }
            process = pb.start();
        } catch (IOException e) {
            throw new DownloadException(
                    "aria2c 启动失败，请确认已安装 aria2 且 aria2c 在 PATH 中: " + e.getMessage(), e);
        }

        try {
            if (options.isShowProgress()) {
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new DownloadException("aria2c 下载失败，退出码: " + exit);
                }
            } else {
                Thread outputThread = new Thread(() -> drainOutput(process), "aria2-output");
                outputThread.setDaemon(true);
                outputThread.start();
                int exit = process.waitFor();
                outputThread.join(5000);
                if (exit != 0) {
                    throw new DownloadException("aria2c 下载失败，退出码: " + exit);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new DownloadException("aria2c 下载被中断", e);
        }
    }

    private static List<String> buildCommand(Aria2Options o) {
        List<String> cmd = new ArrayList<>();
        cmd.add("aria2c");
        cmd.add("--no-conf=true");
        cmd.add("--file-allocation=none");
        cmd.add("--auto-file-renaming=false");

        Path target = o.getTargetFile();
        Path dir = target.getParent() != null ? target.getParent() : Path.of(".");
        String name = target.getFileName() != null ? target.getFileName().toString() : "download";
        cmd.add("-d");
        cmd.add(dir.toString());
        cmd.add("-o");
        cmd.add(name);

        int conn = Math.max(1, o.getConcurrency());
        cmd.add("-x");
        cmd.add(String.valueOf(conn));
        cmd.add("-s");
        cmd.add(String.valueOf(conn));

        if (o.getMaxSpeed() > 0) {
            cmd.add("--max-download-limit=" + formatSpeed(o.getMaxSpeed()));
        }
        if (o.getProxy() != null) {
            String proxy = proxyUri(o.getProxy());
            if (proxy != null) cmd.add("--all-proxy=" + proxy);
        }
        if (o.getHeaders() != null) {
            for (Map.Entry<String, String> entry : o.getHeaders().entrySet()) {
                cmd.add("--header=" + entry.getKey() + ": " + entry.getValue());
            }
        }
        if (o.getExpectedMd5() != null && !o.getExpectedMd5().isBlank()) {
            cmd.add("--checksum=md5=" + o.getExpectedMd5().toLowerCase());
        }
        if (o.getResumeOffset() > 0) {
            cmd.add("--continue=true");
        }
        cmd.add("--connect-timeout=" + seconds(o.getConnectTimeoutMs()));
        cmd.add("--timeout=" + seconds(o.getReadTimeoutMs()));
        cmd.add("--summary-interval=" + (o.isShowProgress() ? 1 : 0));
        if (!o.isShowProgress()) {
            cmd.add("--quiet=true");
        }
        cmd.add(o.getUrl());
        return cmd;
    }

    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond % (1024 * 1024) == 0) return (bytesPerSecond / (1024 * 1024)) + "M";
        if (bytesPerSecond % 1024 == 0) return (bytesPerSecond / 1024) + "K";
        return String.valueOf(bytesPerSecond);
    }

    private static String proxyUri(Proxy proxy) {
        if (proxy.address() == null || !(proxy.address() instanceof InetSocketAddress)) return null;
        InetSocketAddress addr = (InetSocketAddress) proxy.address();
        switch (proxy.type()) {
            case HTTP:  return "http://" + addr.getHostString() + ":" + addr.getPort();
            case SOCKS: return "socks5://" + addr.getHostString() + ":" + addr.getPort();
            default:    return null;
        }
    }

    private static long seconds(int ms) { return Math.max(1, (ms + 999) / 1000); }

    private static void drainOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[Aria2] {}", line);
            }
        } catch (IOException e) {
            log.debug("[Aria2] 读取输出结束: {}", e.getMessage());
        }
    }

    private static String resolveFilename(String url, String explicitName) {
        if (explicitName != null && !explicitName.isBlank()) return explicitName;
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return java.net.URLDecoder.decode(path.substring(lastSlash + 1), StandardCharsets.UTF_8);
        }
        return "download";
    }

    @lombok.Builder
    @lombok.Data
    static class Aria2Options {
        private String url;
        private Path targetFile;
        private int concurrency;
        private long maxSpeed;
        private Proxy proxy;
        private Map<String, String> headers;
        private String expectedMd5;
        private long resumeOffset;
        private boolean forceDownload;
        private boolean showProgress;
        private int connectTimeoutMs;
        private int readTimeoutMs;
    }
}
