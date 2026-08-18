package com.chua.ssh.support.server;

import com.chua.common.support.lang.process.ProgressBar;
import com.chua.common.support.lang.process.ProgressBarBuilder;
import com.chua.common.support.lang.process.ProgressBarStyle;

/**
 * SSH 进度条简易工具类，封装 {@link ProgressBar} 自动适配 SSH 输出流。
 * <pre>{@code
 * @ShellMethod("download")
 * public void download(String[] args, SshCommandResponse res) throws Exception {
 *     SshProgress bar = new SshProgress(res, "下载", 100);
 *     for (int i = 0; i <= 100; i++) {
 *         bar.step();
 *         Thread.sleep(60);
 *     }
 *     bar.done("完成");
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SshProgress implements AutoCloseable {

    /**
     * 委托对象
     */
    private final ProgressBar delegate;

    /**
     * 创建 SSH 进度条。
     *
     * @param response  SSH 响应
     * @param taskName  任务名称
     * @param total     总进度值
     */
    public SshProgress(SshCommandResponse response, String taskName, long total) {
        this(response, taskName, total, 80);
    }

    /**
     * 创建 SSH 进度条。
     *
     * @param response       SSH 响应
     * @param taskName       任务名称
     * @param total          总进度值
     * @param maxBarLength   进度条最大字符宽度
     */
    public SshProgress(SshCommandResponse response, String taskName, long total, int maxBarLength) {
        this.delegate = new ProgressBarBuilder()
                .setTaskName(taskName)
                .setInitialMax(total)
                .setConsumer(new SshProgressBarConsumer(response, maxBarLength))
                .continuousUpdate()
                .setStyle(ProgressBarStyle.ASCII)
                .setUpdateIntervalMillis(50)
                .build();
    }

    /**
     * 步进指定数量。
     *
     * @param n 步进数
     * @return this
     */
    public SshProgress stepBy(long n) {
        delegate.stepBy(n);
        return this;
    }

    /**
     * 步进 1。
     *
     * @return this
     */
    public SshProgress step() {
        delegate.step();
        return this;
    }

    /**
     * 跳转到指定进度。
     *
     * @param n 目标进度
     * @return this
     */
    public SshProgress stepTo(long n) {
        delegate.stepTo(n);
        return this;
    }

    /**
     * 设置附加消息。
     *
     * @param msg 附加消息
     * @return this
     */
    public SshProgress extraMessage(String msg) {
        delegate.setExtraMessage(msg);
        return this;
    }

    /**
     * 完成进度条。
     */
    public void done() {
        delegate.close();
    }

    /**
     * 完成进度条并输出完成信息。
     *
     * @param message 完成信息
     */
    public void done(String message) {
        delegate.close();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
