// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.ssh.support.client;

import com.chua.common.support.network.protocol.ClientSetting;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Linux 命令执行客户端，兼容 服务端指标服务impl 调用方式
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Slf4j
@Setter
public class LinuxExecClient implements AutoCloseable {

    /**
     * 配置对象
     */
    private final ClientSetting setting;
    /**
     * ssh 客户端
     */
    private SshClient sshClient;

    /**
     * 创建 Linux执行客户端 实例
     * @param setting setting
     */
    public LinuxExecClient(ClientSetting setting) {
        this.setting = setting;
    }

    /**
     * 连接
    */
    public void connect() {
        this.sshClient = SshClient.builder()
                .host(setting.getHost())
                .port(setting.getPort())
                .username(setting.getUsername())
                .password(setting.getPassword())
                .connectTimeout(toSeconds(setting.getConnectTimeout()))
                .sessionTimeout(toSeconds(setting.getReadTimeout()))
                .execTimeout(toSeconds(setting.getReadTimeout()))
                .build();
        this.sshClient.connect();
    }

    /**
     * 执行命令
     *
     * @param command 命令
     * @param timeoutMs 超时ms
     * @return 执行命令的结果
     */
    public SshClient.ExecResult executeCommand(String command, int timeoutMs) {
        return executeCommand(command, (long) timeoutMs);
    }

    /**
     * 执行命令
     *
     * @param command 命令
     * @param timeoutMs 超时毫秒
     * @return 执行命令的结果
     */
    public SshClient.ExecResult executeCommand(String command, long timeoutMs) {
        if (sshClient == null) {
            throw new IllegalStateException("SSH 客户端未连接，请先调用 connect()");
        }
        try {
            SshClient.ExecOperation operation = sshClient.exec().command(command);
            if (timeoutMs > 0) {
                operation.timeout(timeoutMs);
            }
            return operation.execute();
        } catch (Exception e) {
            throw new SshClient.SshClientException("SSH 命令执行失败: " + command, e);
        }
    }

    /**
     * 将通用 ClientSetting 的毫秒超时转换为 SshClient 使用的秒超时，向上取整避免缩短配置。
     *
     * @param timeoutMillis 毫秒
     * @return 秒
     */
    private static int toSeconds(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            return 1;
        }
        long seconds = ((timeoutMillis - 1L) / 1000L) + 1L;
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    /**
     * 执行命令
     *
     * @param command 命令
     * @return 执行命令的结果
     */
    public SshClient.ExecResult executeCommand(String command) {
        return executeCommand(command, setting.getReadTimeout());
    }

    /**
     * 关闭Quietly
    */
    public void closeQuietly() {
        try {
            close();
        } catch (Exception e) {
            log.debug("[ssh] 关闭客户端失败，已忽略: {}", e.getMessage());
        }
    }

    /**
     * 关闭
    */
    @Override
    public void close() throws Exception {
        if (sshClient != null) {
            sshClient.close();
        }
    }
}
