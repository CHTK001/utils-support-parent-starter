package com.chua.ionet.support.client;

import com.iohao.net.external.core.config.ExternalGlobalConfig;
import com.iohao.net.external.core.config.ExternalJoinEnum;
import com.iohao.net.extension.client.InputCommandRegion;
import com.iohao.net.extension.client.join.ClientRunOne;
import com.iohao.net.extension.client.kit.ClientUserConfigs;
import com.iohao.net.extension.client.user.ClientUser;
import com.iohao.net.extension.client.user.DefaultClientUser;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * ionet 模拟客户端封装 — 简化 ClientRunOne 的启动流程
 * <p>
 * 使用 Builder 模式构建：
 * <pre>
 * IonetClient client = IonetClient.builder()
 *     .host("127.0.0.1")
 *     .port(10100)
 *     .addRegion(new MyRegion())
 *     .build();
 * client.startup();
 * </pre>
 *
 * @author CH
 */
@Slf4j
public class IonetClient {

    /**
     * 服务器主机地址
     */
    private final String host;
    /**
     * 服务器端口号
     */
    private final int port;
    /**
     * 连接方式（TCP/WebSocket）
     */
    private final ExternalJoinEnum joinType;
    /**
     * 输入命令区域列表
     */
    private final List<InputCommandRegion> regions;
    /**
     * 客户端用户对象
     */
    private final ClientUser clientUser;
    /**
     * 是否关闭日志输出
     */
    private final boolean closeLog;
    /**
     * 是否关闭控制台输入扫描
     */
    private final boolean closeScanner;
    /**
     * ClientRunOne 自定义配置器
     */
    private final Consumer<ClientRunOne> configurer;

    private IonetClient(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.joinType = builder.joinType;
        this.regions = builder.regions;
        this.clientUser = builder.clientUser;
        this.closeLog = builder.closeLog;
        this.closeScanner = builder.closeScanner;
        this.configurer = builder.configurer;
    }

    /**
     * 启动模拟客户端
     */
    public void startup() {
        Locale.setDefault(Locale.CHINA);

        if (closeLog) {
            ClientUserConfigs.closeLog();
        }
        if (closeScanner) {
            ClientUserConfigs.closeScanner = true;
        }

        var clientRunOne = new ClientRunOne()
                .setInputCommandRegions(regions);

        if (clientUser != null) {
            clientRunOne.setClientUser(clientUser);
        }

        if (configurer != null) {
            configurer.accept(clientRunOne);
        }

        clientRunOne.startup();

        log.info("[IonetClient] Connected to {}:{} joinType={}", host, port, joinType);
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        /**
         * 服务器主机地址，默认 127.0.0.1
         */
        private String host = "127.0.0.1";
        /**
         * 服务器端口号，默认取外部全局端口
         */
        private int port = ExternalGlobalConfig.externalPort;
        /**
         * 连接方式，默认 TCP
         */
        private ExternalJoinEnum joinType = ExternalJoinEnum.TCP;
        /**
         * 输入命令区域列表
         */
        private final List<InputCommandRegion> regions = new java.util.ArrayList<>();
        /**
         * 客户端用户对象
         */
        private ClientUser clientUser;
        /**
         * 是否关闭日志输出，默认 false
         */
        private boolean closeLog = false;
        /**
         * 是否关闭控制台输入扫描，默认 false
         */
        private boolean closeScanner = false;
        /**
         * ClientRunOne 自定义配置器
         */
        private Consumer<ClientRunOne> configurer;

        public Builder host(String host) { this.host = host; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder joinType(ExternalJoinEnum joinType) { this.joinType = joinType; return this; }
        public Builder addRegion(InputCommandRegion region) { this.regions.add(region); return this; }
        public Builder regions(List<InputCommandRegion> regions) { this.regions.addAll(regions); return this; }
        public Builder clientUser(ClientUser clientUser) { this.clientUser = clientUser; return this; }
        public Builder userId(long userId) { this.clientUser = new DefaultClientUser(); this.clientUser.setJwt(String.valueOf(userId)); return this; }
        public Builder closeLog(boolean close) { this.closeLog = close; return this; }
        public Builder closeScanner(boolean close) { this.closeScanner = close; return this; }
        public Builder configurer(Consumer<ClientRunOne> configurer) { this.configurer = configurer; return this; }

        public IonetClient build() {
            if (regions.isEmpty()) {
                throw new IllegalArgumentException("At least one InputCommandRegion is required: call .addRegion(region)");
            }
            return new IonetClient(this);
        }
    }
}