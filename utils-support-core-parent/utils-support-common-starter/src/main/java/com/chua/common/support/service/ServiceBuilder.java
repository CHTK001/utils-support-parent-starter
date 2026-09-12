package com.chua.common.support.service;

import com.chua.common.support.service.impl.LocalServiceManager;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.util.Map;

/**
 * 服务部署链式构建器（Facade）。
 *
 * <p>统一入口，按以下步骤完成服务配置与部署：</p>
 * <pre>{@code
 * // 本地部署
 * ServiceBuilder.local()
 *     .withJar("app.jar")
 *     .withStartCmd("java -jar app.jar")
 *     .withStopCmd("kill %PID%")
 *     .withServiceName("my-app")
 *     .toLocation()
 *     .start();
 *
 * // 远程部署
 * ServiceBuilder.remote()
 *     .withJar("app.jar")
 *     .withStartCmd("java -jar /opt/app/app.jar")
 *     .withServiceName("my-app")
 *     .toRemote("192.168.1.10", 22, "root", "pass")
 *     .install()
 *     .start();
 * }</pre>mote("192.168.1.10", 22, "root", "pass")
 *     .install()
 *     .start();
 * }</pre>
 *
 * <h3>SPI 实现映射</h3>
 * <ul>
 *   <li>{@code service-process} → {@link LocalServiceManager}（本地进程管理）</li>
 *   <li>{@code service-remote}  → {@link com.chua.common.support.service.impl.SshServiceManager}（SSH 远程管理）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class ServiceBuilder implements Closeable {

    /**
     * SPI 名称：本地服务管理器。
     */
    public static final String SPI_SERVICE = "service-process";

    /**
     * SPI 名称：远程服务管理器。
     */
    public static final String SPI_REMOTE = "service-remote";

    /**
     * 默认远程协议：ssh。
     */
    public static final String DEFAULT_PROTOCOL = "ssh";

    // ---------- 公共属性 ----------

    /** jar 文件路径 */
    private String jarPath;
    /** 启动命令（支持 {jar} 占位符） */
    private String startCmd;
    /** 停止命令（支持 {pid} 占位符） */
    private String stopCmd;
    /** 服务名称 */
    private String serviceName;
    /** PID 文件路径（空 则使用默认） */
    private String pidFile;
    /** 工作目录 */
    private String workingDir;
    /** 环境变量 */
    private Map<String, String> env;

    /**
     * 创建本地服务构建器（快捷工厂方法）。
     * @return 本地的结果
     */
    public static LocalDsl local() {
        return new LocalDsl();
    }

    /**
     * 创建远程服务构建器（快捷工厂方法）。
     * @return 远程的结果
     */
    public static RemoteDsl remote() {
        return new RemoteDsl();
    }

    // ---------- 配置方法 ----------

    /**
     * 设置 jar 路径。
     * @param jarPath jar路径
     * @return withJar的结果
     */
    public ServiceBuilder withJar(String jarPath) {
        this.jarPath = jarPath;
        return this;
    }

    /**
      * 设置启动命令（支持 {jar} 占位符，自动替换为 jar路径）。
     * @param startCmd 启动CMD
     * @return with启动cmd的结果
     */
    public ServiceBuilder withStartCmd(String startCmd) {
        this.startCmd = startCmd;
        return this;
    }

    /**
     * 设置停止命令（支持 {pid} 占位符）。
     * @param stopCmd 停止CMD
     * @return with停止cmd的结果
     */
    public ServiceBuilder withStopCmd(String stopCmd) {
        this.stopCmd = stopCmd;
        return this;
    }

    /**
     * 设置服务名称。
     * @param serviceName 服务名称
     * @return with服务名称的结果
     */
    public ServiceBuilder withServiceName(String serviceName) {
        this.serviceName = serviceName;
        return this;
    }

    /**
     * 设置 PID 文件路径（可选）。
     * @param pidFile pid文件
     * @return withpid文件的结果
     */
    public ServiceBuilder withPidFile(String pidFile) {
        this.pidFile = pidFile;
        return this;
    }

    /**
     * 设置工作目录（可选）。
     * @param workingDir workingdir
     * @return withWorkingDir的结果
     */
    public ServiceBuilder withWorkingDir(String workingDir) {
        this.workingDir = workingDir;
        return this;
    }

    /**
     * 设置环境变量（可选）。
     * @param env env
     * @return withEnv的结果
     */
    public ServiceBuilder withEnv(Map<String, String> env) {
        this.env = env;
        return this;
    }

    // ---------- 分支入口 ----------

    /**
     * 切换到本地部署模式。
     * @return 转为位置的结果
     */
    public LocationManager toLocation() {
        return new LocationManager(this);
    }

    /**
     * 切换到远程部署模式。
     * @return 转为远程的结果
     */
    public RemoteManager toRemote() {
        return new RemoteManager(this);
    }

    /**
     * 切换到远程部署模式（指定协议 ssh/winrm）。
     *
     * @param protocol 远程协议（ssh / winrm）
     * @return 转为远程的结果
     */
    public RemoteManager toRemote(String protocol) {
        return new RemoteManager(this, protocol);
    }

    /**
     * 切换到远程部署模式（便捷重载，传入 SSH 连接参数）。
     * @param host 主机
     * @param port 端口
     * @param username 用户名
     * @param password 密码
     * @return 转为远程的结果
     */
    public RemoteManager toRemote(String host, int port, String username, String password) {
        RemoteManager mgr = new RemoteManager(this);
        mgr.withHost(host).withPort(port).withUsername(username).withPassword(password);
        return mgr;
    }

    /**
     * 切换到远程部署模式（指定协议与连接参数）。
     *
     * @param protocol 远程协议（ssh / winrm）
     * @param host     远程主机
     * @param port     远程端口
     * @param username 用户名
     * @param password 密码
     * @return 转为远程的结果
     */
    public RemoteManager toRemote(String protocol, String host, int port, String username, String password) {
        RemoteManager mgr = new RemoteManager(this, protocol);
        mgr.withHost(host).withPort(port).withUsername(username).withPassword(password);
        return mgr;
    }

    /**
     * 切换到远程部署模式（使用 SSH 私钥认证）。
     * @param host 主机
     * @param port 端口
     * @param username 用户名
     * @param privateKeyPath 私募键路径
     * @return 转为远程键的结果
     */
    public RemoteManager toRemoteKey(String host, int port, String username, String privateKeyPath) {
        RemoteManager mgr = new RemoteManager(this);
        mgr.withHost(host).withPort(port).withUsername(username).withPrivateKey(privateKeyPath);
        return mgr;
    }

    // ---------- DSL：本地管理器 ----------

    /**
     * 本地服务管理器 DSL。
     * @author CH
     * @since 4.0.0
     * @param host 主机
     * @param port 端口
     * @return with远程的结果
     */
    public static class LocationManager {

        private final ServiceBuilder builder; // 构建器
        private final ServiceManager manager; // 管理器

        LocationManager(ServiceBuilder builder) {
            this.builder = builder;
            this.manager = ServiceProvider.of(ServiceManager.class)
                    .getExtension(SPI_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("[service] 未找到 ServiceManager 实现，请确保 classpath 包含相关依赖");
            /**
             * with服务。
             * @param serviceName 服务名称
             * @return with服务的结果
             */
            }
        }

        public ServiceBuilder withService(String serviceName) {
            builder.withServiceName(serviceName);
            /**
             * with位置。
             * @param jarPath jar路径
             * @return with位置的结果
             * @param host 主机
             * @param port 端口
             */
            return builder;
        /**
         * with位置。
         * @param jarPath jar路径
         * @return with位置的结果
         */
        }

        public ServiceBuilder withLocation(String jarPath) {
            builder.withJar(jarPath);
            return builder;
        }

        public ServiceBuilder withRemote(String host, int port) {
            // 本地模式忽略远程参数，供链式兼容
            return builder;
        }

        /**
         * 启动服务。
         * @return 启动的结果
         */
        public long start() {
            resolveAndValidate();
            String cmd = buildStartCmd();
            long pid = manager.start(builder.jarPath, cmd, builder.pidFile);
            log.info("[service] 本地启动成功: name={} pid={}", builder.serviceName, pid);
            return pid;
        }

        /**
         * 停止服务。
         */
        public void stop() {
            long pid = builder.jarPath != null && !builder.jarPath.isBlank()
                    ? manager.start(builder.jarPath, buildStartCmd(), builder.pidFile) : -1;
            manager.stop(pid, builder.serviceName);
            log.info("[service] 本地停止完成: name={}", builder.serviceName);
        }

        /**
         * 重启服务。
         */
        public void restart() {
            resolveAndValidate();
            String cmd = buildStartCmd();
            long pid = builder.pidFile != null
                    ? manager.start(builder.jarPath, cmd) : -1;
            manager.restart(pid, builder.serviceName, builder.jarPath, cmd);
            log.info("[service] 本地重启完成: name={}", builder.serviceName);
        }

        /**
         * 查询服务状态。
         * @return 状态的结果
         */
        public boolean status() {
            if (builder.jarPath == null || builder.jarPath.isBlank()) {
                long pid = manager.findPidByName(builder.serviceName);
                return manager.isRunning(pid);
            }
            resolveAndValidate();
            long pid = builder.pidFile != null
                    ? manager.start(builder.jarPath, buildStartCmd()) : -1;
            return manager.isRunning(pid);
        }

        /**
         * 安装服务（生成启动脚本等）。
         */
        public void install() {
            resolveAndValidate();
            manager.install(builder.serviceName, builder.jarPath, buildStartCmd());
            log.info("[service] 本地安装完成: name={}", builder.serviceName);
        }

        /**
         * 卸载服务。
         * @return 构建启动cmd的结果
         /**
          * uninstall。
          */
          * @return 构建启动cmd的结果
         /**
          * uninstall。
          */
         */
        public void uninstall() {
            resolveAndValidate();
            manager.uninstall(builder.serviceName);
            log.info("[service] 本地卸载完成: name={}", builder.serviceName);
        }

        private void resolveAndValidate() {
            if (builder.serviceName == null || builder.serviceName.isBlank()) {
                throw new IllegalStateException("[service] withServiceName() 未设置");
            }
            if (builder.jarPath == null || builder.jarPath.isBlank()) {
                throw new IllegalStateException("[service] withJar() 未设置");
            }
        }

        private String buildStartCmd() {
            String cmd = builder.startCmd != null ? builder.startCmd
                    : "java -jar \"" + builder.jarPath + "\"";
            if (builder.workingDir != null) {
                cmd = "cd \"" + builder.workingDir + "\" && " + cmd;
            }
            return cmd;
        }
    }

    // ---------- DSL：远程管理器 ----------

    /**
     * 远程服务管理器 DSL。
     * @author CH
     * @since 4.0.0
     */
    public static class RemoteManager {

        private final ServiceBuilder builder; // 构建器
        private final RemoteServiceManager manager; // 管理器

        // 远程连接参数
        private String sshHost;
        private int sshPort = 22; // ssh端口
        private String sshUsername; // ssh用户名
        private String sshPassword; // ssh密码
        private String sshPrivateKey; // ssh私募键

        RemoteManager(ServiceBuilder builder) {
            this(builder, DEFAULT_PROTOCOL);
        }

        /**
         * 使用指定协议（ssh/winrm）加载远程服务管理器。
         *
         * @param builder  宿主构建器
         * @param protocol SPI 名称（ssh 或 winrm）
         * @param host 主机
         * @param port 端口
         * @return with远程的结果
         */
        RemoteManager(ServiceBuilder builder, String protocol) {
            this.builder = builder;
            this.manager = ServiceProvider.of(RemoteServiceManager.class)
                    .getExtension(protocol);
            if (manager == null) {
                throw new IllegalStateException("[service] 未找到 RemoteServiceManager 实现: protocol="
                        + protocol + "，请确保 classpath 包含 ssh-starter 或 winrm-starter 依赖");
            /**
             * with主机。
             * @param host 主机
             * @return with主机的结果
             */
            }
        }

        public RemoteManager withHost(String host) {
            this.sshHost = host;
            /**
             * with端口。
             * @param port 端口
             * @return with端口的结果
             */
            return this;
        }

        public RemoteManager withPort(int port) {
            this.sshPort = port;
            /**
             * with用户名。
             * @param username 用户名
             * @return with用户名的结果
             */
            return this;
        }

        public RemoteManager withUsername(String username) {
            this.sshUsername = username;
            /**
             * with密码。
             * @param password 密码
             * @return with密码的结果
             */
            return this;
        }

        public RemoteManager withPassword(String password) {
            this.sshPassword = password;
            /**
             * with私募键。
             * @param privateKeyPath 私募键路径
             * @return with私募键的结果
             */
            return this;
        }

        public RemoteManager withPrivateKey(String privateKeyPath) {
            this.sshPrivateKey = privateKeyPath;
            /**
             * with服务。
             * @param serviceName 服务名称
             * @return with服务的结果
             */
            return this;
        }

        public ServiceBuilder withService(String serviceName) {
            builder.withServiceName(serviceName);
            /**
             * with位置。
             * @param jarPath jar路径
             * @return with位置的结果
             * @param host 主机
             * @param port 端口
             */
            return builder;
        /**
         * with位置。
         * @param jarPath jar路径
         * @return with位置的结果
         */
        }

        public ServiceBuilder withLocation(String jarPath) {
            builder.withJar(jarPath);
            return builder;
        }

        public ServiceBuilder withRemote(String host, int port) {
            this.sshHost = host;
            this.sshPort = port;
            return builder;
        }

        /**
          * 设置服务名称（委托给外部 构建器）。
         * @param serviceName 服务名称
         * @return with服务名称的结果
         */
        public RemoteManager withServiceName(String serviceName) {
            builder.withServiceName(serviceName);
            return this;
        }

        /**
          * 设置 jar 路径（委托给外部 构建器）。
         * @param jarPath jar路径
         * @return withJar的结果
         */
        public RemoteManager withJar(String jarPath) {
            builder.withJar(jarPath);
            return this;
        }

        /**
          * 设置启动命令（覆盖默认 Java -jar 命令）。
         * @param cmd CMD
         * @return with启动cmd的结果
         */
        public RemoteManager withStartCmd(String cmd) {
            builder.withStartCmd(cmd);
            return this;
        }

        /**
         * 建立 SSH 连接。
         * @return 连接的结果
         */
        public RemoteManager connect() {
            resolveAndValidate();
            RemoteServiceManager.SshConfig cfg = new RemoteServiceManager.SshConfig(
                    sshHost, sshPort, sshUsername, sshPassword, sshPrivateKey);
            manager.connect(cfg);
            return this;
        }

        /**
         * 断开 SSH 连接。
         */
        public void disconnect() {
            manager.disconnect();
        }

        /**
         * 启动远程服务（先连接，后启动）。
         * @return 启动的结果
         */
        public long start() {
            if (!manager.isConnected()) {
                connect();
            }
            resolveAndValidate();
            String cmd = buildRemoteStartCmd();
            long pid = manager.startRemote(builder.serviceName, builder.jarPath, cmd);
            log.info("[service-remote] 远程启动成功: name={} pid={}", builder.serviceName, pid);
            return pid;
        }

        /**
         * 停止远程服务。
         */
        public void stop() {
            if (!manager.isConnected()) {
                return;
            }
            resolveAndValidate();
            manager.stopRemote(-1, builder.serviceName);
            log.info("[service-remote] 远程停止完成: name={}", builder.serviceName);
        }

        /**
         * 重启远程服务。
         */
        public void restart() {
            if (!manager.isConnected()) {
                connect();
            }
            resolveAndValidate();
            String cmd = buildRemoteStartCmd();
            manager.restartRemote(-1, builder.serviceName, builder.jarPath, cmd);
            log.info("[service-remote] 远程重启完成: name={}", builder.serviceName);
        }

        /**
         * 查询远程服务状态。
         * @return 状态的结果
         */
        public boolean status() {
            if (!manager.isConnected()) {
                return false;
            }
            return manager.isRemoteServiceRunning(builder.serviceName);
        }

        /**
         * 上传 jar 并安装远程服务（生成 systemd unit）。
         */
        public void install() {
            if (!manager.isConnected()) {
                connect();
            }
            resolveAndValidate();
            String remotePath = "/opt/" + builder.serviceName + "/" + builder.serviceName + ".jar";
            manager.uploadJar(builder.jarPath, remotePath);
            String cmd = buildRemoteStartCmd().replace(builder.jarPath, remotePath);
            manager.installRemote(builder.serviceName, remotePath, cmd);
            log.info("[service-remote] 远程安装完成: name={} jar={}", builder.serviceName, remotePath);
        }

        /**
         * 卸载远程服务（删除 systemd unit、停止进程）。
         * @return 构建远程启动cmd的结果
         /**
          * uninstall。
          */
          * @return 构建远程启动cmd的结果
         /**
          * uninstall。
          */
         */
        public void uninstall() {
            if (!manager.isConnected()) {
                return;
            }
            resolveAndValidate();
            manager.uninstallRemote(builder.serviceName);
            log.info("[service-remote] 远程卸载完成: name={}", builder.serviceName);
        }

        private void resolveAndValidate() {
            if (builder.serviceName == null || builder.serviceName.isBlank()) {
                throw new IllegalStateException("[service] withServiceName() 未设置");
            }
            if (builder.jarPath == null || builder.jarPath.isBlank()) {
                throw new IllegalStateException("[service] withJar() 未设置");
            }
            if (sshHost == null || sshHost.isBlank()) {
                throw new IllegalStateException("[service-remote] SSH host 未设置，调用 withHost() 或 toRemote(host,port,user,pwd)");
            }
            if (sshUsername == null || sshUsername.isBlank()) {
                throw new IllegalStateException("[service-remote] SSH username 未设置");
            }
        }

        private String buildRemoteStartCmd() {
            if (builder.startCmd != null) {
                return builder.startCmd.replace("{jar}", builder.jarPath);
            }
            return "java -jar \"" + builder.jarPath + "\"";
        }
    }

    // ---------- DSL：静态快捷入口 ----------

    /**
     * 本地模式快捷入口：{@code ServiceBuilder.local().start()}
     * @author CH
     * @since 4.0.0
     * @return 转为位置的结果
     * @param cmd CMD
     */
    public static class LocalDsl {
        /**
          * withjar。
         * @param jarPath jar路径
         * @return withJar的结果
         */
        private final ServiceBuilder b = new ServiceBuilder();

        /**
         * withJar。
         * @param jarPath jar路径
         * @return withJar的结果
         */
        public LocalDsl withJar(String jarPath) {
            b.withJar(jarPath);
            return this;
        /**
         * with服务名称。
         * @param name 名称
         * @return with服务名称的结果
         * @param cmd cmd
         */
        }

        public LocalDsl withServiceName(String name) {
            b.withServiceName(name);
            return this;
        }

        public LocalDsl withStartCmd(String cmd) {
            b.withStartCmd(cmd);
            return this;
        }

        public LocationManager toLocation() {
            return new LocationManager(b);
        }
    }

    /**
     * 远程模式快捷入口：{@code ServiceBuilder.remote().start()}
     * @author CH
     * @since 4.0.0
     * @param host 主机
     * @param port 端口
     * @param username 用户名
     * @param password 密码
     /**
       * withjar。
      * @param jarPath jar路径
      * @return withJar的结果
      */
     * @return 转为远程的结果
     */
    public static class RemoteDsl {
        private final ServiceBuilder b = new ServiceBuilder(); // b

        /**
         * with服务名称。
         * @param name 名称
         * @return with服务名称的结果
         * @param host 主机
         /**
          * withJar。
          * @param jarPath jar路径
          * @return withJar的结果
          */
         * @param port 端口
         * @param username 用户名
         * @param password 密码
         */
        public RemoteDsl withJar(String jarPath) {
            /**
             * with服务名称。
             * @param name 名称
             * @return with服务名称的结果
             */
            b.withJar(jarPath);
            return this;
        }

        public RemoteDsl withServiceName(String name) {
            b.withServiceName(name);
            return this;
        }

        public RemoteManager toRemote(String host, int port, String username, String password) {
            RemoteManager m = new RemoteManager(b);
            m.withHost(host).withPort(port).withUsername(username).withPassword(password);
            return m;
        }

        /**
         * 指定协议（ssh/winrm）后切换远程模式。
         *
         * @param protocol 远程协议（ssh / winrm）
         * @param host 主机
         /**
          * 转为远程。
          * @param protocol 协议
          * @param host 主机
          * @param port 端口
          * @param username 用户名
          * @param password 密码
          * @return 转为远程的结果
          */
         * @param port 端口
         * @param username 用户名
         * @param privateKey 私募键
         * @return 转为远程键的结果
         */
        public RemoteManager toRemote(String protocol, String host, int port, String username, String password) {
            RemoteManager m = new RemoteManager(b, protocol);
            m.withHost(host).withPort(port).withUsername(username).withPassword(password);
            return m;
        }

        public RemoteManager toRemoteKey(String host, int port, String username, String privateKey) {
            RemoteManager m = new RemoteManager(b);
            m.withHost(host).withPort(port).withUsername(username).withPrivateKey(privateKey);
            return m;
        }
    }

    @Override
    public void close() {
        // no-op
    }
}
