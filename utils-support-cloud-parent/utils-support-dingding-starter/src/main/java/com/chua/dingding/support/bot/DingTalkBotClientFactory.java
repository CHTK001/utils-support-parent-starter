package com.chua.dingding.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * 钉钉机器人客户端工厂（SPI 实现）。
 * <p>通过 SPI 机制注册到
 * {@code META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory}，
 * 平台名称为 {@code dingtalk}。泛化构建器只有 token/secret 两个凭证位，
 * 对钉钉即 clientId（AppKey）与 clientSecret（AppSecret）；Stream 订阅主题、
 * 机器人编码等扩展项须拿到实例后通过 {@link DingTalkBotClient} 的专有方法设置。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DingTalkBotClientFactory implements BotClient.Factory {

    @Override
    public BotClient create() {
        log.debug("创建钉钉机器人客户端");
        return new DingTalkBotClient();
    }

    @Override
    public BotClient.Builder builder() {
        log.debug("创建钉钉机器人客户端构建器");
        return new DingTalkBuilder();
    }

    /**
     * 钉钉构建器，收集泛化凭证后装配 {@link DingTalkBotClient}
     *
     * @author CH
     * @since 4.0.0
     */
    static class DingTalkBuilder implements BotClient.Builder {

        /**
         * 应用凭证 ID（AppKey）
         */
        private String token;

        /**
         * 应用凭证密钥（AppSecret）
         */
        private String secret;

        /**
         * 开放平台 API 域名
         */
        private String baseUrl;

        /**
         * 连接超时时间（毫秒）
         */
        private long connectTimeoutMillis = 10_000L;

        /**
         * 读取超时时间（毫秒）
         */
        private long readTimeoutMillis = 30_000L;

        /**
         * 配置保存或加载器
         */
        private ConfigSaveOrLoader configSaveOrLoader;

        @Override
        public BotClient.Builder token(String token) {
            this.token = token;
            return this;
        }

        @Override
        public BotClient.Builder secret(String secret) {
            this.secret = secret;
            return this;
        }

        @Override
        public BotClient.Builder encodingAesKey(String encodingAesKey) {
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

        @Override
        public BotClient build() {
            DingTalkBotClient client = new DingTalkBotClient();
            client.token(token);
            client.secret(secret);
            client.baseUrl(baseUrl);
            client.connectTimeoutMillis(connectTimeoutMillis);
            client.readTimeoutMillis(readTimeoutMillis);
            client.configSaveOrLoader(configSaveOrLoader);
            log.debug("构建钉钉机器人客户端, clientId={}, apiBaseUrl={}", mask(token), baseUrl);
            return client;
        }

        /**
         * 掩码凭证，避免调试日志泄露 AppKey
         *
         * @param value 原始值
         * @return 掩码后的值
         */
        private static String mask(String value) {
            if (value == null || value.length() <= 8) {
                return value == null ? null : "***";
            }
            return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
        }
    }
}
