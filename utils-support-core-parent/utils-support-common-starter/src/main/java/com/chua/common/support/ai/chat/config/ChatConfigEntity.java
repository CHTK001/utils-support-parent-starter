package com.chua.common.support.ai.chat.config;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import lombok.Data;

/**
 * 单个 ChatClient 配置持久化实体 — 通过 Engine ORM 属性字段存储。
 *
 * <p>与 {@link ChatConfig} 配合使用，替代 JSON/@Builder 配置。
 * 所有字段均为属性，支持 Engine Lambda 按任意字段查询：
 * <pre>{@code
 *   // 保存配置
 *   ChatConfigEntity entity = ChatConfig.configure()
 *       .provider("openai").apiKey("sk-xxx").model("gpt-4")
 *       .toEntity("my-openai");
 *   engine.update(ChatConfigEntity.class).saveOrUpdate(entity);
 *
 *   // 按名称加载
 *   ChatConfigEntity entity = engine.query(ChatConfigEntity.class)
 *       .eq("name", "my-openai").one();
 *   ChatClient client = entity.toChatClient();
 *
 *   // 按服务商查询
 *   List<ChatConfigEntity> configs = engine.query(ChatConfigEntity.class)
 *       .eq("provider", "openai").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class ChatConfigEntity {

    /** 主键 */
    /**
    * 标识
    */
    private Long id;

    /**
     * 配置名称（唯一标识，如 "production"、"my-openai"）
     */
    /**
     * 名称
     */
    private String name;

    /** AI 服务商名称 */
    /**
    * 提供方标识
    */
    private String provider;

    /** API 密钥 */
    /**
    * 应用密钥
    */
    private String appKey;

    /** API 密钥（备用） */
    /**
    * 应用密钥
    */
    private String appSecret;

    /** API 请求基础地址 */
    private String baseUrl;

    /** 默认模型名称 */
    /**
     * 模型名称
     */
    private String model;

    /** 默认温度参数 */
    private Double temperature;

    /** 默认最大输出 Token 数 */
    private Integer maxTokens;

    /** 默认 Top-P 采样参数 */
    private Double topP;

    /** 默认系统提示词 */
    private String system;

    /** HTTP 代理地址 */
    private String proxy;

    /** 更新时间 */
    /**
     * 更新时间
     */
    private Long updatedAt;

    // ======================== 转换方法 ========================

    /**
     * 转为 {@link ChatConfig} 链式配置
     *
     * @return ChatConfig
     */
    public ChatConfig toConfig() {
        return ChatConfig.fromEntity(this);
    }

    /**
     * 直接创建 {@link ChatClient}
     *
     * @return ChatClient 实例
     */
    public ChatClient toChatClient() {
        return toConfig().newChatClient();
    }

    /**
     * 转为 {@link ChatClientSetting}
     *
     * @return ChatClientSetting
     */
    public ChatClientSetting toSetting() {
        return toConfig().toSetting();
    }

    /**
     * 从 {@link ChatConfig} 构建实体
     *
     * @param config 链式配置
     * @param name   配置名称
     * @return ChatConfigEntity
     */
    public static ChatConfigEntity from(ChatConfig config, String name) {
        ChatConfigEntity entity = new ChatConfigEntity();
        entity.setName(name);
        entity.setProvider(config.getProvider());
        entity.setAppKey(config.getApiKey());
        entity.setAppSecret(config.getAppSecret());
        entity.setBaseUrl(config.getBaseUrl());
        entity.setModel(config.getModel());
        entity.setTemperature(config.getTemperature());
        entity.setMaxTokens(config.getMaxTokens());
        entity.setTopP(config.getTopP());
        entity.setSystem(config.getSystem());
        entity.setProxy(config.getProxy());
        entity.setUpdatedAt(System.currentTimeMillis());
        return entity;
    }
}
