package com.chua.common.support.network.download;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * aria2 下载协议实现 — 通过 ProcessBuilder 调用本机 aria2c 完成下载。
 * <p>
 * 映射 {@link Downloader} 的链式配置：目录/文件名/并发分片/限速/代理/请求头/断点续传/MD5 校验/超时。
 * 依赖：本机需安装 aria2（aria2c 可执行文件在 PATH 中）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class Aria2Downloader {

    private Aria2Downloader() {
    }

    /**
     * 执行 aria2c 下载。
     *
     * @param options 下载选项
     * @throws Downloader.DownloadException 下载失败（含 aria2c 未安装）
     */
    public static void download(Aria2Options options) throws Downloader.DownloadException {
        List<String> cmd = buildCommand(options);

        if (log.isDebugEnabled()) {
            log.debug("[Aria2] 命令: {}", cmd);
        }

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (options.isShowProgress()) {
                // 让 aria2c 直接把进度输出到控制台
                pb.inheritIO();
            }
            process = pb.start();
        } catch (IOException e) {
            throw new Downloader.DownloadException(
                    "aria2c 启动失败，请确认已安装 aria2 且 aria2c 在 PATH 中: " + e.getMessage(), e);
        }

        try {
            if (options.isShowProgress()) {
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new Downloader.DownloadException("aria2c 下载失败，退出码: " + exit);
                }
            } else {
                // 静默模式：后台线程排空输出，避免管道缓冲阻塞
                Thread outputThread = new Thread(() -> drainOutput(process), "aria2-output");
                outputThread.setDaemon(true);
                outputThread.start();
                int exit = process.waitFor();
                outputThread.join(5000);
                if (exit != 0) {
                    throw new Downloader.DownloadException("aria2c 下载失败，退出码: " + exit);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new Downloader.DownloadException("aria2c 下载被中断", e);
        }
    }

    /** 构建 aria2c 命令行（参数列表方式，无 shell 注入风险） */
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

    /** 限速格式化为 aria2 可识别的 K/M 后缀 */
    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond % (1024 * 1024) == 0) {
            return (bytesPerSecond / (1024 * 1024)) + "M";
        }
        if (bytesPerSecond % 1024 == 0) {
            return (bytesPerSecond / 1024) + "K";
        }
        return String.valueOf(bytesPerSecond);
    }

    /** Proxy 转为 aria2 代理 URI（HTTP/SOCKS5），其他类型返回 null */
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

    /** 毫秒转秒（向上取整，最小 1） */
    private static long seconds(int ms) {
        return Math.max(1, (ms + 999) / 1000);
    }

    /** 排空子进程输出到日志（静默模式） */
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
     * aria2 下载选项。
     */
    @Builder
    @Data
    public static class Aria2Options {
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
