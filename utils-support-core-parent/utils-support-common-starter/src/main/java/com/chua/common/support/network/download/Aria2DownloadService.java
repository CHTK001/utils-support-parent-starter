package com.chua.common.support.network.download;

import com.chua.common.support.spi.annotations.Spi;
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
        String filename = DownloadUtils.resolveFilename(config.getUrl(), config.getFilename());
        Path targetDir = config.getTargetDir() != null ? config.getTargetDir() : Path.of(".");
        Path targetFile = targetDir.resolve(filename);

        // forceDownload 时清除已有文件
        if (config.isForceDownload() && Files.isRegularFile(targetFile)) {
            try {
                Files.deleteIfExists(targetFile);
            } catch (IOException e) {
                log.warn("[Aria2DownloadService] 删除已有文件失败: {}", targetFile, e);
            }
        }

        // 检查断点续传
        long resumeOffset = 0;
        if (!config.isForceDownload() && Files.isRegularFile(targetFile)) {
            resumeOffset = Files.size(targetFile);
        }

        Files.createDirectories(targetDir);

        var options = Aria2Options.builder()
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

    /**
    * 启动 aria2c 进程执行下载。
    *
    * @param options 下载选项
    * @throws DownloadException 当 aria2c 不可用、进程退出码非 0 或被中断时
     */
    private static void download(Aria2Options options) throws DownloadException {
        List<String> cmd = buildCommand(options);

        if (log.isDebugEnabled()) {
            log.debug("[Aria2] 命令: {}", cmd);
        }

        Process process = null;
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

        // 捕获 process 为 effectively final，供 lambda 使用
        final Process finalProcess = process;

        try {
            if (options.isShowProgress()) {
                int exit = finalProcess.waitFor();
                if (exit != 0) {
                    throw new DownloadException("aria2c 下载失败，退出码: " + exit);
                }
            } else {
                Thread outputThread = new Thread(() -> drainOutput(finalProcess), "aria2-output");
                outputThread.setDaemon(true);
                outputThread.start();
                int exit = finalProcess.waitFor();
                outputThread.join(5000);
                if (exit != 0) {
                    throw new DownloadException("aria2c 下载失败，退出码: " + exit);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (finalProcess != null) {
                finalProcess.destroyForcibly();
            }
            throw new DownloadException("aria2c 下载被中断", e);
        } finally {
            if (finalProcess != null && finalProcess.isAlive()) {
                finalProcess.destroyForcibly();
            }
        }
    }

    /**
    * 构建 aria2c 命令行参数列表（无 shell 注入风险）。
    *
    * @param o 下载选项
    * @return aria2c 命令行参数列表
     */
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
            if (proxy != null) {
                cmd.add("--all-proxy=" + proxy);
            }
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

    /**
    * 将字节/秒格式化为 aria2c 可识别的 K/M 后缀。
    *
    * @param bytesPerSecond 字节每秒
    * @return 格式化后的字符串，如 "1M"、"500K"
     */
    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond % (1024 * 1024) == 0) {
            return (bytesPerSecond / (1024 * 1024)) + "M";
        }
        if (bytesPerSecond % 1024 == 0) {
            return (bytesPerSecond / 1024) + "K";
        }
        return String.valueOf(bytesPerSecond);
    }

    /**
    * 将 Java Proxy 转换为 aria2c 代理 URI。
    *
    * @param proxy Java Proxy 对象
    * @return 代理 URI 字符串（如 http://host:port），不支持的类型返回 null
     */
    private static String proxyUri(Proxy proxy) {
        if (proxy.address() == null || !(proxy.address() instanceof InetSocketAddress)) {
            return null;
        }
        InetSocketAddress addr = (InetSocketAddress) proxy.address();
        switch (proxy.type()) {
            case HTTP:
                return "http://" + addr.getHostString() + ":" + addr.getPort();
            case SOCKS:
                return "socks5://" + addr.getHostString() + ":" + addr.getPort();
            default:
                return null;
        }
    }

    /**
    * 毫秒向上取整为秒（最小值为 1）。
    *
    * @param ms 毫秒数
    * @return 秒数
     */
    private static long seconds(int ms) {
        return Math.max(1, (ms + 999) / 1000);
    }

    /**
    * 后台线程排空子进程输出到日志（静默模式）。
    *
    * @param process 子进程
     */
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

    /**
    * aria2c 下载内部选项。
     */
    @lombok.Builder
    @lombok.Data
    private static class Aria2Options {
        /** 下载地址 */
        private String url;
        /** 目标文件（含目录与文件名） */
        private Path targetFile;
        /** 并发连接数 */
        private int concurrency;
        /** 限速（bytes/sec，0 = 不限） */
        private long maxSpeed;
        /** 代理 */
        private Proxy proxy;
        /** 自定义请求头 */
        private Map<String, String> headers;
        /** 期望 MD5（null 则跳过 aria2 校验） */
        private String expectedMd5;
        /** 断点续传偏移（>0 时启用 --continue） */
        private long resumeOffset;
        /** 强制重新下载 */
        private boolean forceDownload;
        /** 是否显示进度 */
        private boolean showProgress;
        /** 连接超时（毫秒） */
        private int connectTimeoutMs;
        /** 读取超时（毫秒） */
        private int readTimeoutMs;
    }
}
