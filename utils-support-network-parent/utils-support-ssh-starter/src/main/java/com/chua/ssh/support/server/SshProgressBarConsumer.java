package com.chua.ssh.support.server;

import com.chua.common.support.lang.process.ProgressBarConsumer;

/**
 * 适配 SSH 终端的进度条消费者，将进度渲染输出写入 {@link SshCommandResponse}。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SshProgressBarConsumer implements ProgressBarConsumer {

    /**
     * 默认 width
     */
    private static final int DEFAULT_WIDTH = 80;

    /**
     * 响应
     */
    private final SshCommandResponse response;
    /**
     * 最大 Rendered 长度
     */
    private final int maxRenderedLength;

    /**
     * 创建 ssh进步barconsumer 实例
     * @param response 响应
     */
    public SshProgressBarConsumer(SshCommandResponse response) {
        this(response, DEFAULT_WIDTH);
    }

    /**
     * 创建 ssh进步barconsumer 实例
     * @param response 响应
     * @param maxRenderedLength int
     * @param maxRenderedLength 最大rendered长度
     */
    public SshProgressBarConsumer(SshCommandResponse response, int maxRenderedLength) {
        this.response = response;
        this.maxRenderedLength = maxRenderedLength;
    }

    @Override
    /** 获取最大值Rendered获取长度 */
    public int getMaxRenderedLength() {
        return maxRenderedLength;
    }

    @Override
    /** Accept */
    public void accept(String rendered) {
        response.writeRaw(rendered.getBytes());
    }

    @Override
    /** 关闭 */
    public void close() {
        response.writeRaw("\n".getBytes());
    }
}
