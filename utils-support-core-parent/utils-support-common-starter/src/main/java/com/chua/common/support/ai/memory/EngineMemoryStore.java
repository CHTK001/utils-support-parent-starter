package com.chua.common.support.ai.memory;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 基于 {@link Engine} 接口的记忆存储（无反射、不依赖 datasource 实现包）。
 * <p>
 * 通过 {@link Engine#store(String, List)} / {@link Engine#query(Class)} 解耦。
 * 主副本在本类列表；同步写入 Engine 表名 {@link #TABLE}（与实体类名驼峰转下划线一致）。
 * 换模型时用 sessionId 读写，与具体 ChatClient 无关。
 * </p>
 *
 * <pre>{@code
 * MemoryManager mm = MemoryManager.ofEngine(engine);
 * mm.saveFromConversation(dialog, sessionId, "chat");
 * List&lt;MemoryEntry&gt; ctx = mm.listBySession(sessionId);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class EngineMemoryStore implements MemoryStore {

    /**
     * 与 {@link MemoryEntryEntity} 简单类名驼峰转下划线一致，供 Engine 表定位。
     */
    public static final String TABLE = "memory_entry_entity";

    /**
     * 引擎实例
    */
    private final Engine engine;
    /**
     * 配置对象
    */
    private final MemoryConfig config;
    /**
     * 内存行数据列表
    */
    private final List<MemoryEntryEntity> rows = new CopyOnWriteArrayList<>();

    /**
     * 创建 EngineMemoryStore 实例
     * @param engine engine
     * @param config MemoryConfig
     */
    public EngineMemoryStore(Engine engine, MemoryConfig config) {
        this.engine = engine;
        this.config = config != null ? config : MemoryConfig.builder().build();
        loadFromEngine();
    }

    /**
     * 加载FromEngine
    */
    private void loadFromEngine() {
        if (engine == null) {
            return;
        }
        try {
            List<MemoryEntryEntity> loaded = engine.query(MemoryEntryEntity.class).list();
            if (loaded == null || loaded.isEmpty()) {
                return;
            }
            rows.clear();
            rows.addAll(loaded);
        } catch (UnsupportedOperationException e) {
            log.debug("[EngineMemoryStore] query.list 未实现，仅内存: {}", e.getMessage());
        } catch (Exception e) {
            log.debug("[EngineMemoryStore] load skip: {}", e.getMessage());
        }
    }

    /**
     * SyncToEngine
    */
    private void syncToEngine() {
        if (engine == null) {
            return;
        }
        try {
            List<MemoryEntryEntity> snapshot = new ArrayList<>(rows);
            engine.store(TABLE, snapshot);
        } catch (Exception e) {
            log.debug("[EngineMemoryStore] store skip: {}", e.getMessage());
        }
    }

    @Override
    /**
     * 保存
    */
    public void save(MemoryEntry entry) {
        MemoryEntryEntity entity = MemoryEntryEntity.from(normalize(entry));
        rows.removeIf(r -> entity.getId() != null && entity.getId().equals(r.getId()));
        rows.add(entity);
        evictIfNeeded();
        syncToEngine();
    }

    /**
     * Normalize
     * @param entry 条目，不允许为 null
     * @return Memory条目 对象
     */
    private MemoryEntry normalize(MemoryEntry entry) {
        var b = entry.toBuilder();
        if (entry.getId() == null || entry.getId().isBlank()) {
            b.id(UUID.randomUUID().toString());
        }
        if (entry.getCreatedAt() == 0) {
            b.createdAt(System.currentTimeMillis());
        }
        String content = entry.getContent();
        if (content != null && content.length() > config.getMaxContentLength()) {
            b.content(content.substring(0, config.getMaxContentLength()));
        }
        return b.build();
    }

    @Override
    /**
     * 搜索
    */
    public List<MemoryEntry> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return listAll(limit);
        }
        String lower = keyword.toLowerCase();
        return rows.stream()
                .map(MemoryEntryEntity::toEntry)
                .filter(e -> e.getContent() != null && e.getContent().toLowerCase().contains(lower))
                .sorted(Comparator.comparingDouble((MemoryEntry e) ->
                                e.getImportance() != null ? e.getImportance() : 0.0)
                        .reversed()
                        .thenComparing(MemoryEntry::getCreatedAt, Comparator.reverseOrder()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    /**
     * ListByType
    */
    public List<MemoryEntry> listByType(String type, int limit) {
        return rows.stream()
                .map(MemoryEntryEntity::toEntry)
                .filter(e -> type != null && type.equals(e.getType()))
                .sorted(Comparator.comparing(MemoryEntry::getCreatedAt, Comparator.reverseOrder()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    /**
     * ListBySession
    */
    public List<MemoryEntry> listBySession(String sessionId) {
        return rows.stream()
                .map(MemoryEntryEntity::toEntry)
                .filter(e -> sessionId != null && sessionId.equals(e.getSessionId()))
                .sorted(Comparator.comparing(MemoryEntry::getCreatedAt, Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    @Override
    /**
     * 删除
    */
    public boolean delete(String id) {
        boolean removed = rows.removeIf(r -> id != null && id.equals(r.getId()));
        if (removed) {
            syncToEngine();
        }
        return removed;
    }

    @Override
    /**
     * 计算数量
    */
    public int count() {
        return rows.size();
    }

    @Override
    /**
     * Backup
    */
    public void backup(String backupPath) {
        try {
            Path target = Path.of(backupPath);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            String json = Json.toJson(
                    rows.stream().map(MemoryEntryEntity::toEntry).collect(Collectors.toList()));
            Files.writeString(target, json);
        } catch (Exception e) {
            throw new RuntimeException("EngineMemoryStore backup failed", e);
        }
    }

    @Override
    /**
     * Restore
    */
    public void restore(String backupPath) {
        try {
            String json = Files.readString(Path.of(backupPath));
            List<MemoryEntry> list = Json.fromJsonToList(json, MemoryEntry.class);
            if (list == null) {
                return;
            }
            rows.clear();
            for (MemoryEntry e : list) {
                rows.add(MemoryEntryEntity.from(normalize(e)));
            }
            syncToEngine();
        } catch (Exception e) {
            throw new RuntimeException("EngineMemoryStore restore failed", e);
        }
    }

    /**
     * ListAll
     * @param limit 上限，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private List<MemoryEntry> listAll(int limit) {
        return rows.stream()
                .map(MemoryEntryEntity::toEntry)
                .sorted(Comparator.comparing(MemoryEntry::getCreatedAt, Comparator.reverseOrder()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * EvictIfNeeded
    */
    private void evictIfNeeded() {
        int max = config.getMaxEntries();
        if (rows.size() <= max) {
            return;
        }
        List<MemoryEntryEntity> sorted = rows.stream()
                .sorted(Comparator.comparing(e -> e.getCreatedAt() != null ? e.getCreatedAt() : 0L))
                .collect(Collectors.toList());
        int toRemove = rows.size() - max;
        for (int i = 0; i < toRemove && i < sorted.size(); i++) {
            String id = sorted.get(i).getId();
            rows.removeIf(r -> id != null && id.equals(r.getId()));
        }
    }
}
