package com.chua.ssh.support.client;

import com.chua.common.support.network.protocol.ClientSetting;
import lombok.Getter;
import lombok.Setter;

/**
 * Linux 命令执行客户端，兼容 ServerMetricsServiceImpl 调用方式
 *
 * @author CH
 * @since 2026/7/30
 */
@Getter
@Setter
public class LinuxExecClient implements AutoCloseable {

    private final ClientSetting setting;
    private SshClient sshClient;

    public LinuxExecClient(ClientSetting setting) {
        this.setting = setting;
    }

    public void connect() {
        this.sshClient = SshClient.builder()
                .host(setting.getHost())
                .port(setting.getPort())
                .username(setting.getUsername())
                .password(setting.getPassword())
                .connectTimeout(10)
                .sessionTimeout(30)
                .build();
        this.sshClient.connect();
    }

    public SshClient.ExecResult executeCommand(String command, int timeoutMs) {
        if (sshClient == null) {
            throw new IllegalStateException("SSH 客户端未连接，请先调用 connect()");
        }
        try {
            return sshClient.exec()
                    .command(command)
                    .execute();
        } catch (Exception e) {
            throw new SshClient.SshClientException("SSH 命令执行失败: " + command, e);
        }
    }

    public SshClient.ExecResult executeCommand(String command) {
        return executeCommand(command, 30_000);
    }

    public void closeQuietly() {
        try {
            close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() throws Exception {
        if (sshClient != null) {
            sshClient.close();
        }
    }
}
