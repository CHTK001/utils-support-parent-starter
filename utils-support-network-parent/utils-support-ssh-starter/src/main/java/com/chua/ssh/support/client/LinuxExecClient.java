package com.chua.ssh.support.client;

import com.chua.common.support.network.protocol.ClientSetting;
import lombok.Getter;
import lombok.Setter;

/**
 * Linux 命令执行客户端，兼容 ServerMetricsServiceImpl 调用方式
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
public class LinuxExecClient implements AutoCloseable {

    /**
     * 配置对象
     */
    private final ClientSetting setting;
    /**
     * ssh Client
     */
    private SshClient sshClient;

    /**
     * 创建 LinuxExecClient 实例
     * @param setting setting
     */
    public LinuxExecClient(ClientSetting setting) {
        this.setting = setting;
    }

    /** 连接 */
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

    /** 执行Command */
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

    /** 执行Command */
    public SshClient.ExecResult executeCommand(String command) {
        return executeCommand(command, 30_000);
    }

    /** 关闭Quietly */
    public void closeQuietly() {
        try {
            close();
        } catch (Exception ignored) {
        }
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        if (sshClient != null) {
            sshClient.close();
        }
    }
}
