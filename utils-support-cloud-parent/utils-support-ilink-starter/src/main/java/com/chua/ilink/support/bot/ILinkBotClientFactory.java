package com.chua.ilink.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.spi.annotations.Spi;

/**
 * i链接 机器人 客户端工厂。
 *
 * <p>SPI 名称：{@code ilink}。通过 {@code BotClient.auto("ilink")} 自动加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ilink")
public class ILinkBotClientFactory implements BotClient.Factory {

    /**
     * 创建 i链接机器人客户端 实例。
     *
     * @return BotClient 实例
     */
    @Override
    public BotClient create() {
        return new ILinkBotClient();
    }

    /**
     * 创建 构建器。
     *
     * @return Builder 实例
     */
    @Override
    public Builder builder() {
        return new Builder();
    }

    /**
     * i链接 机器人 客户端构建器。
     * @author CH
     * @since 4.0.0
     */
    public static class Builder implements BotClient.Builder {

        /**
         * 客户端实例
        */
        private final ILinkBotClient client = new ILinkBotClient();

        @Override
        public Builder token(String token) {
            client.token(token);
            return this;
        }

        @Override
        public Builder secret(String secret) {
            client.secret(secret);
            return this;
        }

        @Override
        public Builder encodingAesKey(String key) {
            client.encodingAesKey(key);
            return this;
        }

        @Override
        public Builder baseUrl(String baseUrl) {
            client.baseUrl(baseUrl);
            return this;
        }

        @Override
        public Builder connectTimeoutMillis(long ms) {
            client.connectTimeoutMillis(ms);
            return this;
        }

        @Override
        public Builder readTimeoutMillis(long ms) {
            client.readTimeoutMillis(ms);
            return this;
        }

        @Override
        public Builder configSaveOrLoader(
                com.chua.common.support.config.loader.ConfigSaveOrLoader loader) {
            client.configSaveOrLoader(loader);
            return this;
        }

        @Override
        public BotClient build() { return client; }
    }
}
