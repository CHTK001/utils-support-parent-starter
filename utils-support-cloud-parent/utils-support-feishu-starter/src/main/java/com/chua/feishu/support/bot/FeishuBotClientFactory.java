package com.chua.feishu.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * 飞书 Bot 客户端工厂（SPI 实现）。
 * <p>平台名称为 {@code feishu}。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
@Slf4j
public class FeishuBotClientFactory implements BotClient.Factory {

    @Override
    public BotClient create() {
        log.debug("Creating Feishu Bot client");
        return new FeishuBotClient();
    }

    @Override
    public BotClient.Builder builder() {
        log.debug("Creating Feishu Bot client builder");
        return new FeishuBuilder();
    }

    /**
     * 飞书 Builder 内部类
     *
 * @author CH
     */
    static class FeishuBuilder implements BotClient.Builder {

        /**
         * 应用 ID
         */
        private String appId;

        /**
         * 应用密钥
         */
        private String appSecret;

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
            return this;
        }

        @Override
        public BotClient build() {
            FeishuBotClient client = new FeishuBotClient();
            if (appId != null) {
                client.token(appId);
            }
            if (appSecret != null) {
                client.secret(appSecret);
            }
            if (baseUrl != null) {
                client.baseUrl(baseUrl);
            }
            client.connectTimeoutMillis(connectTimeoutMillis);
            client.readTimeoutMillis(readTimeoutMillis);
            log.debug("Built Feishu Bot client with appId={}, "
                    + "baseUrl={}", appId, baseUrl);
            return client;
        }
    }
}
