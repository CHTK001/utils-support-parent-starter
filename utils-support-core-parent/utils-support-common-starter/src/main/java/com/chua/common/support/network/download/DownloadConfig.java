package com.chua.common.support.network.download;

import java.net.Proxy;
import java.nio.file.Path;
import java.util.Map;

/**
 * 下载任务配置 — 不可变值对象，传给 {@link DownloadService#execute}。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DownloadConfig {

    private final String url;
    private final Path targetDir;
    private final String filename;
    private final String expectedMd5;
    private final int concurrency;
    private final long maxSpeed;
    private final Proxy proxy;
    private final boolean autoExtract;
    private final Path extractTo;
    private final boolean skipMd5Check;
    private final boolean forceDownload;
    private final boolean showProgress;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final Map<String, String> headers;
    private final long resumeOffset;

    private DownloadConfig(Builder builder) {
        this.url = builder.url;
        this.targetDir = builder.targetDir;
        this.filename = builder.filename;
        this.expectedMd5 = builder.expectedMd5;
        this.concurrency = builder.concurrency;
        this.maxSpeed = builder.maxSpeed;
        this.proxy = builder.proxy;
        this.autoExtract = builder.autoExtract;
        this.extractTo = builder.extractTo;
        this.skipMd5Check = builder.skipMd5Check;
        this.forceDownload = builder.forceDownload;
        this.showProgress = builder.showProgress;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
        this.headers = builder.headers;
        this.resumeOffset = builder.resumeOffset;
    }

    public String getUrl() { return url; }
    public Path getTargetDir() { return targetDir; }
    public String getFilename() { return filename; }
    public String getExpectedMd5() { return expectedMd5; }
    public int getConcurrency() { return concurrency; }
    public long getMaxSpeed() { return maxSpeed; }
    public Proxy getProxy() { return proxy; }
    public boolean isAutoExtract() { return autoExtract; }
    public Path getExtractTo() { return extractTo; }
    public boolean isSkipMd5Check() { return skipMd5Check; }
    public boolean isForceDownload() { return forceDownload; }
    public boolean isShowProgress() { return showProgress; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public Map<String, String> getHeaders() { return headers; }
    public long getResumeOffset() { return resumeOffset; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
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
        private Map<String, String> headers = java.util.Collections.emptyMap();
        private long resumeOffset = 0;

        public Builder url(String url) { this.url = url; return this; }
        public Builder targetDir(Path targetDir) { this.targetDir = targetDir; return this; }
        public Builder filename(String filename) { this.filename = filename; return this; }
        public Builder expectedMd5(String expectedMd5) { this.expectedMd5 = expectedMd5; return this; }
        public Builder concurrency(int concurrency) { this.concurrency = Math.max(1, concurrency); return this; }
        public Builder maxSpeed(long maxSpeed) { this.maxSpeed = maxSpeed; return this; }
        public Builder proxy(Proxy proxy) { this.proxy = proxy; return this; }
        public Builder autoExtract(boolean autoExtract) { this.autoExtract = autoExtract; return this; }
        public Builder extractTo(Path extractTo) { this.extractTo = extractTo; return this; }
        public Builder skipMd5Check(boolean skipMd5Check) { this.skipMd5Check = skipMd5Check; return this; }
        public Builder forceDownload(boolean forceDownload) { this.forceDownload = forceDownload; return this; }
        public Builder showProgress(boolean showProgress) { this.showProgress = showProgress; return this; }
        public Builder connectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; return this; }
        public Builder readTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; return this; }
        public Builder headers(Map<String, String> headers) { this.headers = headers; return this; }
        public Builder resumeOffset(long resumeOffset) { this.resumeOffset = resumeOffset; return this; }

        public DownloadConfig build() { return new DownloadConfig(this); }
    }
}
