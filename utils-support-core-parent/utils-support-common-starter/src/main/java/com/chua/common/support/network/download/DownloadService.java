package com.chua.common.support.network.download;

import java.io.IOException;

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
     * <p>该方法是下载服务的核心入口，负责从 URL 下载文件到目标路径，
     * 支持并发分片、断点续传、MD5 校验、限速、代理、自动解压等功能。
     *
     * @param config 下载配置，url 和 targetDir 不能为 null，concurrency 必须 >= 1
     * @return 下载结果，包含成功标志、文件路径、跳过原因（如 md5_match）
     * @throws Downloader.DownloadException 当 URL 为空、HTTP 响应码非 200/206、
     *         MD5 校验失败、aria2c 进程不存在或下载被中断时抛出
     * @throws IOException 当文件系统操作（创建目录、写入文件、删除临时文件）失败时抛出
     */
    DownloadResult execute(DownloadConfig config) throws DownloadException, IOException;
}
