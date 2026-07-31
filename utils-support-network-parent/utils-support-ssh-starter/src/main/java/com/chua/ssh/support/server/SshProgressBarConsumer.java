package com.chua.ssh.support.server;

import com.chua.common.support.lang.process.ProgressBarConsumer;

/**
 * 适配 SSH 终端的进度条消费者，将进度渲染输出写入 {@link SshCommandResponse}。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SshProgressBarConsumer implements ProgressBarConsumer {

    private static final int DEFAULT_WIDTH = 80;

    private final SshCommandResponse response;
    private final int maxRenderedLength;

    public SshProgressBarConsumer(SshCommandResponse response) {
        this(response, DEFAULT_WIDTH);
    }

    public SshProgressBarConsumer(SshCommandResponse response, int maxRenderedLength) {
        this.response = response;
        this.maxRenderedLength = maxRenderedLength;
    }

    @Override
    public int getMaxRenderedLength() {
        return maxRenderedLength;
    }

    @Override
    public void accept(String rendered) {
        response.writeRaw(rendered.getBytes());
    }

    @Override
    public void close() {
        response.writeRaw("\n".getBytes());
    }
}
