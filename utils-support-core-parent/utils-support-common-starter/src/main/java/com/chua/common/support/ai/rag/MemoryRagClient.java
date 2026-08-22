package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.rag.RagClient.UploadProvider;
import com.chua.common.support.ai.splitter.TextChunk;
import com.chua.common.support.ai.splitter.TextSplitter;
import com.chua.common.support.file.txtractor.TextExtractor;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 内存 RAG 客户端实现，用于测试和演示。
 *
 * <p>基于内存向量存储和伪向量 Embedding，无需外部服务。
 * 仅用于功能验证，不适合生产环境。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MemoryRagClient implements RagClient {

    /**
     * 上传目录下存放原始文件的子目录名
     */
    private static final String UPLOAD_FILES_SUBDIR = "files";

    /**
     * 向量元数据：文档正文内容键
     */
    private static final String META_CONTENT = "content";

    /**
     * 向量元数据：所属文档 ID 键
     */
    private static final String META_DOC_ID = "docId";

    /**
     * 向量元数据：原始文件名键
     */
    private static final String META_FILE_NAME = "fileName";

    /**
     * 向量元数据：文件类型（扩展名）键
     */
    private static final String META_FILE_TYPE = "fileType";

    /**
     * 向量元数据：分块序号键
     */
    private static final String META_CHUNK_INDEX = "chunkIndex";

    /**
     * 文件名拼接时 docId 与原文件名之间的分隔符
     */
    private static final String FILE_NAME_SEPARATOR = "_";

    /**
     * 段落分隔符（双换行）
     */
    private static final String PARAGRAPH_BREAK = "\n\n";

    /**
     * 检索得到的默认相似度（精确匹配）
     */
    private static final double DEFAULT_SIMILARITY = 1.0;

    /**
     * 文档状态：已就绪，可被重新索引
     */
    private static final String STATUS_READY = "READY";

    /**
     * UUID 中的连字符
     */
    private static final String UUID_DASH = "-";

    /**
     * UUID 替换连字符后的空字符串
     */
    private static final String EMPTY = "";

    /**
     * 检索上下文拼接的模板：标题 + 上下文 + 问题
     */
    private static final String CONTEXT_HEADER = "基于以下上下文回答问题。\n\n上下文:";

    /**
     * 问题前缀
     */
    private static final String QUESTION_PREFIX = "\n\n问题: ";

    /**
     * 文档上传 SPI 提供者，默认使用本地文件落盘。
     */
    private final UploadProvider uploadProvider;

    /**
     * 客户端配置
     */
    private final RagClientSetting setting;

    /**
     * 向量存储后端
     */
    private final VectorStorage vectorStorage;

    /**
     * 文本分块器
     */
    private final TextSplitter textSplitter;

    /**
     * 向量计算服务
     */
    private VectorService vectorService;

    /**
     * 上传文件根目录
     */
    private final Path uploadDir;

    /**
     * 已上传原始文件目录（{@code uploadDir/files}）
     */
    private final Path filesDir;

    /**
     * 文档状态列表
     */
    private final List<RagDocument> documents;

    /**
     * 检索返回的 Top K 数量
     */
    private int topK;

    /**
     * 检索相似度阈值
     */
    private double similarityThreshold;

    /**
     * 构造内存版 RAG 客户端。
     *
     * @param setting 客户端配置（非空）
     */
    public MemoryRagClient(RagClientSetting setting) {
        this.setting = setting;
        this.vectorStorage = setting.getVectorStorage();
        this.textSplitter = setting.getTextSplitter();
        this.vectorService = VectorService.from(setting.getEmbeddingClient());
        this.topK = setting.getTopK();
        this.similarityThreshold = setting.getSimilarityThreshold();
        this.uploadProvider = ServiceProvider.of(UploadProvider.class).getDefault();

        this.uploadDir = Path.of(setting.getUploadDir());
        this.filesDir = this.uploadDir.resolve(UPLOAD_FILES_SUBDIR);
        try {
            Files.createDirectories(this.filesDir);
        } catch (IOException e) {
            throw new RuntimeException("创建上传目录失败: " + e.getMessage(), e);
        }
        this.documents = new CopyOnWriteArrayList<>();

        log.info("[MemoryRagClient] 初始化完成, uploadDir={}, uploadProvider={}", setting.getUploadDir(), uploadProvider.getClass().getSimpleName());
    }

    /**
     * 设置 Top K。
     *
     * @param topK 检索返回的最大文档片段数
     * @return 当前客户端以支持链式调用
     */
    @Override
    public RagClient topK(int topK) {
        this.topK = topK;
        return this;
    }

    /**
     * 设置相似度阈值。
     *
     * @param threshold 相似度阈值，低于此值的向量被过滤
     * @return 当前客户端以支持链式调用
     */
    @Override
    public RagClient similarityThreshold(double threshold) {
        this.similarityThreshold = threshold;
        return this;
    }

    /**
     * 使用默认 Top K 与相似度阈值查询。
     *
     * @param query 用户查询文本
     * @return RAG 响应
     */
    @Override
    public RagResponse query(String query) {
        return query(query, topK, similarityThreshold);
    }

    /**
     * 查询 RAG：检索向量、拼接上下文并调用聊天客户端生成回答。
     *
     * @param query               用户查询文本
     * @param topK                检索返回的最大文档片段数
     * @param similarityThreshold 相似度阈值
     * @return RAG 响应（包含答案、来源片段与元数据）
     */
    @Override
    public RagResponse query(String query, int topK, double similarityThreshold) {
        float[] queryVector = vectorService.embed(query);
        List<Vector> results = vectorStorage.search(queryVector, topK);

        List<RagResponse.Source> sources = CollectionUtils.newArrayList();
        StringBuilder context = new StringBuilder();

        for (Vector v : results) {
            if (v.metadata() != null && v.metadata().containsKey(META_CONTENT)) {
                String content = (String) v.metadata().get(META_CONTENT);
                String docId = v.metadata().containsKey(META_DOC_ID)
                        ? (String) v.metadata().get(META_DOC_ID)
                        : v.id();
                sources.add(new RagResponse.Source(docId, content, DEFAULT_SIMILARITY, v.metadata()));
                context.append(content).append(PARAGRAPH_BREAK);
            }
        }

        String prompt = CONTEXT_HEADER + context + QUESTION_PREFIX + query;
        String systemPrompt = setting.getSystemPrompt();
        if (StringUtils.isNotBlank(systemPrompt)) {
            prompt = systemPrompt + PARAGRAPH_BREAK + prompt;
        }
        String answer = setting.getChatClient().chatSync(prompt);

        return new RagResponse(answer, sources, Map.of());
    }

    /**
     * 流式查询：当前实现退化为同步查询后通过 consumer 推回结果。
     *
     * @param query    用户查询文本
     * @param consumer 流式回调（接收完整答案）
     */
    @Override
    public void queryStream(String query, Consumer<String> consumer) {
        RagResponse response = query(query);
        consumer.accept(response.answer());
    }

    /**
     * 流式查询（带 Top K 与相似度阈值）：当前实现退化为同步查询。
     *
     * @param query               用户查询文本
     * @param topK                检索返回的最大文档片段数
     * @param similarityThreshold 相似度阈值
     * @param consumer            流式回调
     */
    @Override
    public void queryStream(String query, int topK, double similarityThreshold, Consumer<String> consumer) {
        RagResponse response = query(query, topK, similarityThreshold);
        consumer.accept(response.answer());
    }

    /**
     * 上传并索引一个文档。
     *
     * <p>流程：落盘 → 文本抽取 → 分块 → 向量化 → 入库。任意步骤失败都会在返回的
     * {@link RagDocument} 中以 {@code error} 字段记录错误信息。</p>
     *
     * @param fileName 文件名（带扩展名）
     * @param data     文件二进制内容
     * @return 文档状态对象（含 docId、状态、错误信息等）
     */
    @Override
    public RagDocument uploadDocument(String fileName, byte[] data) {
        String docId = UUID.randomUUID().toString().replace(UUID_DASH, EMPTY);
        String fileType = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : EMPTY;
        RagDocument doc = RagDocument.processing(docId, fileName, fileType, data.length);

        // 通过 SPI UploadProvider 上传（默认本地落盘，可切换云存储等实现）
        String fileId;
        try {
            fileId = uploadProvider.upload(docId, fileName, data);
        } catch (Exception e) {
            RagDocument failed = doc.withError("上传失败: " + e.getMessage());
            documents.add(failed);
            return failed;
        }

        try {
            // 从 UploadProvider 读取文件并提取文本
            byte[] fileData = uploadProvider.read(fileId);
            String text = extractText(fileData, fileName);
            if (StringUtils.isBlank(text)) {
                RagDocument failed = doc.withError("提取文本为空");
                documents.add(failed);
                return failed;
            }
            List<TextChunk> chunks = textSplitter.split(text);
            if (chunks.isEmpty()) {
                RagDocument failed = doc.withError("分块为空");
                documents.add(failed);
                return failed;
            }

            for (TextChunk chunk : chunks) {
                float[] vector = vectorService.embed(chunk.text());
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(META_CONTENT, chunk.text());
                metadata.put(META_DOC_ID, docId);
                metadata.put(META_FILE_NAME, fileName);
                metadata.put(META_FILE_TYPE, fileType);
                metadata.put(META_CHUNK_INDEX, chunk.index());
                vectorStorage.add(new Vector(docId + FILE_NAME_SEPARATOR + chunk.index(), vector, metadata));
            }

            doc = doc.withChunkCount(chunks.size());
        } catch (Exception e) {
            log.warn("[MemoryRagClient] 索引文档失败: {}", e.getMessage(), e);
            doc = doc.withError("索引失败: " + e.getMessage());
        }

        documents.add(doc);
        return doc;
    }

    /**
     * 抽取已上传文件的文本内容。
     *
     * <p>优先调用 setting 中注入的 TextExtractor（PDF/Word/Excel 等由其解析）；
     * 若未注入或抽取失败，则按 UTF-8 兜底读取文件内容。</p>
     *
     * @param data     文件字节数据
     * @param fileName 原始文件名（用于日志）
     * @return 抽取出的文本
     * @throws IOException 读取失败
     */
    private String extractText(byte[] data, String fileName) throws IOException {
        TextExtractor extractor = setting.getTextExtractor();
        if (extractor != null) {
            Path tempFile = filesDir.resolve("_temp_" + fileName);
            try {
                Files.write(tempFile, data);
                try {
                    return extractor.extractFullText(tempFile.toFile());
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception e) {
                log.warn("[MemoryRagClient] TextExtractor 抽取失败, 降级为 UTF-8 读取: {}", e.getMessage());
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 删除指定文档：同步清理向量存储和落盘文件。
     *
     * @param docId 文档 ID
     * @return 是否成功移除
     */
    @Override
    public boolean deleteDocument(String docId) {
        // 清理向量存储中该文档的所有分块
        try {
            vectorStorage.removeByIdPrefix(docId + FILE_NAME_SEPARATOR);
        } catch (Exception e) {
            log.warn("[MemoryRagClient] 清理向量失败: {}", e.getMessage());
        }

        // 通过 UploadProvider 删除上传文件
        try {
            uploadProvider.delete(docId);
        } catch (Exception e) {
            log.warn("[MemoryRagClient] 删除上传文件失败: {}", e.getMessage());
        }

        return documents.removeIf(d -> d.id().equals(docId));
    }

    /**
     * 分页列出文档，按创建时间倒序。
     *
     * @param page     页号（从 1 开始）
     * @param pageSize 每页大小
     * @return 当前页的文档列表
     */
    @Override
    public List<RagDocument> listDocuments(int page, int pageSize) {
        List<RagDocument> sorted = documents.stream()
                .sorted((a, b) -> Long.compare(b.createTime(), a.createTime()))
                .collect(Collectors.toList());
        int from = (page - 1) * pageSize;
        if (from >= sorted.size()) {
            return List.of();
        }
        return sorted.subList(from, Math.min(from + pageSize, sorted.size()));
    }

    /**
     * 获取已索引文档总数。
     *
     * @return 文档数
     */
    @Override
    public int documentCount() {
        return documents.size();
    }

    /**
     * 重新索引全部 READY 状态的文档：清空现有向量库并对每个文档重新向量化。
     *
     * @return 成功重新索引的文档数
     */
    @Override
    public int reindex() {
        vectorStorage.clear();
        int count = 0;
        for (RagDocument doc : documents) {
            if (!STATUS_READY.equals(doc.status())) {
                continue;
            }
            try {
                Path file = filesDir.resolve(doc.id() + FILE_NAME_SEPARATOR + doc.fileName());
                if (Files.exists(file)) {
                    String text = extractText(file.toFile(), doc.fileName());
                    if (StringUtils.isBlank(text)) {
                        continue;
                    }
                    List<TextChunk> chunks = textSplitter.split(text);
                    for (TextChunk chunk : chunks) {
                        float[] vector = vectorService.embed(chunk.text());
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put(META_CONTENT, chunk.text());
                        metadata.put(META_DOC_ID, doc.id());
                        metadata.put(META_FILE_NAME, doc.fileName());
                        metadata.put(META_FILE_TYPE, doc.fileType());
                        metadata.put(META_CHUNK_INDEX, chunk.index());
                        vectorStorage.add(new Vector(doc.id() + FILE_NAME_SEPARATOR + chunk.index(), vector, metadata));
                    }
                    count++;
                }
            } catch (Exception e) {
                log.warn("[MemoryRagClient] 重新索引失败: {}", e.getMessage(), e);
            }
        }
        return count;
    }

    /**
     * 读取指定文档的原始文件内容。
     *
     * @param docId 文档 ID
     * @return 文档文本；文档不存在或文件丢失时返回 {@code null}
     */
    @Override
    public String readDocumentContent(String docId) {
        Optional<RagDocument> opt = documents.stream().filter(d -> d.id().equals(docId)).findFirst();
        if (opt.isEmpty()) {
            return null;
        }
        try {
            Path file = filesDir.resolve(docId + FILE_NAME_SEPARATOR + opt.get().fileName());
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 获取客户端配置。
     *
     * @return 配置对象
     */
    @Override
    public RagClientSetting getSetting() {
        return setting;
    }

    /**
     * 注入聊天客户端。
     *
     * @param chatClient 聊天客户端实现
     */
    @Override
    public void setChatClient(ChatClient chatClient) {
        setting.setChatClient(chatClient);
    }

    /**
     * 注入 Embedding 客户端，并刷新内部的 {@link VectorService}。
     *
     * @param embeddingClient Embedding 客户端实现
     */
    @Override
    public void setEmbeddingClient(EmbeddingClient embeddingClient) {
        setting.setEmbeddingClient(embeddingClient);
        this.vectorService = VectorService.from(embeddingClient);
    }

    /**
     * 关闭客户端：当前实现为 no-op，保留以便未来扩展。
     */
    @Override
    public void close() {
        log.info("[MemoryRagClient] 已关闭");
    }
}
