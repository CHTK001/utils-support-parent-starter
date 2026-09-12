package com.chua.common.support.ai.bot;

import com.chua.common.support.pool.PooledObjectClient;
import com.chua.common.support.spi.ServiceProvider;

/**
* Bot 客户端统一接口
* <p>
* 支持多平台 Bot（钉钉、飞书、QQ 等）的统一抽象。
* 通过 {@link Factory} SPI 机制实现平台扩展。
* </p>
*
* <h3>使用示例</h3>
* <pre>{@code
* BotClient client = BotClient.auto("dingtalk");
* client.configure(token, secret, null);
* client.addMessageListener(msg -> {
*     client.sendText(msg.getFromUser(), "reply: " + msg.getContent());
* });
* client.start();
* }</pre>
*
* @author CH
* @since 2026/07/18
 */
public interface BotClient extends PooledObjectClient<BotClient> {

    // ── SPI Auto-load ────────────────────────────────────────────

    /**
    * 根据平台名称自动创建 BotClient
    *
    * @param platform 平台名称（如 "dingtalk"、"feishu"、"qq"）
    * @return BotClient 实例
    * @throws IllegalStateException 如果未找到对应平台的 Factory
     */
    static BotClient auto(String platform) {
        Factory factory = ServiceProvider.of(Factory.class)
                .getExtension(platform);
        if (factory == null) {
            throw new IllegalStateException(
                    "No BotClient.Factory found for platform: "
                            + platform);
        }
        return factory.create();
    }

    /**
    * 根据平台名称创建 Builder
    *
    * @param platform 平台名称
    * @return Builder 实例
    * @throws IllegalStateException 如果未找到对应平台的 Factory
     */
    static BotClient.Builder builder(String platform) {
        Factory factory = ServiceProvider.of(Factory.class)
                .getExtension(platform);
        if (factory == null) {
            throw new IllegalStateException(
                    "No BotClient.Factory found for platform: " + platform);
        }
        return factory.builder();
    }

    // ── Configuration ────────────────────────────────────────────

    /**
    * 配置客户端
    *
    * @param token          平台凭证（钉钉 access_token / 飞书 App ID / QQ BotAppID）
    * @param secret         密钥（钉钉签名密钥 / 飞书 App Secret / QQ BotSecret）
    * @param encodingAesKey 加密密钥（飞书 Encrypt Key / QQ BotToken）
    * @return this
     */
    BotClient configure(String token, String secret, String encodingAesKey);

    /**
    * 设置平台凭证 token
    *
    * @param token 平台凭证
    * @return this
     */
    BotClient token(String token);

    /**
    * 设置密钥
    *
    * @param secret 密钥
    * @return this
     */
    BotClient secret(String secret);

    /**
    * 设置加密 AES Key
    *
    * @param encodingAesKey 加密密钥
    * @return this
     */
    BotClient encodingAesKey(String encodingAesKey);

    /**
    * 设置 API 基础 URL
    *
    * @param baseUrl API 基础地址
    * @return this
     */
    BotClient baseUrl(String baseUrl);

    /**
    * 设置连接超时（毫秒）
    *
    * @param connectTimeoutMillis 连接超时时间
    * @return this
     */
    BotClient connectTimeoutMillis(long connectTimeoutMillis);

    /**
    * 设置读取超时（毫秒）
    *
    * @param readTimeoutMillis 读取超时时间
    * @return this
     */
    BotClient readTimeoutMillis(long readTimeoutMillis);

    /**
    * 设置配置持久化加载器
    *
    * @param configSaveOrLoader 配置加载器
    * @return this
     */
    BotClient configSaveOrLoader(
            com.chua.common.support.config.loader.ConfigSaveOrLoader configSaveOrLoader);

    // ── Lifecycle ────────────────────────────────────────────────

    /**
    * 启动客户端
    *
    * @return this
     */
    BotClient start();

    /**
    * 停止客户端
     */
    void stop();

    /**
    * 是否运行中
    *
    * @return true 表示运行中
     */
    boolean isRunning();

    // ── Message sending (sync) ───────────────────────────────────

    /**
    * 发送文本消息
    *
    * @param toUser   目标用户 ID
    * @param content  消息内容
    * @return 发送结果
     */
    BotSendResult sendText(String toUser, String content);

    /**
    * 发送图片消息
    *
    * @param toUser     目标用户 ID
    * @param mediaPath  图片本地路径
    * @return 发送结果
     */
    BotSendResult sendImage(String toUser, String mediaPath);

    /**
    * 发送语音消息
    *
    * @param toUser     目标用户 ID
    * @param mediaPath  语音文件路径
    * @return 发送结果
     */
    BotSendResult sendVoice(String toUser, String mediaPath);

    /**
    * 发送视频消息
    *
    * @param toUser     目标用户 ID
    * @param mediaPath  视频文件路径
    * @param title      视频标题
    * @param desc       视频描述
    * @return 发送结果
     */
    BotSendResult sendVideo(
            String toUser,
            String mediaPath,
            String title,
            String desc);

    /**
    * 发送文件消息
    *
    * @param toUser     目标用户 ID
    * @param mediaPath  文件本地路径
    * @return 发送结果
     */
    BotSendResult sendFile(String toUser, String mediaPath);

    /**
    * 发送通用出站消息
    *
    * @param message 出站消息对象
    * @return 发送结果
     */
    BotSendResult send(BotOutboundMessage message);

    // ── Message sending (async) ──────────────────────────────────

    /**
    * 异步发送文本消息
    *
    * @param toUser   目标用户 ID
    * @param content  消息内容
    * @return 异步发送结果
     */
    java.util.concurrent.CompletableFuture<BotSendResult> sendTextAsync(
            String toUser, String content);

    /**
    * 异步发送图片消息
    *
    * @param toUser     目标用户 ID
    * @param mediaPath  图片本地路径
    * @return 异步发送结果
     */
    java.util.concurrent.CompletableFuture<BotSendResult> sendImageAsync(
            String toUser, String mediaPath);

    /**
    * 异步发送通用出站消息
    *
    * @param message 出站消息对象
    * @return 异步发送结果
     */
    java.util.concurrent.CompletableFuture<BotSendResult> sendAsync(
            BotOutboundMessage message);

    // ── Group management ─────────────────────────────────────────

    /**
    * 获取群列表
    *
    * @return 群组信息列表
     */
    java.util.List<BotGroupInfo> listGroups();

    /**
    * 向群组发送文本
    *
    * @param groupId 群组 ID
    * @param content 消息内容
    * @return 发送结果
     */
    BotSendResult sendToGroup(String groupId, String content);

    /**
    * 异步向群组发送文本
    *
    * @param groupId 群组 ID
    * @param content 消息内容
    * @return 异步发送结果
     */
    java.util.concurrent.CompletableFuture<BotSendResult> sendToGroupAsync(
            String groupId, String content);

    /**
    * 向群组发送 @提及消息
    *
    * @param groupId        群组 ID
    * @param content        消息内容
    * @param mentionedUserIds 被 @ 的用户 ID 列表
    * @return 发送结果
     */
    BotSendResult sendToGroupMention(
            String groupId,
            String content,
            java.util.List<String> mentionedUserIds);

    /**
    * 异步向群组发送 @提及消息
    *
    * @param groupId        群组 ID
    * @param content        消息内容
    * @param mentionedUserIds 被 @ 的用户 ID 列表
    * @return 异步发送结果
     */
    java.util.concurrent.CompletableFuture<BotSendResult>
    sendToGroupMentionAsync(
            String groupId,
            String content,
            java.util.List<String> mentionedUserIds);

    // ── User store ───────────────────────────────────────────────

    /**
    * 设置用户存储
    *
    * @param userStore 用户存储实例
    * @return this
     */
    BotClient userStore(BotUserStore userStore);

    /**
    * 列出所有用户
    *
    * @return 用户信息列表
     */
    java.util.List<BotUserInfo> listUsers();

    // ── Listeners ────────────────────────────────────────────────

    /**
    * 添加消息监听器
    *
    * @param listener 消息监听器
    * @return this
     */
    BotClient addMessageListener(BotMessageListener listener);

    /**
    * 移除消息监听器
    *
    * @param listener 消息监听器
    * @return this
     */
    BotClient removeMessageListener(BotMessageListener listener);

    /**
    * 添加错误监听器
    *
    * @param listener 错误监听器
    * @return this
     */
    BotClient addErrorListener(BotErrorListener listener);

    // ── Config ───────────────────────────────────────────────────

    /**
    * 获取当前配置
    *
    * @return 配置 Map
     */
    java.util.Map<String, Object> getConfig();

    // ── SPI Factory ──────────────────────────────────────────────

    /**
    * BotClient 工厂 SPI 接口
    * <p>各平台通过实现此接口并注册到
    * {@code META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory}
    * 来实现自动加载。</p>
    *
    * @author CH
     */
    interface Factory {

        /**
        * 创建 BotClient 实例
        *
        * @return BotClient 实例
         */
        BotClient create();

        /**
        * 返回 Builder
        *
        * @return Builder 实例
         */
        Builder builder();
    }

    /**
    * BotClient 构建器
    *
    * @author CH
     */
    interface Builder {

        /**
        * 设置 token
        *
        * @param token 平台凭证
        * @return this
         */
        Builder token(String token);

        /**
        * 设置密钥
        *
        * @param secret 密钥
        * @return this
         */
        Builder secret(String secret);

        /**
        * 设置编码密钥
        *
        * @param encodingAesKey 加密密钥
        * @return this
         */
        Builder encodingAesKey(String encodingAesKey);

        /**
        * 设置基础 URL
        *
        * @param baseUrl API 基础地址
        * @return this
         */
        Builder baseUrl(String baseUrl);

        /**
        * 设置连接超时
        *
        * @param connectTimeoutMillis 连接超时毫秒数
        * @return this
         */
        Builder connectTimeoutMillis(long connectTimeoutMillis);

        /**
        * 设置读取超时
        *
        * @param readTimeoutMillis 读取超时毫秒数
        * @return this
         */
        Builder readTimeoutMillis(long readTimeoutMillis);

        /**
        * 设置配置加载器
        *
        * @param configSaveOrLoader 配置加载器
        * @return this
         */
        Builder configSaveOrLoader(
                com.chua.common.support.config.loader.ConfigSaveOrLoader configSaveOrLoader);

        /**
        * 构建 BotClient
        *
        * @return BotClient 实例
         */
        BotClient build();
    }
}
