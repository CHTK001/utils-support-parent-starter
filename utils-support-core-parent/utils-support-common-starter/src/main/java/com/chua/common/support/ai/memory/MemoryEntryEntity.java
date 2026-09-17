package com.chua.common.support.ai.memory;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 记忆条目可写实体 — 供 {@link EngineMemoryStore} / Engine ORM 使用。
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class MemoryEntryEntity {

    /**
    * 标识
    */
    private String id;
    /**
    * 内容
    */
    private String content;
    /**
    * 类型
    */
    private String type;
    /**
    * 会话 ID
    */
    private String sessionId;
    /**
    * Agent 标识
    */
    private String agentId;
    /**
    * 创建时间
    */
    private Long createdAt;
    /**
    * 重要性
    */
    private Double importance;
    /**
    * 检索标签
    */
    private List<String> tags;
    /**
    * 扩展元数据
    */
    private Map<String, Object> metadata;

    /** From */
    public static MemoryEntryEntity from(MemoryEntry entry) {
        if (entry == null) {
            return null;
        }
        MemoryEntryEntity e = new MemoryEntryEntity();
        e.id = entry.getId();
        e.content = entry.getContent();
        e.type = entry.getType();
        e.sessionId = entry.getSessionId();
        e.agentId = entry.getAgentId();
        e.createdAt = entry.getCreatedAt();
        e.importance = entry.getImportance();
        e.tags = entry.getTags() != null ? new ArrayList<>(entry.getTags()) : null;
        e.metadata = entry.getMetadata() != null ? new LinkedHashMap<>(entry.getMetadata()) : null;
        return e;
    }

    /** ToEntry */
    public MemoryEntry toEntry() {
        return MemoryEntry.builder()
                .id(id)
                .content(content)
                .type(type)
                .sessionId(sessionId)
                .agentId(agentId)
                .createdAt(createdAt != null ? createdAt : 0L)
                .importance(importance)
                .tags(tags)
                .metadata(metadata)
                .build();
    }
}
