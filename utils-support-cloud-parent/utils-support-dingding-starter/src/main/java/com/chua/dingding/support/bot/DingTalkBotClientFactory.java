package com.chua.dingding.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * 钉钉 Bot 客户端工厂（SPI 实现）。
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
    /** Builder */
    public BotClient.Builder builder() {
        log.debug("Creating DingTalk Bot client builder");
        return new DingTalkBuilder();
    }

    /**
     * 钉钉 Builder 内部类
     *
     */
    static class DingTalkBuilder implements BotClient.Builder {

        /**
         * 平台凭证 token
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
        /** Token */
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
         * EncodingAesKey
         * @param encodingAesKey encodingAesKey
         * @param baseUrl baseUrl
         * @param connectTimeoutMillis connectTimeoutMillis
         * @param readTimeoutMillis readTimeoutMillis
         * @param configSaveOrLoader configSaveOrLoader
         * @param token token
         * @param baseUrl baseUrl
         */
        public BotClient.Builder encodingAesKey(
                String encodingAesKey) {
            return this;
        }

        @Override
        /** BaseUrl */
        public BotClient.Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        @Override
        /**
         * 连接TimeoutMillis
         * @param connectTimeoutMillis connectTimeoutMillis
         * @param readTimeoutMillis readTimeoutMillis
         * @param configSaveOrLoader configSaveOrLoader
         * @param token token
         * @param baseUrl baseUrl
         */
        public BotClient.Builder connectTimeoutMillis(
                long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        @Override
        /**
         * 读取TimeoutMillis
         * @param readTimeoutMillis readTimeoutMillis
         * @param configSaveOrLoader configSaveOrLoader
         * @param token token
         * @param baseUrl baseUrl
         */
        public BotClient.Builder readTimeoutMillis(
                long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        @Override
        /**
         * Config保存OrLoader
         * @param configSaveOrLoader configSaveOrLoader
         * @param token token
         * @param baseUrl baseUrl
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
