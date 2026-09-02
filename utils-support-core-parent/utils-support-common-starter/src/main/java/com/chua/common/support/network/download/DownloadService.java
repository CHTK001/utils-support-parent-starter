package com.chua.common.support.network.download;

/**
 * 下载服务 SPI 接口。
 *
 * <p>各协议实现（HTTP 直连、aria2c 等）通过 {@code @Spi} 注解注册，
 * 由 {@link com.chua.common.support.spi.ServiceProvider} 按名称选取。
 *
 * <p>常见实现别名：
 * <ul>
 *   <li>{@code "default"} — 内置 HTTP/HTTPS 实现（含单线程、并发分片、断点续传）</li>
 *   <li>{@code "aria2"} — 委托本机 aria2c 进程</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see DefaultDownloadService
 * @see Aria2DownloadService
 */
public interface DownloadService {

    /**
     * 执行下载任务。
     *
     * @param config 下载配置
     * @return 下载结果
     * @throws Downloader.DownloadException 下载失败
     */
    DownloadResult execute(DownloadConfig config) throws Downloader.DownloadException, java.io.IOException;
}
