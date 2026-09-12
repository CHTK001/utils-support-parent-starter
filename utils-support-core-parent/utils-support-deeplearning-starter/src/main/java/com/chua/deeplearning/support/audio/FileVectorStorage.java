package com.chua.deeplearning.support.audio;

import com.chua.common.support.utils.MathUtils;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
* 基于本地文件的向量持久化存储。
*
* <p>解决 {@code MemoryVectorStorage} 重启丢失的问题：写入时同步落盘
* （JSON 数组格式），启动时懒加载恢复，实现真正的声纹"入库"。</p>
*
* <p>文件格式（{@code voiceprints.json}）：</p>
* <pre>{@code
* [ {"id": "speaker-A", "dim": 80, "data": [0.1, 0.2, ...]}, ... ]
* }</pre>
*
* <p>线程安全：读写锁保护；写操作全量重写文件（声纹库规模小，可接受）。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class FileVectorStorage implements VectorStorage {

    /**
    * 维度
     */
    private final int dimension;

    /**
    * 持久化文件路径
     */
    private final Path file;

    /**
    * 内存索引：标识 → 向量（链接哈希映射 保持插入序）
     */
    private final Map<String, float[]> store = new LinkedHashMap<>();

    /**
    * 读写锁
     */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
    * 文件向量storage。
    * @param dimension 维度
    * @param file 文件
     */
    private FileVectorStorage(int dimension, Path file) {
        this.dimension = dimension;
        this.file = file;
        load();
    }

    /**
    * 创建文件向量存储。
    *
    * @param dimension 向量维度
    * @param directory 持久化目录（自动创建）
    * @return 存储实例
     */
    public static FileVectorStorage create(int dimension, Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建声纹库目录: " + directory, e);
        }
        return new FileVectorStorage(dimension, directory.resolve("voiceprints.json"));
    }

    /** 启动时从磁盘恢复 */
    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(file.toFile());
            if (!root.isArray()) {
                return;
            }
            lock.writeLock().lock();
            try {
                for (JsonNode item : root) {
                    String id = item.path("id").asText();
                    JsonNode arr = item.path("data");
                    float[] vec = new float[arr.size()];
                    for (int i = 0; i < arr.size(); i++) {
                        vec[i] = arr.get(i).floatValue();
                    }
                    store.put(id, vec);
                }
            } finally {
                lock.writeLock().unlock();
            }
            log.info("[FileVectorStorage] 已恢复 {} 条声纹自 {}", store.size(), file);
        } catch (Exception e) {
            log.warn("[FileVectorStorage] 恢复失败（视为空库）: {}", e.getMessage());
        }
    }

    /** 全量落盘 */
    private void save() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode root = mapper.createArrayNode();
            for (Map.Entry<String, float[]> e : store.entrySet()) {
                ObjectNode item = root.addObject();
                item.put("id", e.getKey());
                item.put("dim", e.getValue().length);
                ArrayNode arr = item.putArray("data");
                for (float v : e.getValue()) {
                    arr.add(v);
                }
            }
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
        } catch (IOException e) {
            throw new IllegalStateException("声纹库写盘失败: " + file, e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public boolean add(String id, float[] vector) {
        checkDim(vector);
        lock.writeLock().lock();
        try {
            store.put(id, vector.clone());
            save();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = store.remove(id) != null;
            if (removed) {
                save();
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean update(String id, float[] vector) {
        checkDim(vector);
        lock.writeLock().lock();
        try {
            if (!store.containsKey(id)) {
                return false;
            }
            store.put(id, vector.clone());
            save();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Vector> search(float[] query, int topK) {
        lock.readLock().lock();
        try {
            List<Vector> scored = new ArrayList<>(store.size());
            for (Map.Entry<String, float[]> e : store.entrySet()) {
                double score = MathUtils.cosineSimilarity(query, e.getValue());
                Map<String, Object> meta = new HashMap<>();
                meta.put("score", score);
                scored.add(new Vector(e.getKey(), e.getValue(), meta));
            }
            scored.sort(Comparator.comparingDouble(
                    (Vector v) -> ((Number) v.metadata().get("score")).doubleValue()).reversed());
            return scored.subList(0, Math.min(topK, scored.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return store.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            store.clear();
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
    * 校验维度
    *
    * @param vector 向量
     */
    private void checkDim(float[] vector) {
        if (vector.length != dimension) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension + ", 实际 " + vector.length);
        }
    }


    /**
    * 关闭存储（写操作已实时落盘，此处仅标记）。
     */
    @Override
    public void close() {
        // 写操作已实时落盘
    }
}
