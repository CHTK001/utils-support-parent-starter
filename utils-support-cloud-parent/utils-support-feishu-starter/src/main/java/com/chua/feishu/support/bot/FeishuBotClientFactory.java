package com.chua.feishu.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.spi.annotations.Spi;

import lombok.extern.slf4j.Slf4j;

/**
* 飞书 机器人 客户端工厂（SPI 实现）。
* <p>平台名称为 {@code feishu}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("feishu")
public class FeishuBotClientFactory implements BotClient.Factory {

    @Override
    /** 创建 */
    public BotClient create() {
        log.debug("Creating Feishu Bot client");
        return new FeishuBotClient();
    }

    @Override
    /** 构建器 */
    public BotClient.Builder builder() {
        log.debug("Creating Feishu Bot client builder");
        return new FeishuBuilder();
    }

    /**
    * 飞书 构建器 内部类
    *
    * @author CH
    * @since 4.0.0
    */
    static class FeishuBuilder implements BotClient.Builder {

        /**
        * 应用 标识
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
        /** 令牌 */
        public BotClient.Builder token(String token) {
            this.appId = token;
            return this;
        }

        @Override
        /** Secret */
        public BotClient.Builder secret(String secret) {
            this.appSecret = secret;
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
        * @param appId appid
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
        * @param appId appid
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
        * @param appId appid
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
        * @param appId appid
        * @param baseUrl baseurl
        */
        public BotClient.Builder configSaveOrLoader(
                ConfigSaveOrLoader configSaveOrLoader) {
            return this;
        }

        @Override
        /** 构建 */
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
