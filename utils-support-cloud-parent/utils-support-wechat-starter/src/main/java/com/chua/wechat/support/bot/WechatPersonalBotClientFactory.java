package com.chua.wechat.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 个人微信机器人客户端工厂（SPI 实现）。
 * <p>平台名称为 {@code wechat-personal}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("wechat-personal")
public class WechatPersonalBotClientFactory implements BotClient.Factory {

    @Override
    public BotClient create() {
        log.debug("Creating WeChat personal bot client");
        return new WechatPersonalBotClient();
    }

    @Override
    public BotClient.Builder builder() {
        log.debug("Creating WeChat personal bot client builder");
        return new WechatPersonalBuilder();
    }

    /**
     * 个人微信构建器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    static class WechatPersonalBuilder implements BotClient.Builder {

        private String bridgeUrl;

        private String callbackUrl;

        private String token;

        private long connectTimeoutMillis = 5_000L;

        private long readTimeoutMillis = 15_000L;

        @Override
        public BotClient.Builder token(String token) {
            this.token = token;
            return this;
        }

        @Override
        public BotClient.Builder secret(String secret) {
            return this;
        }

        @Override
        public BotClient.Builder encodingAesKey(String encodingAesKey) {
            return this;
        }

        @Override
        public BotClient.Builder baseUrl(String baseUrl) {
            this.bridgeUrl = baseUrl;
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
            return this;
        }

        /**
         * 设置入站回调地址。
         *
         * @param callbackUrl 回调地址
         * @return this
         */
        public BotClient.Builder callbackUrl(String callbackUrl) {
            this.callbackUrl = callbackUrl;
            return this;
        }

        @Override
        public BotClient build() {
            WechatPersonalBotClient client = new WechatPersonalBotClient();
            if (StringUtils.isNotBlank(bridgeUrl)) {
                client.baseUrl(bridgeUrl);
            }
            if (StringUtils.isNotBlank(token)) {
                client.token(token);
            }
            client.callbackUrl(callbackUrl);
            client.connectTimeoutMillis(connectTimeoutMillis);
            client.readTimeoutMillis(readTimeoutMillis);
            log.debug("Built WeChat personal bot client with bridge={}", bridgeUrl);
            return client;
        }
    }
}
