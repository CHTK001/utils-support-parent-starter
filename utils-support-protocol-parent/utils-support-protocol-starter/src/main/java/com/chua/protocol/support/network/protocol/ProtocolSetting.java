package com.chua.protocol.support.network.protocol;

/**
 * 协议配置构建器。
 *
 * <p>用于构建协议服务所需的连接参数（协议类型、端口、选项）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ProtocolSetting {

    /**
     * 协议类型（如 http / armeria / kcp / rsocket）
    */
    private String protocol;

    /**
     * 监听端口
    */
    private int port;

    /**
     * 附加选项，接收任意配置对象（如 期权 / 映射）
    */
    private Object options;

    /**
     * 协议setting。
     */
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

    /**
     * 获取协议类型
     *
     * @return 获取协议的结果
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * 获取监听端口
     *
     * @return 获取端口的结果
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取附加选项对象
     *
     * @return 获取期权的结果
     */
    public Object getOptions() {
        return options;
    }

    /**
     * 协议配置构建器。
     */
    public static final class Builder {
        private final ProtocolSetting setting = new ProtocolSetting();

        /**
         * 设置协议类型
         *
         * @param protocol 协议
         * @return 协议的结果
         */
        public Builder protocol(String protocol) {
            setting.protocol = protocol;
            return this;
        }

        /**
         * 设置监听端口
         *
         * @param port 端口
         * @return 端口的结果
         */
        public Builder port(int port) {
            setting.port = port;
            return this;
        }

        /**
         * 设置监听端口（nullable）
         *
         * @param port 端口
         * @return 端口的结果
         */
        public Builder port(Integer port) {
            setting.port = port != null ? port : 0;
            return this;
        }

        /**
         * 设置附加选项（接受任意对象）
         *
         * @param options 期权
         * @return 期权的结果
         */
        public Builder options(Object options) {
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
