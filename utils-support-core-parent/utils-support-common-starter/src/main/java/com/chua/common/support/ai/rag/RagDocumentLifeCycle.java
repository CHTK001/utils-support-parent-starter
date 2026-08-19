package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.splitter.TextChunk;
import com.chua.common.support.ai.splitter.TextSplitter;
import com.chua.common.support.file.txtractor.TextExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * RAG 文档生命周期管理。
 * <p>
 * 管理文档的完整生命周期：上传 → 提取文本 → 分块 → 索引 → 删除 → 重索引。
 * 文档元数据以 JSON 格式持久化到本地文件系统。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RagDocumentLifeCycle implements AutoCloseable {

    /** JSON 对象映射器 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 数据库文件名称 */
    private static final String DB_FILE = "db.json";
    /** 文件存储目录 */
    private static final String FILES_DIR = "files";
    /** 文档列表类型引用 */
    private static final TypeReference<List<RagDocument>> DOC_LIST_TYPE = new TypeReference<>() {};

    /** 上传目录 */
    private final Path uploadDir;
    /** 文件存储目录 */
    private final Path filesDir;
    /** 数据库文件路径 */
    private final Path dbFile;
    /** 知识库客户端 */
    private final KnowledgeClient knowledgeClient;
    /** 文本分割器 */
    private final TextSplitter textSplitter;
    /** 文本提取器 */
    private final TextExtractor textExtractor;
    /** 文档列表 */
    private final List<RagDocument> documents;

    /**
     * 构造文档生命周期管理器。
     *
     * @param uploadDir       上传目录路径
     * @param knowledgeClient 向量知识库客户端
     * @param textSplitter    文本分块器
     * @param textExtractor   文本提取器（为 null 时纯文本直接读取）
     * @throws IOException 目录创建失败时抛出
     */
    public RagDocumentLifeCycle(String uploadDir, KnowledgeClient knowledgeClient,
                                TextSplitter textSplitter, TextExtractor textExtractor) throws IOException {
        this.uploadDir = Path.of(uploadDir);
        this.filesDir = this.uploadDir.resolve(FILES_DIR);
        this.dbFile = this.uploadDir.resolve(DB_FILE);
        this.knowledgeClient = knowledgeClient;
        this.textSplitter = textSplitter;
        this.textExtractor = textExtractor;

        Files.createDirectories(this.filesDir);
        this.documents = new CopyOnWriteArrayList<>(loadDb());

        log.info("[RagDocumentLifeCycle] 初始化完成, uploadDir={}, 文档数={}", uploadDir, this.documents.size());
    }

    // ==================== 文档上传 ====================

    /**
     * 上传文档：保存文件 → 提取文本 → 分块 → 索引。
     *
     * @param fileName 文件名
     * @param data     文件字节数据
     * @return 文档元数据
     */
    public RagDocument uploadDocument(String fileName, byte[] data) {
        String docId = UUID.randomUUID().toString().replace("-", "");
        String fileType = extractExtension(fileName);
        RagDocument doc = RagDocument.processing(docId, fileName, fileType, data.length);

        // 保存文件
        try {
            Path targetFile = filesDir.resolve(docId + "_" + fileName);
            Files.write(targetFile, data);
        } catch (IOException e) {
            RagDocument failed = doc.withError("保存文件失败: " + e.getMessage());
            addDocument(failed);
            return failed;
        }

        // 提取文本并索引
        try {
            String text = extractText(data, fileName);
            Map<String, Object> meta = new HashMap<>();
            meta.put("fileName", fileName);
            meta.put("fileType", fileType);
            meta.put("fileSize", data.length);
            meta.put("docId", docId);

            if (textSplitter != null && text != null) {
                List<TextChunk> chunks = textSplitter.split(text);
                knowledgeClient.upsert(docId, text, meta, chunks);
                doc = doc.withChunkCount(chunks.size());
            } else {
                knowledgeClient.upsert(docId, text != null ? text : new String(data, StandardCharsets.UTF_8), meta);
                doc = doc.withChunkCount(1);
            }
        } catch (Exception e) {
            log.warn("[RagDocumentLifeCycle] 索引文档失败: {}", e.getMessage(), e);
            doc = doc.withError("索引失败: " + e.getMessage());
        }

        addDocument(doc);
        return doc;
    }

    // ==================== 文档删除 ====================

    /**
     * 删除文档：从知识库移除 + 删除本地文件。
     *
     * @param docId 文档 ID
     * @return 是否成功
     */
    public boolean deleteDocument(String docId) {
        try {
            knowledgeClient.remove(docId);
        } catch (Exception e) {
            log.warn("[RagDocumentLifeCycle] 从知识库删除失败: {}", e.getMessage(), e);
        }

        Optional<RagDocument> opt = findDocument(docId);
        opt.ifPresent(doc -> {
            try {
                Path targetFile = filesDir.resolve(docId + "_" + doc.fileName());
                Files.deleteIfExists(targetFile);
            } catch (IOException e) {
                log.warn("[RagDocumentLifeCycle] 删除本地文件失败: {}", e.getMessage(), e);
            }
        });

        boolean removed = documents.removeIf(d -> d.id().equals(docId));
        if (removed) {
            saveDb();
            log.info("[RagDocumentLifeCycle] 文档已删除: {}", docId);
        }
        return removed;
    }

    // ==================== 文档查询 ====================

    /**
     * 分页查询文档列表（按创建时间倒序）。
     *
     * @param page     页码（从 1 开始）
     * @param pageSize 每页大小
     * @return 文档列表
     */
    public List<RagDocument> listDocuments(int page, int pageSize) {
        List<RagDocument> sorted = documents.stream()
                .sorted((a, b) -> Long.compare(b.createTime(), a.createTime()))
                .collect(Collectors.toList());
        int fromIndex = (page - 1) * pageSize;
        if (fromIndex >= sorted.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + pageSize, sorted.size());
        return sorted.subList(fromIndex, toIndex);
    }

    /**
     * 获取文档总数。
     *
     * @return 文档数量
     */
    public int documentCount() {
        return documents.size();
    }

    // ==================== 重新索引 ====================

    /**
     * 清空知识库 + 重新索引所有 READY 状态的文档。
     *
     * @return 重新索引的文档数量
     */
    public int reindex() {
        try {
            knowledgeClient.clear();
        } catch (Exception e) {
            log.warn("[RagDocumentLifeCycle] 清空知识库失败: {}", e.getMessage(), e);
        }

        int count = 0;
        for (RagDocument doc : documents) {
            if (!"READY".equals(doc.status())) {
                continue;
            }
            try {
                Path file = filesDir.resolve(doc.id() + "_" + doc.fileName());
                if (Files.exists(file)) {
                    byte[] data = Files.readAllBytes(file);
                    String text = extractText(data, doc.fileName());
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("fileName", doc.fileName());
                    meta.put("fileType", doc.fileType());
                    meta.put("docId", doc.id());

                    if (textSplitter != null && text != null) {
                        List<TextChunk> chunks = textSplitter.split(text);
                        knowledgeClient.upsert(doc.id(), text, meta, chunks);
                    } else {
                        knowledgeClient.upsert(doc.id(), text != null ? text : new String(data, StandardCharsets.UTF_8), meta);
                    }
                    count++;
                }
            } catch (Exception e) {
                log.warn("[RagDocumentLifeCycle] 重新索引失败: {} - {}", doc.id(), e.getMessage(), e);
            }
        }

        log.info("[RagDocumentLifeCycle] 重新索引完成, 成功 {} 个文档", count);
        return count;
    }

    // ==================== 文档内容 ====================

    /**
     * 读取文档原始内容（限 100KB）。
     *
     * @param docId 文档 ID
     * @return 文档内容，不存在则返回 null
     */
    public String readDocumentContent(String docId) {
        Optional<RagDocument> opt = findDocument(docId);
        if (opt.isEmpty()) {
            return null;
        }
        RagDocument doc = opt.get();
        Path file = filesDir.resolve(docId + "_" + doc.fileName());
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > 100_000) {
                content = content.substring(0, 100_000) + "\n... (内容过长已截断)";
            }
            return content;
        } catch (Exception e) {
            log.warn("[RagDocumentLifeCycle] 读取文档内容失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 根据 ID 查找文档。
     *
     * @param docId 文档 ID
     * @return 文档元数据
     */
    public Optional<RagDocument> findDocument(String docId) {
        return documents.stream().filter(d -> d.id().equals(docId)).findFirst();
    }

    // ==================== 内部方法 ====================

    /** 添加Document */
    private synchronized void addDocument(RagDocument doc) {
        documents.add(doc);
        saveDb();
    }

    /** 保存Db */
    private void saveDb() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(dbFile.toFile(), documents);
        } catch (IOException e) {
            log.warn("[RagDocumentLifeCycle] 持久化文档元数据失败: {}", e.getMessage(), e);
        }
    }

    /** 加载Db */
    private List<RagDocument> loadDb() {
        if (!Files.exists(dbFile)) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(dbFile.toFile(), DOC_LIST_TYPE);
        } catch (IOException e) {
            log.warn("[RagDocumentLifeCycle] 加载文档元数据失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /** ExtractText */
    private String extractText(byte[] data, String fileName) {
        if (textExtractor == null) {
            return new String(data, StandardCharsets.UTF_8);
        }
        try {
            Path tempFile = filesDir.resolve("_temp_" + fileName);
            Files.write(tempFile, data);
            String text = textExtractor.extractFullText(tempFile.toFile());
            Files.deleteIfExists(tempFile);
            return text;
        } catch (Exception e) {
            log.warn("[RagDocumentLifeCycle] 文本提取失败: {}, 降级为原始读取", e.getMessage());
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /** ExtractExtension */
    private static String extractExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx >= 0 ? fileName.substring(idx + 1).toLowerCase() : "";
    }

    @Override
    /** 关闭 */
    public void close() {
        saveDb();
        log.info("[RagDocumentLifeCycle] 已关闭");
    }
}