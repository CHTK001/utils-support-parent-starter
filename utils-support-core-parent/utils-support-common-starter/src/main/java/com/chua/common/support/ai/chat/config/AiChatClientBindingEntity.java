package com.chua.common.support.ai.chat.config;

import lombok.Data;

/**
 * AI 对话客户端绑定实体 — 将层次化配置扁平化为单行记录。
 *
 * <p>每个实体代表一个客户端在其所属组中的绑定关系，
 * 通过 Engine ORM 属性字段持久化，支持 Lambda 按任意字段查询：
 * <pre>{@code
 *   // 查询使用 OpenAI 的所有绑定
 *   List<AiChatClientBindingEntity> bindings = engine.query(AiChatClientBindingEntity.class)
 *       .eq("provider", "openai").list();
 *
 *   // 查询某个配置的所有绑定
 *   List<AiChatClientBindingEntity> bindings = engine.query(AiChatClientBindingEntity.class)
 *       .eq("configId", configId)
 *       .orderByAsc("groupOrder, clientOrder")
 *       .list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class AiChatClientBindingEntity {

    /** 主键 */
    /**
     * 标识
     */
    private Long id;

    /** 所属配置 ID（关联 {@link AiChatConfigEntity}） */
    private Long configId;

    // ==================== 组上下文 ====================

    /** 组名称 */
    private String groupName;

    /** 组内策略（failover | round_robin | weighted | cost | latency） */
    private String groupStrategy;

    /** 组条件表达式（如 "prompt.length < 200"） */
    private String groupCondition;

    /** 组排序 */
    private Integer groupOrder;

    // ==================== 客户端配置 ====================

    /** AI 服务商名称，如 "openai"、"alibaba" */
    /**
     * 提供方标识
     */
    private String provider;

    /** API 密钥 */
    /**
     * API 密钥
     */
    private String apiKey;

    /** 自定义 API 地址（可选） */
    private String baseUrl;

    /** 模型名称（可选） */
    /**
     * 模型名称
     */
    private String model;

    /** 温度参数（可选） */
    private Double temperature;

    /** 最大 Token 数（可选） */
    private Integer maxTokens;

    /** 系统提示词（可选） */
    private String systemPrompt;

    /** HTTP 代理（可选） */
    private String proxy;

    /** 权重（weighted 策略使用，默认 1） */
    private int weight = 1;

    /** 客户端排序 */
    private Integer clientOrder;

    // ==================== 审计 ====================

    /** 创建时间 */
    /**
     * 创建时间
     */
    private Long createdAt;

    /** 更新时间 */
    /**
     * 更新时间
     */
    private Long updatedAt;
}
