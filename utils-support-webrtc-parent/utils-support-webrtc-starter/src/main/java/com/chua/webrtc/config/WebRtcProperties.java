package com.chua.webrtc.config;

import com.chua.common.support.network.ProtocolType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebRTC 远控 配置信息。
 *
 * @author CH
 * @since 4.0.0.50
 */
@ConfigurationProperties(prefix = "utils.webrtc")
public class WebRtcProperties {

    /**
     * 服务器组态
     */
    private Server server = new Server();

    public Server getServer() {
        return server;
    }

    public void setServer(Server server) {
        this.server = server;
    }

    /**
     * 信令服务器配置
     */
    public static class Server {

        /**
         * 协议类型，默认 WS
         */
        private String protocol = ProtocolType.WS.name();

        /**
         * 端口号，默认 10000
         */
        private int port = 10000;

        /**
         * 是否自动启动，默认 true
         */
        private boolean autoStart = true;

        /**
         * 是否自动关闭，默认 true
         */
        private boolean autoClose = true;

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }

        public boolean isAutoClose() {
            return autoClose;
        }

        public void setAutoClose(boolean autoClose) {
            this.autoClose = autoClose;
        }
    }
}
