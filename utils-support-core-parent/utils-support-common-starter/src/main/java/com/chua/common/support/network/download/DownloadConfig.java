package com.chua.common.support.network.download;

import java.net.Proxy;
import java.nio.file.Path;
import java.util.Map;

/**
 * 下载任务配置 — 不可变值对象，传给 {@link DownloadService#execute}。
 *
 * <p>所有字段均不可变，构建完成后状态固定，可安全跨线程传递。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DownloadConfig {

    /** 下载地址 URL（必须非空） */
    private final String url;
    /** 目标下载目录 */
    private final Path targetDir;
    /** 显式指定的文件名（null 时从 URL 路径自动解析） */
    private final String filename;
    /** 期望的 MD5 校验值（null 或空白则跳过校验） */
    private final String expectedMd5;
    /** 并发下载线程数，必须 >= 1 */
    private final int concurrency;
    /** 限速字节/秒（0 = 不限速） */
    private final long maxSpeed;
    /** HTTP 代理（可为 null，表示不使用代理） */
    private final Proxy proxy;
    /** 下载完成后是否自动解压压缩包 */
    private final boolean autoExtract;
    /** 解压目标目录（null 时表示与下载目录相同） */
    private final Path extractTo;
    /**
    * 是否跳过 MD5 校验（优先于 expectedMd5，true 时忽略校验）
    */
    private final boolean skipMd5Check;
    /** 是否强制重新下载（忽略本地缓存文件） */
    private final boolean forceDownload;
    /** 是否在控制台显示下载进度条 */
    private final boolean showProgress;
    /** 连接超时（毫秒） */
    private final int connectTimeoutMs;
    /** 读取超时（毫秒） */
    private final int readTimeoutMs;
    /** 自定义 HTTP 请求头（可为空 map，不影响正常请求） */
    private final Map<String, String> headers;
    /** 断点续传起始偏移字节数（>0 时启用，由框架自动设置） */
    private final long resumeOffset;

    /**
     * 构造方法，创建 Download配置 实例。
     *
     * @param builder 方法入参 builder
     */
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

    /**
     * 下载配置构建器，支持链式调用构建 {@link DownloadConfig} 实例。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static class Builder {

        /** 下载地址，必须通过 {@link #url(String)} 设置 */
        private String url;
        /** 目标下载目录，默认当前工作目录 */
        private Path targetDir;
        /** 显式文件名，为空时从 URL 路径自动解析 */
        private String filename;
        /** 期望的 MD5 校验值，为 null 时跳过校验 */
        private String expectedMd5;
        /** 并发线程数，默认 1 */
        private int concurrency = 1;
        /** 限速（bytes/sec），0 表示不限速 */
        private long maxSpeed = 0;
        /** HTTP 代理，可为 null */
        private Proxy proxy;
        /** 下载完成后自动解压，默认 false */
        private boolean autoExtract = false;
        /** 解压目标目录，为 null 时使用下载目录 */
        private Path extractTo;
        /** 跳过 MD5 校验，默认 false */
        private boolean skipMd5Check = false;
        /** 强制重新下载（忽略本地缓存），默认 false */
        private boolean forceDownload = false;
        /** 显示进度条，默认 true */
        private boolean showProgress = true;
        /** 连接超时（毫秒），默认 15000 */
        private int connectTimeoutMs = 15_000;
        /** 读取超时（毫秒），默认 60000 */
        private int readTimeoutMs = 60_000;
        /** 自定义请求头，默认空 map */
        private Map<String, String> headers = java.util.Collections.emptyMap();
        /** 断点续传偏移字节，默认 0 */
        private long resumeOffset = 0;

        /**
         * 设置下载地址。
         *
         * @param url 合法的 HTTP/HTTPS URL，不能为 null 或空白
         * @return Builder 自身，支持链式调用
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * 设置目标下载目录。
         *
         * @param targetDir 目标目录，为 null 时使用当前工作目录
         * @return Builder 自身
         */
        public Builder targetDir(Path targetDir) {
            this.targetDir = targetDir;
            return this;
        }

        /**
         * 设置显式文件名。
         *
         * @param filename 期望的文件名，为 null 或空白时从 URL 路径自动解析
         * @return Builder 自身
         */
        public Builder filename(String filename) {
            this.filename = filename;
            return this;
        }

        /**
         * 设置期望的 MD5 校验值。
         *
         * @param expectedMd5 小写十六进制 MD5 字符串，为 null 或空白时跳过校验
         * @return Builder 自身
         */
        public Builder expectedMd5(String expectedMd5) {
            this.expectedMd5 = expectedMd5;
            return this;
        }

        /**
         * 设置并发下载线程数。
         *
         * @param concurrency 线程数，必须 >= 1，传入小于 1 的值会自动调整为 1
         * @return Builder 自身
         */
        public Builder concurrency(int concurrency) {
            this.concurrency = Math.max(1, concurrency);
            return this;
        }

        /**
         * 设置下载限速。
         *
         * @param maxSpeed 限速字节/秒，0 表示不限速
         * @return Builder 自身
         */
        public Builder maxSpeed(long maxSpeed) {
            this.maxSpeed = maxSpeed;
            return this;
        }

        /**
         * 设置 HTTP 代理。
         *
         * @param proxy 代理对象，为 null 时不使用代理
         * @return Builder 自身
         */
        public Builder proxy(Proxy proxy) {
            this.proxy = proxy;
            return this;
        }

        /**
         * 设置下载完成后是否自动解压压缩包。
         *
         * @param autoExtract true 表示下载完成后自动解压
         * @return Builder 自身
         */
        public Builder autoExtract(boolean autoExtract) {
            this.autoExtract = autoExtract;
            return this;
        }

        /**
         * 设置解压目标目录。
         *
         * @param extractTo 解压目标目录，为 null 时使用下载目录
         * @return Builder 自身
         */
        public Builder extractTo(Path extractTo) {
            this.extractTo = extractTo;
            return this;
        }

        /**
         * 设置是否跳过 MD5 校验。
         *
         * @param skipMd5Check true 表示跳过校验（即使设置了 expectedMd5）
         * @return Builder 自身
         */
        public Builder skipMd5Check(boolean skipMd5Check) {
            this.skipMd5Check = skipMd5Check;
            return this;
        }

        /**
         * 设置是否强制重新下载。
         *
         * @param forceDownload true 表示忽略本地已有文件强制重新下载
         * @return Builder 自身
         */
        public Builder forceDownload(boolean forceDownload) {
            this.forceDownload = forceDownload;
            return this;
        }

        /**
         * 设置是否在控制台显示下载进度条。
         *
         * @param showProgress true 显示进度条，false 静默下载
         * @return Builder 自身
         */
        public Builder showProgress(boolean showProgress) {
            this.showProgress = showProgress;
            return this;
        }

        /**
         * 设置连接超时。
         *
         * @param connectTimeoutMs 超时毫秒数
         * @return Builder 自身
         */
        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /**
         * 设置读取超时。
         *
         * @param readTimeoutMs 超时毫秒数
         * @return Builder 自身
         */
        public Builder readTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        /**
         * 设置自定义 HTTP 请求头。
         *
         * @param headers 键值对形式的请求头，会覆盖默认头
         * @return Builder 自身
         */
        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /**
         * 设置断点续传起始偏移字节数。
         *
         * <p>通常由框架自动计算，手动设置适用于已知部分下载的场景。
         *
         * @param resumeOffset 已下载的字节数
         * @return Builder 自身
         */
        public Builder resumeOffset(long resumeOffset) {
            this.resumeOffset = resumeOffset;
            return this;
        }

        /**
         * 构建不可变的 {@link DownloadConfig} 实例。
         *
         * @return 新构建的配置对象
         * @throws IllegalStateException 当 url 为 null 时
         */
        public DownloadConfig build() {
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("download url must not be null or blank");
            }
            return new DownloadConfig(this);
        }
    }
}
