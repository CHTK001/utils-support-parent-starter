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
     *
     * <p>{@link RemoteServiceManager#connect(SshConfig)} 的入参载体，
     * 全部字段由调用方经 {@code ServiceBuilder} 的远程 DSL 注入后组装，
     * 实现方不应自行提供默认值。规范构造器只对
     * {@link #host} 与 {@link #username} 做必填校验（为 {@code null}
     * 或全空白即抛 {@link IllegalArgumentException}），
     * 其余字段原样透传。</p>
     *
     * <p>凭据字段（{@link #password} / {@link #privateKeyPath}）留有
     * {@code null} 空间，用于在「口令认证」与「私钥认证」之间二选一；
     * 两者都不给时由具体实现决定行为。</p>
     *
     * @param host          远程主机地址，可为 IP 或域名；不允许为 {@code null}，
     *                      也不允许为空白（紧凑构造器直接拒绝）
     * @param port          远程服务监听端口，单位「端口号」，取值 1~65535。
     *                      SSH 实现取默认 22；复用本 record 的 WinRM 实现取 HTTP 端口 5985。
     *                      紧凑构造器只校验取值由调用方负责，未做范围检查
     * @param username      登录用户名，用于认证与远端命令的归属标识；
     *                      不允许为 {@code null}，也不允许为空白（紧凑构造器直接拒绝）
     * @param password      口令认证用的登录口令，由调用方通过配置注入，
     *                      实现方原样交给底层 SSH/WinRM 客户端；
     *                      允许为 {@code null}，此时应改走 {@link #privateKeyPath} 的私钥认证
     * @param privateKeyPath 私钥认证用的私钥文件路径（对发起连接的进程可读）；
     *                      允许为 {@code null} 或空白，空白等价于未提供。
     *                      典型实现只在该值非空时才装配私钥认证器
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
     * @param config 配置，不允许为 null
     */
    void connect(SshConfig config);

    /**
     * 断开 SSH 连接。
     */
    void disconnect();

    /**
     * 判断 SSH 是否已连接。
     * @return 是否成功（true 表示成功）
     */
    boolean isConnected();

    /**
     * 启动远程服务。
     *
     * @return 远程进程 PID
     * @param serviceName 服务名称，不允许为 null
     * @param jarPath jar路径，不允许为 null
     * @param startCmd 启动Cmd，不允许为 null
     */
    long startRemote(String serviceName, String jarPath, String startCmd);

    /**
     * 停止远程服务。
     * @param pid 方法入参 pid
     * @param serviceName 服务名称，不允许为 null
     */
    void stopRemote(long pid, String serviceName);

    /**
     * 重启远程服务。
     * @param pid 方法入参 pid
     * @param serviceName 服务名称，不允许为 null
     * @param jarPath jar路径，不允许为 null
     * @param startCmd 启动Cmd，不允许为 null
     */
    void restartRemote(long pid, String serviceName, String jarPath, String startCmd);

    /**
     * 查询远程服务运行状态。
     * @param pid 方法入参 pid
     * @return 是否成功（true 表示成功）
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
     * @param serviceName 服务名称，不允许为 null
     * @param remoteJarPath remoteJar路径，不允许为 null
     * @param startCmd 启动Cmd，不允许为 null
     */
    default void installRemote(String serviceName, String remoteJarPath, String startCmd) {
        // 无操作：SSH 模式依赖手动或外部配置
    }

    /**
     * 在远程主机上卸载服务。
     * @param serviceName 服务名称，不允许为 null
     */
    default void uninstallRemote(String serviceName) {
        // 无操作
    }
}
