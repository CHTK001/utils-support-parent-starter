package com.chua.whatsapp.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.spi.annotations.Spi;

import lombok.extern.slf4j.Slf4j;

/**
 * WhatsApp Cloud API 机器人客户端工厂（SPI 实现）。
 *
 * <p>平台名称为 {@code whatsapp}，通过
 * {@code META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory}
 * 注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("whatsapp")
public class WhatsAppBotClientFactory implements BotClient.Factory {

    @Override
    public BotClient create() {
        log.debug("Creating WhatsApp Bot client");
        return new WhatsAppBotClient();
    }

    @Override
    public BotClient.Builder builder() {
        log.debug("Creating WhatsApp Bot client builder");
        return new WhatsAppBuilder();
    }

    /**
     * WhatsApp 构建器。
     *
     * <p>{@code token} = WhatsApp {@code phone_number_id}，
     * {@code secret} = Meta {@code access_token}，
     * {@code encodingAesKey} = Meta App Secret（用于 Webhook 签名校验）。</p>
     */
    static class WhatsAppBuilder implements BotClient.Builder {

        private String phoneNumberId;
        private String accessToken;
        private String appSecret;
        private String verifyToken;
        private String baseUrl;
        private String httpProxy;
        private long connectTimeoutMillis = 10_000L;
        private long readTimeoutMillis = 30_000L;

        @Override
        public BotClient.Builder token(String token) {
            this.phoneNumberId = token;
            return this;
        }

        @Override
        public BotClient.Builder secret(String secret) {
            this.accessToken = secret;
            return this;
        }

        @Override
        public BotClient.Builder encodingAesKey(String encodingAesKey) {
            this.appSecret = encodingAesKey;
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
            return this;
        }

        /**
         * 设置 Webhook 校验令牌。
         *
         * @param verifyToken Meta Webhook 验证令牌
         * @return this
         */
        public BotClient.Builder verifyToken(String verifyToken) {
            this.verifyToken = verifyToken;
            return this;
        }

        /**
         * 设置出站 HTTP 代理（Meta Graph API 调用走该代理）。
         *
         * @param httpProxy 代理地址，格式 {@code host:port}
         * @return this
         */
        public BotClient.Builder httpProxy(String httpProxy) {
            this.httpProxy = httpProxy;
            return this;
        }

        @Override
        public BotClient build() {
            WhatsAppBotClient client = new WhatsAppBotClient();
            client.configure(phoneNumberId, accessToken, appSecret);
            if (verifyToken != null) {
                client.verifyToken(verifyToken);
            }
            if (baseUrl != null) {
                client.baseUrl(baseUrl);
            }
            if (httpProxy != null) {
                client.httpProxy(httpProxy);
            }
            client.connectTimeoutMillis(connectTimeoutMillis);
            client.readTimeoutMillis(readTimeoutMillis);
            log.debug("Built WhatsApp Bot client with phoneNumberId={}, baseUrl={}",
                    phoneNumberId, baseUrl);
            return client;
        }
    }
}