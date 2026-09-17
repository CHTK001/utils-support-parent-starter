package com.chua.common.support.ai.memory;

import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
* 基于工作间文件的记忆存储实现
*
* <p>将记忆条目以 JSON 文件形式存储在工作间目录下。
* 每条记忆一个 JSON 文件，按 ID 命名，便于增删改查。
*
* <p>目录结构：
* <pre>
* {workspace}/
* ├── memory/
* │   ├── {id1}.json
* │   ├── {id2}.json
* │   └── ...
* └── backup/
*     └── memory-{timestamp}.json
* </pre>
*
* @author CH
* @since 2026/07/16
 */
@Slf4j
public class FileMemoryStore implements MemoryStore {

    /** 配置对象 */
    private final MemoryConfig config;
    /** 内存存储目录 */
    private final Path memoryDir;

    /**
    * 创建 FileMemoryStore 实例
    * @param config config
    */
    public FileMemoryStore(MemoryConfig config) {
        this.config = config;
        this.memoryDir = Paths.get(config.getWorkspace(), "memory");
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            throw new RuntimeException("创建记忆目录失败: " + memoryDir, e);
        }
    }

    @Override
    /** 保存 */
    public void save(MemoryEntry entry) {
        String id = entry.getId();
        long createdAt = entry.getCreatedAt();
        String content = entry.getContent();
        var builder = entry.toBuilder();
        if (id == null) {
            builder.id(UUID.randomUUID().toString());
        }
        if (createdAt == 0) {
            builder.createdAt(System.currentTimeMillis());
        }
        if (content != null && content.length() > config.getMaxContentLength()) {
            builder.content(content.substring(0, config.getMaxContentLength()));
        }
        MemoryEntry finalEntry = builder.build();
        String json = Json.toJson(finalEntry);
        Path file = memoryDir.resolve(finalEntry.getId() + ".json");
        try {
            Files.writeString(file, json);
        } catch (IOException e) {
            throw new RuntimeException("保存记忆失败: " + finalEntry.getId(), e);
        }
        // 超出上限时淘汰最旧的
        evictIfNeeded();
    }

    @Override
    /** 搜索 */
    public List<MemoryEntry> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return listAll(limit);
        }
        String lowerKeyword = keyword.toLowerCase();
        return listAll(Integer.MAX_VALUE).stream()
                .filter(e -> e.getContent() != null && e.getContent().toLowerCase().contains(lowerKeyword))
                .sorted(Comparator.comparingDouble(MemoryEntry::importance).reversed()
                        .thenComparing(MemoryEntry::createdAt, Comparator.reverseOrder()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    /** ListByType */
    public List<MemoryEntry> listByType(String type, int limit) {
        return listAll(Integer.MAX_VALUE).stream()
                .filter(e -> type.equals(e.getType()))
                .sorted(Comparator.comparing(MemoryEntry::createdAt, Comparator.reverseOrder()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    /** ListBySession */
    public List<MemoryEntry> listBySession(String sessionId) {
        return listAll(Integer.MAX_VALUE).stream()
                .filter(e -> sessionId.equals(e.getSessionId()))
                .sorted(Comparator.comparing(MemoryEntry::createdAt, Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    @Override
    /** 删除 */
    public boolean delete(String id) {
        Path file = memoryDir.resolve(id + ".json");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    /** 计算数量 */
    public int count() {
        try {
            return (int) Files.list(memoryDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    /** Backup */
    public void backup(String backupPath) {
        try {
            Path target = Paths.get(backupPath);
            Files.createDirectories(target.getParent());
            List<MemoryEntry> all = listAll(Integer.MAX_VALUE);
            String json = Json.toJson(all);
            Files.writeString(target, json);
        } catch (IOException e) {
            throw new RuntimeException("备份记忆失败", e);
        }
    }

    @Override
    /** Restore */
    public void restore(String backupPath) {
        try {
            String json = Files.readString(Paths.get(backupPath));
            List<Map<String, Object>> list = Json.fromJson(json, List.class);
            for (Map<String, Object> map : list) {
                MemoryEntry entry = Json.fromJson(Json.toJson(map), MemoryEntry.class);
                save(entry);
            }
        } catch (IOException e) {
            throw new RuntimeException("恢复记忆失败", e);
        }
    }

    /** ListAll */
    private List<MemoryEntry> listAll(int limit) {
        List<MemoryEntry> result = new ArrayList<>();
        try {
            Files.list(memoryDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime(p); }
                        catch (IOException ex) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                    }, Comparator.reverseOrder()))
                    .limit(limit)
                    .forEach(p -> {
                        try {
                            String json = Files.readString(p);
                            result.add(Json.fromJson(json, MemoryEntry.class));
                        } catch (Exception e) {
                            log.warn("读取记忆文件失败: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("遍历记忆目录失败: {}", memoryDir, e);
        }
        return result;
    }

    /** EvictIfNeeded */
    private void evictIfNeeded() {
        int current = count();
        if (current <= config.getMaxEntries()) {
            return;
        }
        int toRemove = current - config.getMaxEntries();
        try {
            Files.list(memoryDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime(p); }
                        catch (IOException ex) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                    }))
                    .limit(toRemove)
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException e) { log.warn("淘汰记忆文件失败: {}", p, e); }
                    });
        } catch (IOException e) {
            log.warn("遍历记忆目录淘汰时失败", e);
        }
    }
}
