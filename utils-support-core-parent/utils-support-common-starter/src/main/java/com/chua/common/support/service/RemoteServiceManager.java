package com.chua.common.support.service;

import com.chua.common.support.spi.annotations.Spi;

/**
* SPI 接口：远程服务管理器（SSH 远程执行）。
*
* <p>支持通过 SSH 在远程主机上执行服务的 {@code start / stop / restart / install / uninstall} 操作。</p>
*
* <h3>命名空间</h3>
* <ul>
*   <li>{@code ssh} — SSH 远程管理（默认）</li>
* </ul>
*
* @author CH
* @since 4.0.0.43
 */
@Spi("service-remote")
public interface RemoteServiceManager {

    /**
    * SSH 连接配置。
     */
    record SshConfig(
        String host,
        int port,
        String username,
        String password,
        String privateKeyPath
    ) {
        public SshConfig {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host cannot be blank");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("username cannot be blank");
            }
        }
    }

    /**
    * 建立 SSH 连接。
     */
    void connect(SshConfig config);

    /**
    * 断开 SSH 连接。
     */
    void disconnect();

    /**
    * 判断 SSH 是否已连接。
     */
    boolean isConnected();

    /**
    * 启动远程服务。
    *
    * @return 远程进程 PID
     */
    long startRemote(String serviceName, String jarPath, String startCmd);

    /**
    * 停止远程服务。
     */
    void stopRemote(long pid, String serviceName);

    /**
    * 重启远程服务。
     */
    void restartRemote(long pid, String serviceName, String jarPath, String startCmd);

    /**
    * 查询远程服务运行状态。
     */
    boolean isRemoteRunning(long pid);

    /**
    * 按服务名查询远程服务是否运行（适用于已安装为系统服务的场景）。
    *
    * @param serviceName 服务名
    * @return true 表示正在运行
     */
    default boolean isRemoteServiceRunning(String serviceName) {
        return false;
    }

    /**
    * 将 jar 文件上传到远程主机。
    *
    * @param localPath  本地 jar 路径
    * @param remotePath 远程目标路径（含文件名）
     */
    void uploadJar(String localPath, String remotePath);

    /**
    * 在远程主机上安装服务（创建启动脚本、写入 systemd unit 等）。
     */
    default void installRemote(String serviceName, String remoteJarPath, String startCmd) {
        // 无操作：SSH 模式依赖手动或外部配置
    }

    /**
    * 在远程主机上卸载服务。
     */
    default void uninstallRemote(String serviceName) {
        // 无操作
    }
}
