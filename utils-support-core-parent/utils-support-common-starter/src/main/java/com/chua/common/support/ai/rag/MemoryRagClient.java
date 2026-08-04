package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.splitter.TextChunk;
import com.chua.common.support.ai.splitter.TextSplitter;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
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
import org.jspecify.annotations.NullUnmarked;

/**
 * 内存 RAG 客户端实现，用于测试和演示。
 * <p>
 * 基于内存向量存储和伪向量 Embedding，无需外部服务。
 * 仅用于功能验证，不适合生产环境。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@SuppressWarnings("NullAway")
@NullUnmarked
public class MemoryRagClient implements RagClient {

    private final RagClientSetting setting;
    private final VectorStorage vectorStorage;
    private final TextSplitter textSplitter;
    private VectorService vectorService;
    private final Path uploadDir;
    private final Path filesDir;
    private final List<RagDocument> documents;
    private int topK;
    private double similarityThreshold;

    public MemoryRagClient(RagClientSetting setting) {
        this.setting = setting;
        this.vectorStorage = setting.getVectorStorage();
        this.textSplitter = setting.getTextSplitter();
        this.vectorService = VectorService.from(setting.getEmbeddingClient());
        this.topK = setting.getTopK();
        this.similarityThreshold = setting.getSimilarityThreshold();

        this.uploadDir = Path.of(setting.getUploadDir());
        this.filesDir = this.uploadDir.resolve("files");
        try {
            Files.createDirectories(this.filesDir);
        } catch (IOException e) {
            throw new RuntimeException("创建上传目录失败: " + e.getMessage(), e);
        }
        this.documents = new CopyOnWriteArrayList<>();

        log.info("[MemoryRagClient] 初始化完成, uploadDir={}", setting.getUploadDir());
    }

    @Override
    public RagClient topK(int topK) {
        this.topK = topK;
        return this;
    }

    @Override
    public RagClient similarityThreshold(double threshold) {
        this.similarityThreshold = threshold;
        return this;
    }

    @Override
    public RagResponse query(String query) {
        return query(query, topK, similarityThreshold);
    }

    @Override
    public RagResponse query(String query, int topK, double similarityThreshold) {
        float[] queryVector = vectorService.embed(query);
        List<Vector> results = vectorStorage.search(queryVector, topK);

        List<RagResponse.Source> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        for (Vector v : results) {
            if (v.metadata() != null && v.metadata().containsKey("content")) {
                String content = (String) v.metadata().get("content");
                String docId = v.metadata().containsKey("docId") ? (String) v.metadata().get("docId") : v.id();
                sources.add(new RagResponse.Source(docId, content, 1.0, v.metadata()));
                context.append(content).append("\n\n");
            }
        }

        String prompt = "基于以下上下文回答问题。\n\n上下文:\n" + context + "\n\n问题: " + query;
        String answer = setting.getChatClient().chatSync(prompt);

        return new RagResponse(answer, sources, Map.of());
    }

    @Override
    public void queryStream(String query, java.util.function.Consumer<String> consumer) {
        RagResponse response = query(query);
        consumer.accept(response.answer());
    }

    @Override
    public void queryStream(String query, int topK, double similarityThreshold, java.util.function.Consumer<String> consumer) {
        RagResponse response = query(query, topK, similarityThreshold);
        consumer.accept(response.answer());
    }

    @Override
    public RagDocument uploadDocument(String fileName, byte[] data) {
        String docId = UUID.randomUUID().toString().replace("-", "");
        String fileType = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
        RagDocument doc = RagDocument.processing(docId, fileName, fileType, data.length);

        try {
            Path targetFile = filesDir.resolve(docId + "_" + fileName);
            Files.write(targetFile, data);
        } catch (IOException e) {
            RagDocument failed = doc.withError("保存文件失败: " + e.getMessage());
            documents.add(failed);
            return failed;
        }

        try {
            String text = new String(data, StandardCharsets.UTF_8);
            List<TextChunk> chunks = textSplitter.split(text);

            for (TextChunk chunk : chunks) {
                float[] vector = vectorService.embed(chunk.text());
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("content", chunk.text());
                metadata.put("docId", docId);
                metadata.put("fileName", fileName);
                vectorStorage.add(new Vector(docId + "_" + chunk.index(), vector, metadata));
            }

            doc = doc.withChunkCount(chunks.size());
        } catch (Exception e) {
            log.warn("[MemoryRagClient] 索引文档失败: {}", e.getMessage(), e);
            doc = doc.withError("索引失败: " + e.getMessage());
        }

        documents.add(doc);
        return doc;
    }

    @Override
    public boolean deleteDocument(String docId) {
        return documents.removeIf(d -> d.id().equals(docId));
    }

    @Override
    public List<RagDocument> listDocuments(int page, int pageSize) {
        List<RagDocument> sorted = documents.stream()
                .sorted((a, b) -> Long.compare(b.createTime(), a.createTime()))
                .collect(Collectors.toList());
        int from = (page - 1) * pageSize;
        if (from >= sorted.size()) return List.of();
        return sorted.subList(from, Math.min(from + pageSize, sorted.size()));
    }

    @Override
    public int documentCount() {
        return documents.size();
    }

    @Override
    public int reindex() {
        vectorStorage.clear();
        int count = 0;
        for (RagDocument doc : documents) {
            if (!"READY".equals(doc.status())) continue;
            try {
                Path file = filesDir.resolve(doc.id() + "_" + doc.fileName());
                if (Files.exists(file)) {
                    byte[] data = Files.readAllBytes(file);
                    String text = new String(data, StandardCharsets.UTF_8);
                    List<TextChunk> chunks = textSplitter.split(text);
                    for (TextChunk chunk : chunks) {
                        float[] vector = vectorService.embed(chunk.text());
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("content", chunk.text());
                        metadata.put("docId", doc.id());
                        vectorStorage.add(new Vector(doc.id() + "_" + chunk.index(), vector, metadata));
                    }
                    count++;
                }
            } catch (Exception e) {
                log.warn("[MemoryRagClient] 重新索引失败: {}", e.getMessage(), e);
            }
        }
        return count;
    }

    @Override
    public String readDocumentContent(String docId) {
        Optional<RagDocument> opt = documents.stream().filter(d -> d.id().equals(docId)).findFirst();
        if (opt.isEmpty()) return null;
        try {
            Path file = filesDir.resolve(docId + "_" + opt.get().fileName());
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    /**
     * 获取当前配置。
     * <p>
     * 返回的 setting 对象是可变的，调用方持有引用时修改会立即生效。
     * </p>
     *
     * @return 客户端配置
     */
    public RagClientSetting getSetting() {
        return setting;
    }

    /**
     * 运行时替换 ChatClient。
     * <p>
     * 直接修改内部 setting 对象，所有后续 query 调用将使用新的 ChatClient。
     * 调用方无需重新创建 RagClient 实例。
     * </p>
     *
     * @param chatClient 新的对话客户端
     */
    @Override
    public void setChatClient(ChatClient chatClient) {
        setting.setChatClient(chatClient);
    }

    /**
     * 运行时替换 EmbeddingClient。
     * <p>
     * 同时更新内部 VectorService 适配器，确保后续索引和查询使用新的向量化服务。
     * </p>
     *
     * @param embeddingClient 新的嵌入向量客户端
     */
    @Override
    public void setEmbeddingClient(EmbeddingClient embeddingClient) {
        setting.setEmbeddingClient(embeddingClient);
        this.vectorService = VectorService.from(embeddingClient);
    }

    @Override
    public void close() {
        log.info("[MemoryRagClient] 已关闭");
    }
}