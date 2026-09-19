package com.chua.common.support.ai.chat.config;

import com.chua.common.support.ai.chat.aggregate.AggregateChatClientSetting;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话配置头实体 — 通过 Engine ORM 属性字段持久化。
 *
 * <p>配合 {@link AiChatClientBindingEntity} 使用，替代 JSON blob 存储。
 * 支持 Engine Lambda 按字段查询：
 * <pre>{@code
 *   // 按名称加载
 *   AiChatConfigEntity header = engine.query(AiChatConfigEntity.class)
 *       .eq("name", "production").one();
 *
 *   // 按策略查询
 *   List<AiChatConfigEntity> configs = engine.query(AiChatConfigEntity.class)
 *       .eq("strategy", "hybrid").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class AiChatConfigEntity {

    /** 主键 */
    /**
    * 标识
    */
    private Long id;

    /**
     * 配置名称（唯一标识，如 "production"、"staging"）
     */
    /**
     * 名称
     */
    private String name;

    /**
     * 全局策略（hybrid | failover | round_robin | weighted | cost | latency）
     */
    /**
     * 策略名称
     */
    private String strategy = "hybrid";

    /** 是否启用调用监控 */
    private boolean monitor = true;

    /** 更新时间 */
    /**
     * 更新时间
     */
    private Long updatedAt;

    /**
     * 加载该配置下所有客户端绑定，构造完整配置
     *
     * @param bindings 客户端绑定列表
     * @return AiChatConfig
     */
    public AiChatConfig toConfig(List<AiChatClientBindingEntity> bindings) {
        return AiChatConfig.fromEntity(this, bindings);
    }

    /**
     * 从 AiChatConfig 和绑定列表构建头实体
     *
     * @param config    链式配置
     * @param name      配置名称
     * @return 头实体
     */
    public static AiChatConfigEntity from(AiChatConfig config, String name) {
        AiChatConfigEntity entity = new AiChatConfigEntity();
        entity.setName(name);
        entity.setStrategy(config.getStrategy());
        entity.setMonitor(true);
        entity.setUpdatedAt(System.currentTimeMillis());
        return entity;
    }
}
