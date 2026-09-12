package com.chua.smb.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.smb.bridge.RustSmbServerBridge;
import lombok.extern.slf4j.Slf4j;

/**
* SMB 嵌入式服务器，继承 {@link AbstractServer}。
*
* <p>基于 Rust 原生库（smb-server crate，SMB2/3 协议）驱动 SMB 服务端，
* Java 侧负责生命周期管理和配置。</p>
*
* <h2>使用方式</h2>
* <pre>{@code
* ServerSetting setting = ServerSetting.defaults();
* setting.setPort(445);
* SmbServer server = SmbServer.builder()
*     .host("0.0.0.0")
*     .port(445)
*     .shareName("smbshare")
*     .rootPath("/tmp/smbroot")
*     .build();
* server.start();
* server.stop();
* }</pre>build();
* server.start();
* server.stop();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class SmbServer extends AbstractServer {

    /**
    * 默认 端口
     */
    public static final int DEFAULT_PORT = 445;

    /**
    * 用户
     */
    private final String user;
    /**
    * 登录密码
     */
    private final String password;
    /**
    * 共享 名称
     */
    private final String shareName;
    /**
    * 根 路径
     */
    private final String rootPath;
    /**
    * 服务器 处理
     */
    private volatile long serverHandle = -1;

    /**
    * 创建 smb服务端 实例
    * @param setting setting
    * @param user 用户
    * @param password 密码
    * @param shareName 共享名称
    * @param rootPath 根路径
     */
    protected SmbServer(ServerSetting setting, String user, String password,
                        String shareName, String rootPath) {
        super(setting);
        this.user = user != null ? user : "";
        this.password = password != null ? password : "";
        this.shareName = shareName != null ? shareName : "smbshare";
        this.rootPath = rootPath != null ? rootPath : "/tmp/smbroot";
    }

    @Override
    /** 获取协议类型 */
    public ProtocolType getProtocolType() {
        return ProtocolType.SMB;
    }

    @Override
    /** 执行开始 */
    protected void doStart() {
        try {
            RustSmbServerBridge.loadLibrary();
            serverHandle = RustSmbServerBridge.start(
                    setting.getHost(),
                    setting.getPort(),
                    shareName,
                    rootPath,
                    user,
                    password
            );
            log.info("SmbServer 已启动: smb://{}:{} share={} root={}",
                    setting.getHost(), setting.getPort(), shareName, rootPath);
        } catch (Exception e) {
            throw new RuntimeException("SMB 服务器启动失败", e);
        }
    }

    @Override
    /** 执行停止 */
    protected void doStop() {
        if (serverHandle > 0) {
            try {
                RustSmbServerBridge.stop(serverHandle);
            } catch (Exception e) {
                log.warn("SmbServer 停止异常", e);
            } finally {
                serverHandle = -1;
            }
        }
        log.info("SmbServer 已停止");
    }

    // ==================== 构建器 ====================

    /**
    * 构建器
    *
    * @return 构建器的结果
    * @author CH
    * @since 4.0.0
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        /**
        * 端口号
         */
        private int port = DEFAULT_PORT;
        /**
        * 主机地址
         */
        private String host = "0.0.0.0";
        /**
        * 用户
         */
        private String user = "";
        /**
        * 登录密码
         */
        private String password = "";
        /**
        * 共享 名称
         */
        private String shareName = "smbshare";
        /**
        * 根 路径
         */
        private String rootPath = "/tmp/smbroot";

        /**
        * 端口
        *
        * @param port 端口
        * @return 端口的结果
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
        * 主机
        *
        * @param host 主机
        * @return 主机的结果
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
        * 用户
        *
        * @param user 用户
        * @return 用户的结果
         */
        public Builder user(String user) {
            this.user = user;
            return this;
        }

        /**
        * 密码
        *
        * @param password 密码
        * @return 密码的结果
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
        * 共享名称
        *
        * @param shareName 共享名称
        * @return 共享名称的结果
         */
        public Builder shareName(String shareName) {
            this.shareName = shareName;
            return this;
        }

        /**
        * 根路径
        *
        * @param rootPath 根路径
        * @return 根路径的结果
         */
        public Builder rootPath(String rootPath) {
            this.rootPath = rootPath;
            return this;
        }

        /**
        * 构建
        *
        * @return 构建的结果
         */
        public SmbServer build() {
            ServerSetting setting = ServerSetting.defaults();
            setting.setHost(host);
            setting.setPort(port);
            return new SmbServer(setting, user, password, shareName, rootPath);
        }
    }
}
