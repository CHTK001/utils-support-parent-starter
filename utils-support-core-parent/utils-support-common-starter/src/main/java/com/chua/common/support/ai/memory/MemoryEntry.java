package com.chua.common.support.ai.memory;

import lombok.Builder;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
* 记忆条目。
*
* <p>表示一条从对话中提炼出来的长期记忆。每条记忆包含内容、来源、时间戳和元数据。
* 记忆体通过 MCP 插件暴露给 Agent，Agent 可搜索、保存和管理记忆。
*
* @author CH
* @since 2026/07/16
 */
@Builder(toBuilder = true)
public record MemoryEntry(
        /**
        * 记忆 ID。
        *
        * <p>唯一标识，由存储层自动生成（UUID）。
         */
        String id,
        /**
        * 记忆内容。
        *
        * <p>从对话中提炼的高质量文本，经过 AI 总结后生成。
        * 长度受 {@link MemoryConfig#maxContentLength} 限制。
         */
        String content,
        /**
        * 记忆类型。
        *
        * <p>分类标签，如 "fact"（事实）、"preference"（偏好）、"summary"（总结）等。
        * 便于按类型检索。
         */
        String type,
        /**
        * 会话 ID。
        *
        * <p>标识该记忆来源于哪次会话，便于按会话清理或回溯。
         */
        String sessionId,
        /**
        * Agent 标识。
        *
        * <p>生成该记忆的 Agent ID，多 Agent 场景下按 Agent 隔离记忆。
         */
        String agentId,
        /**
        * 创建时间戳（毫秒）。
         */
        long createdAt,
        /**
        * 重要性评分。
        *
        * <p>0.0 ~ 1.0 之间，由 AI 总结时评估。高重要性的记忆在搜索时优先返回。
         */
        Double importance,
        /**
        * 标签列表。
        *
        * <p>用于精确检索的标签，如 ["Java", "设计模式"]。
         */
        List<String> tags,
        /**
        * 扩展元数据。
         */
        Map<String, Object> metadata
) implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
    * 获取记忆 ID。
    *
    * @return 记忆 ID
     */
    public String getId() {
        return id;
    }

    /**
    * 获取记忆内容。
    *
    * @return 记忆内容
     */
    public String getContent() {
        return content;
    }

    /**
    * 获取记忆类型。
    *
    * @return 记忆类型
     */
    public String getType() {
        return type;
    }

    /**
    * 获取会话 ID。
    *
    * @return 会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
    * 获取 Agent 标识。
    *
    * @return Agent ID
     */
    public String getAgentId() {
        return agentId;
    }

    /**
    * 获取创建时间戳。
    *
    * @return 时间戳（毫秒）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
    * 获取重要性评分。
    *
    * @return 重要性评分
     */
    public Double getImportance() {
        return importance;
    }

    /**
    * 获取标签列表。
    *
    * @return 标签列表
     */
    public List<String> getTags() {
        return tags;
    }

    /**
    * 获取扩展元数据。
    *
    * @return 元数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
