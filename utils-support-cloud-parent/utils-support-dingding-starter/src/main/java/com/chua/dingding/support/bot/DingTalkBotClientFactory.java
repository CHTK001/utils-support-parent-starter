package com.chua.dingding.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * 钉钉 机器人 客户端工厂（SPI 实现）。
 * <p>通过 SPI 机制注册到
 * {@code META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory}，
 * 平台名称为 {@code dingtalk}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DingTalkBotClientFactory implements BotClient.Factory {

    @Override
    /** 创建 */
    public BotClient create() {
        log.debug("Creating DingTalk Bot client");
        return new DingTalkBotClient();
    }

    @Override
    /** 构建器 */
    public BotClient.Builder builder() {
        log.debug("Creating DingTalk Bot client builder");
        return new DingTalkBuilder();
    }

    /**
     * 钉钉 构建器 内部类
     *
     * @author CH
     * @since 4.0.0
     */
    static class DingTalkBuilder implements BotClient.Builder {

        /**
         * 平台凭证 令牌
         */
        private String token;

        /**
         * 密钥
         */
        private String secret;

        /**
         * 基础 URL
         */
        private String baseUrl;

        /**
         * 连接超时时间（毫秒）
         */
        private long connectTimeoutMillis = 10_000;

        /**
         * 读取超时时间（毫秒）
         */
        private long readTimeoutMillis = 30_000;

        /**
         * 配置加载器
         */
        private ConfigSaveOrLoader configSaveOrLoader;

        @Override
        /** 令牌 */
        public BotClient.Builder token(String token) {
            this.token = token;
            return this;
        }

        @Override
        /** Secret */
        public BotClient.Builder secret(String secret) {
            this.secret = secret;
            return this;
        }

        @Override
        /**
         * 编码aes键
         * @param encodingAesKey 编码aes键
         * @param baseUrl baseurl
         * @param connectTimeoutMillis 连接超时millis
         * @param readTimeoutMillis 读取超时millis
         * @param configSaveOrLoader 配置保存或加载
         * @param token 令牌
         * @param baseUrl baseurl
         */
        public BotClient.Builder encodingAesKey(
                String encodingAesKey) {
            return this;
        }

        @Override
        /** baseurl */
        public BotClient.Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        @Override
        /**
        * 连接超时millis
        * @param connectTimeoutMillis 连接超时millis
        * @param readTimeoutMillis 读取超时millis
        * @param configSaveOrLoader 配置保存或加载
        * @param token 令牌
        * @param baseUrl baseurl
        */
        public BotClient.Builder connectTimeoutMillis(
                long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        @Override
        /**
         * 读取超时millis
         * @param readTimeoutMillis 读取超时millis
         * @param configSaveOrLoader 配置保存或加载
         * @param token 令牌
         * @param baseUrl baseurl
         */
        public BotClient.Builder readTimeoutMillis(
                long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        @Override
        /**
         * 配置保存或加载
         * @param configSaveOrLoader 配置保存或加载
         * @param token 令牌
         * @param baseUrl baseurl
         */
        public BotClient.Builder configSaveOrLoader(
                ConfigSaveOrLoader configSaveOrLoader) {
            this.configSaveOrLoader = configSaveOrLoader;
            return this;
        }

        @Override
        /** 构建 */
        public BotClient build() {
            DingTalkBotClient client = new DingTalkBotClient();
            if (token != null) {
                client.token(token);
            }
            if (secret != null) {
                client.secret(secret);
            }
            if (baseUrl != null) {
                client.baseUrl(baseUrl);
            }
            client.connectTimeoutMillis(connectTimeoutMillis);
            client.readTimeoutMillis(readTimeoutMillis);
            if (configSaveOrLoader != null) {
                client.configSaveOrLoader(configSaveOrLoader);
            }
            log.debug("Built DingTalk Bot client with token={}, "
                    + "baseUrl={}", token, baseUrl);
            return client;
        }
    }
}
