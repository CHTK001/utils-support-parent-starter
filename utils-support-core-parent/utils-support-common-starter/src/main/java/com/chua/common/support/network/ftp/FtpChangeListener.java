package com.chua.common.support.network.ftp;

/**
 * FTP 文件变更监听器。
 *
 * <p>通过 {@link FtpServer#addChangeListener(FtpChangeListener)} 注册，
 * 服务端文件/目录发生变更后回调。监听器运行在命令处理线程上，
 * 应快速返回；耗时逻辑请自行异步化。单个监听器抛出的异常会被服务端
 * 捕获记录，不影响其他监听器与命令处理。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@FunctionalInterface
public interface FtpChangeListener {

    /**
     * 文件变更回调。
     *
     * @param event 变更事件
     */
    void onChange(FtpChangeEvent event);
}
