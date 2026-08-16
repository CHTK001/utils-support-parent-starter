package com.chua.qq.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * QQ Bot 客户端工厂（SPI 实现）。
 * <p>平台名称为 {@code qq}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class QqBotClientFactory implements BotClient.Factory {

    @Override
    public BotClient create() {
        log.debug("Creating QQ Bot client");
        return new QqBotClient();
    }

    @Override
    public BotClient.Builder builder() {
        log.debug("Creating QQ Bot client builder");
        return new QqBuilder();
    }

    /**
     * QQ Builder 内部类
     *
 * @author CH
     */
    static class QqBuilder implements BotClient.Builder {

        /**
         * 应用 ID
         */
        private String appId;

        /**
         * 应用密钥
         */
        private String appSecret;

        /**
         * Bot Token
         */
        private String botToken;

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
        public BotClient.Builder token(String token) {
            this.appId = token;
            return this;
        }

        @Override
        public BotClient.Builder secret(String secret) {
            this.appSecret = secret;
            return this;
        }

        @Override
        public BotClient.Builder encodingAesKey(
                String encodingAesKey) {
            this.botToken = encodingAesKey;
            return this;
        }

        @Override
        public BotClient.Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        @Override
        public BotClient.Builder connectTimeoutMillis(
                long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        @Override
        public BotClient.Builder readTimeoutMillis(
                long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        @Override
        public BotClient.Builder configSaveOrLoader(
                ConfigSaveOrLoader configSaveOrLoader) {
            this.configSaveOrLoader = configSaveOrLoader;
            return this;
        }

        @Override
        public BotClient build() {
            QqBotClient client = new QqBotClient();
            if (appId != null) {
                client.token(appId);
            }
            if (appSecret != null) {
                client.secret(appSecret);
            }
            if (botToken != null) {
                client.encodingAesKey(botToken);
            }
            if (baseUrl != null) {
                client.baseUrl(baseUrl);
            }
            if (configSaveOrLoader != null) {
                client.configSaveOrLoader(configSaveOrLoader);
            }
            client.connectTimeoutMillis(connectTimeoutMillis);
            client.readTimeoutMillis(readTimeoutMillis);
            log.debug("Built QQ Bot client with appId={}, "
                    + "baseUrl={}", appId, baseUrl);
            return client;
        }
    }
}
