package com.chua.qq.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * QQ 机器人客户端工厂（SPI 实现）。
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
     * QQ 构建器
     *
     * @author CH
     * @since 4.0.0.42
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
         * 预先获取的 access_token
         */
        private String botToken;

        /**
         * API 基础地址
         */
        private String baseUrl;

        /**
         * 令牌颁发地址
         */
        private String tokenUrl;

        /**
         * 订阅意图位掩码
         */
        private int[] intents;

        /**
         * Webhook 回调验证令牌
         */
        private String webhookVerifyToken;

        /**
         * 连接超时（毫秒）
         */
        private long connectTimeoutMillis = 10_000L;

        /**
         * 读取超时（毫秒）
         */
        private long readTimeoutMillis = 30_000L;

        /**
         * 配置持久化器
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
        public BotClient.Builder encodingAesKey(String encodingAesKey) {
            this.botToken = encodingAesKey;
            return this;
        }

        @Override
        public BotClient.Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        @Override
        public BotClient.Builder connectTimeoutMillis(long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        @Override
        public BotClient.Builder readTimeoutMillis(long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        @Override
        public BotClient.Builder configSaveOrLoader(ConfigSaveOrLoader configSaveOrLoader) {
            this.configSaveOrLoader = configSaveOrLoader;
            return this;
        }

        /**
         * 设置令牌颁发地址
         *
         * @param tokenUrl 令牌地址
         * @return this
         */
        public BotClient.Builder tokenUrl(String tokenUrl) {
            this.tokenUrl = tokenUrl;
            return this;
        }

        /**
         * 设置订阅意图
         *
         * @param intents 意图位掩码
         * @return this
         */
        public BotClient.Builder intents(int... intents) {
            this.intents = intents;
            return this;
        }

        /**
         * 设置 Webhook 回调验证令牌
         *
         * @param webhookVerifyToken 验证令牌
         * @return this
         */
        public BotClient.Builder webhookVerifyToken(String webhookVerifyToken) {
            this.webhookVerifyToken = webhookVerifyToken;
            return this;
        }

        @Override
        public BotClient build() {
            QqBotClient client = new QqBotClient();
            client.configure(appId, appSecret, botToken);
            if (baseUrl != null) {
                client.baseUrl(baseUrl);
            }
            if (tokenUrl != null) {
                client.tokenUrl(tokenUrl);
            }
            if (intents != null) {
                client.intents(intents);
            }
            if (webhookVerifyToken != null) {
                client.webhookVerifyToken(webhookVerifyToken);
            }
            if (configSaveOrLoader != null) {
                client.configSaveOrLoader(configSaveOrLoader);
            }
            client.connectTimeoutMillis(connectTimeoutMillis);
            client.readTimeoutMillis(readTimeoutMillis);
            log.debug("Built QQ Bot client with appId={}, baseUrl={}", appId, baseUrl);
            return client;
        }
    }
}
