package com.chua.protocol.support.network.protocol;

import com.chua.common.support.base.collection.Options;

/**
 * 协议配置构建器。
 *
 * <p>用于构建协议服务所需的连接参数（协议类型、端口、选项）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ProtocolSetting {

    /** 协议类型（如 http / armeria / kcp / rsocket） */
    private String protocol;

    /** 监听端口 */
    private int port;

    /** 附加选项 */
    private Options options;

    private ProtocolSetting() {
    }

    /**
     * 创建构建器。
     *
     * @return 新的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 获取协议类型 */
    public String getProtocol() {
        return protocol;
    }

    /** 获取监听端口 */
    public int getPort() {
        return port;
    }

    /** 获取附加选项 */
    public Options getOptions() {
        return options;
    }

    /**
     * 协议配置构建器。
     */
    public static final class Builder {
        private final ProtocolSetting setting = new ProtocolSetting();

        /** 设置协议类型 */
        public Builder protocol(String protocol) {
            setting.protocol = protocol;
            return this;
        }

        /** 设置监听端口 */
        public Builder port(int port) {
            setting.port = port;
            return this;
        }

        /** 设置监听端口（nullable） */
        public Builder port(Integer port) {
            setting.port = port != null ? port : 0;
            return this;
        }

        /** 设置附加选项 */
        public Builder options(Options options) {
            setting.options = options;
            return this;
        }

        /**
         * 构建协议配置。
         *
         * @return 不可变的协议配置实例
         */
        public ProtocolSetting build() {
            return setting;
        }
    }
}
