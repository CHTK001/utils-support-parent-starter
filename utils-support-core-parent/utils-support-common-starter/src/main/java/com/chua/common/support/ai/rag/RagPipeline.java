package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.splitter.TextChunk;
import com.chua.common.support.ai.splitter.TextSplitter;
import com.chua.common.support.file.txtractor.TextExtractor;
import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.MathUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * RAG 检索增强生成管道：将文档入库（保存→抽取→分块→嵌入→存储）与
 * 查询（嵌入→检索→过滤→生成）编排为 Pipeline 通用管线执行。
 *
 * <p>实现 {@link RagClient} 接口，供 Spring 配置层或独立 main 直接调用。
 * 图片文件走 OCR 提取器（由 {@link RagClientSetting#getTextExtractor()} 注入），
 * 非图片文件按扩展名经 {@link com.chua.common.support.file.txtractor.TextExtractor#auto}
 * 自动分发提取。向量存储支持任意 {@link VectorStorage} 实现
 * （内存、jvector、pgvector 等）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RagPipeline implements RagClient {

    /** 节点：保存上传文件 */
    private static final String NODE_SAVE = "save";
    /** 节点：文本抽取 */
    private static final String NODE_EXTRACT = "extract";
    /** 节点：文本分块 */
    private static final String NODE_SPLIT = "split";
    /** 节点：向量化嵌入 */
    private static final String NODE_EMBED = "embed";
    /** 节点：向量存储（已合并入 embed 节点，保留常量） */
    private static final String NODE_STORE = "store";
    /** 节点：结果收集 */
    private static final String NODE_COLLECT = "collect";
    /** 节点：结束 */
    private static final String NODE_END = "end";
    /** 节点：查询向量化 */
    private static final String NODE_EMBED_QUERY = "embedQuery";
    /** 节点：向量检索 */
    private static final String NODE_SEARCH = "search";
    /** 节点：相似度过滤 */
    private static final String NODE_FILTER = "filter";
    /** 节点：模型生成回答 */
    private static final String NODE_GENERATE = "generate";

    /** 上传文件子目录名 */
    private static final String UPLOAD_FILES_SUBDIR = "files";
    /** 向量元数据键：分块原文 */
    private static final String META_CONTENT = "content";
    /** 向量元数据键：文档 ID */
    private static final String META_DOC_ID = "docId";
    /** 向量元数据键：文件名 */
    private static final String META_FILE_NAME = "fileName";
    /** 向量元数据键：文件类型（扩展名） */
    private static final String META_FILE_TYPE = "fileType";
    /** 向量元数据键：分块序号 */
    private static final String META_CHUNK_INDEX = "chunkIndex";
    /** 文档 ID 与分块序号的拼接分隔符 */
    private static final String FILE_NAME_SEPARATOR = "_";
    /** 多段上下文的段落分隔符 */
    private static final String PARAGRAPH_BREAK = "\n\n";
    /** 生成提示词的上下文头部 */
    private static final String CONTEXT_HEADER = "基于以下上下文回答问题。\n\n上下文:";
    /** 生成提示词的问题前缀 */
    private static final String QUESTION_PREFIX = "\n\n问题: ";
    /** 文档处理状态：就绪 */
    private static final String STATUS_READY = "READY";
    /** UUID 字符串中的短横线 */
    private static final String UUID_DASH = "-";
    /** 空字符串常量 */
    private static final String EMPTY = "";
    /** 无匹配提取器时跳过 UTF-8 解码的文件大小上限（1MB） */
    private static final int MAX_TEXT_EXTRACT_BYTES = 1_048_576;

    /** RAG 客户端配置（含嵌入、分块、向量存储等组件引用） */
    private final RagClientSetting setting;
    /** 向量存储，负责分块向量的增删与检索 */
    private final VectorStorage vectorStorage;
    /** 文本分块器，将抽取文本切分为若干分块 */
    private final TextSplitter textSplitter;
    /** 向量服务（嵌入客户端的适配层） */
    private final VectorService vectorService;
    /** 上传文件提供者（本地落盘实现） */
    private final UploadProvider uploadProvider;
    /** 上传根目录 */
    private final Path uploadDir;
    /** 上传文件子目录（uploadDir/files） */
    private final Path filesDir;
    /** 已入库文档元数据列表（线程安全，按入库顺序追加） */
    private final List<RagDocument> documents;
    /**
     * 分块内容缓存：chunkId（docId_chunkIndex）→ 分块文本。
     * jvector ON_DISK 等向量库不返回 metadata.content，检索后需从此缓存回读原文。
     */
    private final Map<String, String> chunkContentCache;
    /** 查询返回的 TopK 数量（可热更新） */
    private volatile int topK;
    /** 查询相似度阈值（可热更新） */
    private volatile double similarityThreshold;
    /** 入库管线实例（保存→抽取→分块→嵌入→收集） */
    private final Pipeline ingestPipeline;
    /** 查询管线实例（嵌入→检索→过滤→生成→收集） */
    private final Pipeline queryPipeline;

    /**
     * 构造 RAG 管道并初始化文件目录与两条管线。
     *
     * @param setting RAG 客户端配置，不能为 null，必须包含嵌入客户端、分块器、向量存储与上传目录
     * @throws RuntimeException 当创建上传目录失败时抛出
     */
    public RagPipeline(RagClientSetting setting) {
        this.setting = setting;
        this.vectorStorage = setting.getVectorStorage();
        this.textSplitter = setting.getTextSplitter();
        this.vectorService = VectorService.from(setting.getEmbeddingClient());
        this.topK = setting.getTopK();
        this.similarityThreshold = setting.getSimilarityThreshold();
        this.uploadProvider = new LocalFileUploadProvider(setting.getUploadDir());
        this.uploadDir = Path.of(setting.getUploadDir());
        this.filesDir = this.uploadDir.resolve(UPLOAD_FILES_SUBDIR);
        try {
            Files.createDirectories(this.filesDir);
        } catch (IOException e) {
            throw new RuntimeException("创建上传目录失败: " + e.getMessage(), e);
        }
        this.documents = new CopyOnWriteArrayList<>();
        this.chunkContentCache = new ConcurrentHashMap<>();
        this.ingestPipeline = buildIngestPipeline();
        this.queryPipeline = buildQueryPipeline();
        log.info("[RagPipeline] 初始化完成, uploadDir={}", setting.getUploadDir());
    }

    /**
     * 创建 RAG 管道构建器。
     *
     * @return Builder 实例，不为 null
     */
    public static Builder builder() {
        return new Builder();
    }

    private Pipeline buildIngestPipeline() {
        return PipelineBuilder.newBuilder("rag-ingest")
                .task(NODE_SAVE, ctx -> {
                    RagContext rc = current(ctx);
                    if (rc.data() == null) {
                        rc.currentError("文件内容为空");
                        return null;
                    }
                    try {
                        String fileId = uploadProvider.upload(rc.docId(), rc.fileName(), rc.data());
                        rc.currentFileId(fileId);
                    } catch (Exception e) {
                        rc.currentError("上传失败: " + e.getMessage());
                    }
                    return null;
                }).taskEnd()
                .decision("hasFile", rc -> {
                    RagContext ctx = current(rc);
                    return ctx.currentError() != null ? NODE_COLLECT : null;
                })
                .task(NODE_EXTRACT, ctx -> {
                    RagContext rc = current(ctx);
                    try {
                        byte[] fileData = uploadProvider.read(rc.currentFileId());
                        String text = extractText(fileData, rc.fileName());
                        rc.currentText(text);
                    } catch (Exception e) {
                        rc.currentError("提取文本失败: " + e.getMessage());
                    }
                    return null;
                }).taskEnd()
                .decision("hasText", rc -> {
                    RagContext ctx = current(rc);
                    return ctx.currentError() != null || StringUtils.isBlank(ctx.currentText()) ? NODE_COLLECT : null;
                })
                .task(NODE_SPLIT, ctx -> {
                    RagContext rc = current(ctx);
                    try {
                        List<TextChunk> chunks = textSplitter.split(rc.currentText());
                        rc.currentChunks(chunks);
                    } catch (Exception e) {
                        rc.currentError("分块失败: " + e.getMessage());
                    }
                    return null;
                }).taskEnd()
                .decision("hasChunks", rc -> {
                    RagContext ctx = current(rc);
                    return ctx.currentChunks() == null || ctx.currentChunks().isEmpty() ? NODE_COLLECT : null;
                })
                .task(NODE_EMBED, ctx -> {
                    RagContext rc = current(ctx);
                    try {
                        int count = embedAndStore(rc);
                        rc.currentChunkCount(count);
                    } catch (Exception e) {
                        rc.currentError("向量化失败: " + e.getMessage());
                    }
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> {
                    RagContext rc = current(ctx);
                    RagDocument doc;
                    if (rc.currentError() != null) {
                        doc = RagDocument.processing(rc.docId(), rc.fileName(), rc.fileType(), rc.data() != null ? rc.data().length : 0)
                                .withError(rc.currentError());
                    } else if (StringUtils.isBlank(rc.currentText())) {
                        doc = RagDocument.processing(rc.docId(), rc.fileName(), rc.fileType(), rc.data() != null ? rc.data().length : 0)
                                .withError("提取文本为空");
                    } else if (rc.currentChunks() == null || rc.currentChunks().isEmpty()) {
                        doc = RagDocument.processing(rc.docId(), rc.fileName(), rc.fileType(), rc.data() != null ? rc.data().length : 0)
                                .withError("分块为空");
                    } else {
                        doc = RagDocument.processing(rc.docId(), rc.fileName(), rc.fileType(), rc.data().length)
                                .withChunkCount(rc.currentChunkCount());
                    }
                    documents.add(doc);
                    rc.currentDocument(doc);
                    return null;
                }).taskEnd()
                .task(NODE_END, ctx -> null)
                .end().taskEnd()
                .end(NODE_END)
                .build();
    }

    private Pipeline buildQueryPipeline() {
        return PipelineBuilder.newBuilder("rag-query")
                .task(NODE_EMBED_QUERY, ctx -> {
                    RagContext rc = current(ctx);
                    float[] queryVector = vectorService.embed(rc.query());
                    rc.currentQueryVector(queryVector);
                    return null;
                }).taskEnd()
                .decision("hasVector", rc -> {
                    RagContext ctx = current(rc);
                    return ctx.currentQueryVector() == null ? NODE_COLLECT : null;
                })
                .task(NODE_SEARCH, ctx -> {
                    RagContext rc = current(ctx);
                    List<Vector> results = vectorStorage.search(rc.currentQueryVector(), rc.topK());
                    rc.currentResults(results);
                    return null;
                }).taskEnd()
                .task(NODE_FILTER, ctx -> {
                    RagContext rc = current(ctx);
                    List<RagResponse.Source> sources = filterSources(rc);
                    rc.currentSources(sources);
                    return null;
                }).taskEnd()
                .task(NODE_GENERATE, ctx -> {
                    RagContext rc = current(ctx);
                    String prompt = buildPrompt(rc);
                    String answer = setting.getChatClient().chatSync(prompt);
                    rc.currentAnswer(answer);
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT, ctx -> {
                    RagContext rc = current(ctx);
                    RagResponse response = new RagResponse(rc.currentAnswer(), rc.currentSources(), Map.of());
                    rc.currentResponse(response);
                    return null;
                }).taskEnd()
                .task(NODE_END, ctx -> null)
                .end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 从 Pipeline 上下文取出 RAG 上下文。
     *
     * @param ctx Pipeline 上下文，不能为 null
     * @return RAG 上下文，不为 null
     */
    private static RagContext current(PipelineContext<?> ctx) {
        return ctx.getAttribute("rag");
    }

    /**
     * 将分块文本批量向量化并存入向量存储。
     * <p>批量嵌入失败时降级为逐个嵌入；单个分块嵌入失败时以空向量占位并跳过存储。</p>
     *
     * @param rc RAG 上下文，须已设置 docId/fileName/fileType/currentChunks
     * @return 实际入向量存储的分块数量
     */
    private int embedAndStore(RagContext rc) {
        List<TextChunk> chunks = rc.currentChunks();
        String[] texts = chunks.stream().map(TextChunk::text).toArray(String[]::new);
        float[][] vectors;
        try {
            vectors = vectorService.embedBatch(texts);
        } catch (Exception e) {
            log.warn("[RagPipeline] 批量向量化失败, 逐个向量化: {}", e.getMessage());
            vectors = new float[texts.length][];
            for (int i = 0; i < texts.length; i++) {
                try {
                    vectors[i] = vectorService.embed(texts[i]);
                } catch (Exception ex) {
                    log.warn("[RagPipeline] 向量化第{}个分块失败: {}", i, ex.getMessage());
                    vectors[i] = new float[0];
                }
            }
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (vectors[i] != null && vectors[i].length > 0) {
                String chunkId = rc.docId() + FILE_NAME_SEPARATOR + chunks.get(i).index();
                // 向量元数据：固定 5 键结构，用 record 承载（规约八：固定结构数据禁用 Map）
                VectorChunkMeta meta = new VectorChunkMeta(
                        chunks.get(i).text(),
                        rc.docId(),
                        rc.fileName(),
                        rc.fileType(),
                        chunks.get(i).index());
                Map<String, Object> metadata = new HashMap<>(8);
                metadata.put(META_CONTENT, meta.content());
                metadata.put(META_DOC_ID, meta.docId());
                metadata.put(META_FILE_NAME, meta.fileName());
                metadata.put(META_FILE_TYPE, meta.fileType());
                metadata.put(META_CHUNK_INDEX, meta.chunkIndex());
                vectorStorage.add(new Vector(chunkId, vectors[i], metadata));
                // 记录分块内容，供检索后回读（jvector 等向量库不返回 metadata）
                chunkContentCache.put(chunkId, meta.content());
            }
        }
        return chunks.size();
    }

    /**
     * 过滤检索结果，按相似度阈值筛选并回读分块原文。
     * <p>向量库不返回 metadata.content 时（jvector ON_DISK 等），从 {@link #chunkContentCache}
     * 以 chunkId 回读分块原文；metadata 缺失时内容回退为空字符串。</p>
     *
     * @param rc RAG 上下文，须已设置 queryVector/threshold/currentResults
     * @return 过滤后的来源列表（非 null），无命中时返回空列表
     */
    private List<RagResponse.Source> filterSources(RagContext rc) {
        List<RagResponse.Source> sources = CollectionUtils.newArrayList();
        for (Vector v : rc.currentResults()) {
            if (v.data() == null || v.data().length == 0) {
                continue;
            }
            double score = MathUtils.cosineSimilarity(rc.currentQueryVector(), v.data());
            if (score < rc.threshold()) {
                continue;
            }
            // 优先取向量库 metadata；jvector ON_DISK 不返回 metadata.content 时，
            // 用 v.id() 从内存缓存 chunkContentCache 回读分块原文
            String chunkId = v.id();
            String docId = (v.metadata() != null && v.metadata().containsKey(META_DOC_ID))
                    ? (String) v.metadata().get(META_DOC_ID) : chunkId;
            String content = (v.metadata() != null && v.metadata().containsKey(META_CONTENT))
                    ? (String) v.metadata().get(META_CONTENT) : chunkContentCache.get(chunkId);
            if (content == null) {
                content = EMPTY;
            }
            Map<String, Object> meta = v.metadata() != null ? v.metadata() : new HashMap<>(8);
            sources.add(new RagResponse.Source(docId, content, score, meta));
        }
        return sources;
    }

    /**
     * 构建发送给模型的生成提示词（系统提示 + 上下文 + 问题）。
     *
     * @param rc RAG 上下文，须已设置 query/currentSources
     * @return 完整提示词字符串，不为 null
     */
    private String buildPrompt(RagContext rc) {
        StringBuilder context = new StringBuilder();
        for (RagResponse.Source source : rc.currentSources()) {
            context.append(source.content()).append(PARAGRAPH_BREAK);
        }
        String prompt = CONTEXT_HEADER + context + QUESTION_PREFIX + rc.query();
        String systemPrompt = setting.getSystemPrompt();
        if (StringUtils.isNotBlank(systemPrompt)) {
            prompt = systemPrompt + PARAGRAPH_BREAK + prompt;
        }
        return prompt;
    }

    /**
     * 从文件字节抽取纯文本。
     * <p>图片文件走配置的 OCR 提取器；非图片文件按扩展名经 {@link TextExtractor#auto}
     * 自动分发；均无匹配提取器时降级为 UTF-8 直读，超过 1MB 的无提取器文件跳过解码避免 OOM。</p>
     *
     * @param data   文件字节，null 时返回空字符串
     * @param fileName 文件名，null 时按非图片处理
     * @return 抽取出的纯文本，无法抽取时返回空字符串（不返回 null）
     */
    private String extractText(byte[] data, String fileName) {
        if (data == null) {
            return EMPTY;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase();
        TextExtractor extractor;
        if (isImage(lower)) {
            // 图片：使用 OCR 提取器（检测器+识别器）
            extractor = setting.getTextExtractor();
        } else {
            // 非图片：按扩展名自动分发（pdf/docx/xlsx/csv/txt），无匹配时为空
            try {
                extractor = TextExtractor.auto(tempFileOf(data, fileName));
            } catch (Exception e) {
                extractor = null;
            }
        }
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
                log.warn("[RagPipeline] TextExtractor 抽取失败, 降级为 UTF-8 读取: {}", e.getMessage());
            }
        }
        // 大文件（>1MB）无合适提取器时，跳过 UTF-8 解码以避免 OOM（二进制乱码产生海量无意义分块）
        if (exceedsExtractLimit(data)) {
            log.warn("[RagPipeline] 文件 {} 超过 1MB 且无匹配提取器, 跳过文本抽取", fileName);
            return EMPTY;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 判断是否为图片文件。
     *
     * @param name 文件名（小写），不能为 null
     * @return true 表示为受支持的图片格式（jpg/jpeg/png/bmp/webp/gif）
     */
    private static boolean isImage(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".bmp") || name.endsWith(".webp") || name.endsWith(".gif");
    }

    /**
     * 使用 1MB 上限判断无提取器时是否跳过 UTF-8 解码，避免二进制乱码产生海量无效分块导致 OOM。
     *
     * @param data 文件字节，不能为 null
     * @return true 表示文件超过 1MB，应跳过 UTF-8 解码
     */
    private boolean exceedsExtractLimit(byte[] data) {
        return data.length > MAX_TEXT_EXTRACT_BYTES;
    }

    /**
     * 生成临时文件路径用于扩展名识别（文件内容不实际写入，仅借用扩展名）。
     *
     * @param data     文件字节，本方法不使用，仅保留签名对称
     * @param fileName 文件名，决定扩展名
     * @return 临时文件路径（filesDir/_temp_<fileName>）
     */
    private java.io.File tempFileOf(byte[] data, String fileName) {
        return filesDir.resolve("_temp_" + fileName).toFile();
    }

    /**
     * 执行入库管线。
     *
     * @param rc RAG 上下文，须已设置 docId/fileName/fileType/data
     */
    private void runIngest(RagContext rc) {
        PipelineContext<?> ctx = new PipelineContext<>(ingestPipeline.getId(), (Object) null);
        ctx.setAttribute("rag", rc);
        ctx.setNextNodeId(NODE_SAVE);
        ingestPipeline.resume(ctx);
    }

    /**
     * 执行查询管线。
     *
     * @param rc RAG 上下文，须已设置 query/topK/threshold
     */
    private void runQuery(RagContext rc) {
        PipelineContext<?> ctx = new PipelineContext<>(queryPipeline.getId(), (Object) null);
        ctx.setAttribute("rag", rc);
        ctx.setNextNodeId(NODE_EMBED_QUERY);
        queryPipeline.resume(ctx);
    }

    /**
     * 获取入库管线（供外部观测或测试）。
     *
     * @return 入库 Pipeline 实例，不为 null
     */
    public Pipeline ingestPipeline() {
        return ingestPipeline;
    }

    /**
     * 获取查询管线（供外部观测或测试）。
     *
     * @return 查询 Pipeline 实例，不为 null
     */
    public Pipeline queryPipeline() {
        return queryPipeline;
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
    public RagClient system(String system) {
        return this;
    }

    @Override
    public RagClient temperature(double temperature) {
        return this;
    }

    @Override
    public RagClient maxTokens(int maxTokens) {
        return this;
    }

    @Override
    public RagResponse query(String query) {
        return query(query, topK, similarityThreshold);
    }

    @Override
    public RagResponse query(String query, int topK, double similarityThreshold) {
        RagContext rc = new RagContext();
        rc.query(query);
        rc.topK(topK);
        rc.threshold(similarityThreshold);
        runQuery(rc);
        return rc.currentResponse();
    }

    @Override
    public void queryStream(String query, Consumer<String> consumer) {
        queryStream(query, topK, similarityThreshold, consumer);
    }

    @Override
    public void queryStream(String query, int topK, double similarityThreshold, Consumer<String> consumer) {
        RagResponse response = query(query, topK, similarityThreshold);
        consumer.accept(response.answer());
    }

    @Override
    public RagDocument uploadDocument(String fileName, byte[] data) {
        String docId = UUID.randomUUID().toString().replace(UUID_DASH, EMPTY);
        String fileType = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : EMPTY;
        RagContext rc = new RagContext();
        rc.docId(docId);
        rc.fileName(fileName);
        rc.fileType(fileType);
        rc.data(data);
        runIngest(rc);
        return rc.currentDocument();
    }

    @Override
    public RagDocument updateDocument(String docId, String fileName, byte[] data) {
        deleteDocument(docId);
        RagContext rc = new RagContext();
        rc.docId(docId);
        rc.fileName(fileName);
        String fileType = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : EMPTY;
        rc.fileType(fileType);
        rc.data(data);
        runIngest(rc);
        return rc.currentDocument();
    }

    @Override
    public boolean deleteDocument(String docId) {
        try {
            vectorStorage.removeByIdPrefix(docId + FILE_NAME_SEPARATOR);
        } catch (Exception e) {
            log.warn("[RagPipeline] 清理向量失败: {}", e.getMessage());
        }
        try {
            uploadProvider.delete(docId);
        } catch (Exception e) {
            log.warn("[RagPipeline] 删除上传文件失败: {}", e.getMessage());
        }
        // 清理分块内容缓存
        chunkContentCache.keySet().removeIf(k -> k.startsWith(docId + FILE_NAME_SEPARATOR));
        return documents.removeIf(d -> d.id().equals(docId));
    }

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

    @Override
    public int documentCount() {
        return documents.size();
    }

    @Override
    public int reindex() {
        // 快照迭代前文档列表，避免 ingest 的 collect 节点向 documents 追加导致
        // "ConcurrentModificationException: arraycopy during iteration"
        List<RagDocument> snapshot = new java.util.ArrayList<>(documents);
        vectorStorage.clear();
        chunkContentCache.clear();
        documents.clear();
        int count = 0;
        for (RagDocument doc : snapshot) {
            if (!STATUS_READY.equals(doc.status())) {
                continue;
            }
            try {
                RagContext rc = new RagContext();
                rc.docId(doc.id());
                rc.fileName(doc.fileName());
                rc.fileType(doc.fileType());
                byte[] fileData = uploadProvider.read(doc.id());
                rc.data(fileData);
                runIngest(rc);
                count++;
            } catch (Exception e) {
                log.warn("[RagPipeline] 重新索引失败: {}", e.getMessage(), e);
            }
        }
        return count;
    }

    @Override
    public String readDocumentContent(String docId) {
        Optional<RagDocument> opt = documents.stream().filter(d -> d.id().equals(docId)).findFirst();
        if (opt.isEmpty()) {
            return null;
        }
        try {
            byte[] data = uploadProvider.read(docId);
            return data != null ? new String(data, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public RagClientSetting getSetting() {
        return setting;
    }

    @Override
    public void setChatClient(ChatClient chatClient) {
        setting.setChatClient(chatClient);
    }

    @Override
    public void setEmbeddingClient(EmbeddingClient embeddingClient) {
        setting.setEmbeddingClient(embeddingClient);
    }

    @Override
    public void close() {
        log.info("[RagPipeline] 已关闭");
    }

    /**
     * RAG 管道构建器，链式配置各组件后 {@link #build()} 生成 {@link RagPipeline}。
     *
     * <p>未显式设置的字段采用默认值：uploadDir 为 {@code ./rag-uploads}，
     * topK 为 5，similarityThreshold 为 0.7。</p>
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static class Builder {
        /** 对话生成模型客户端 */
        private ChatClient chatClient;
        /** 嵌入向量模型客户端 */
        private EmbeddingClient embeddingClient;
        /** 图片 OCR 文本提取器（可选，非图片文件不使用） */
        private TextExtractor textExtractor;
        /** 文本分块器 */
        private TextSplitter textSplitter;
        /** 向量存储 */
        private VectorStorage vectorStorage;
        /** 上传文件根目录，默认 ./rag-uploads */
        private String uploadDir = "./rag-uploads";
        /** 查询 TopK，默认 5 */
        private int topK = 5;
        /** 查询相似度阈值，默认 0.7 */
        private double similarityThreshold = 0.7;
        /** 系统提示词（可选） */
        private String systemPrompt;

        /**
         * 设置对话生成模型客户端。
         *
         * @param chatClient 客户端实例
         * @return 当前构建器
         */
        public Builder chatClient(ChatClient chatClient) {
            this.chatClient = chatClient;
            return this;
        }

        /**
         * 设置嵌入向量模型客户端。
         *
         * @param embeddingClient 客户端实例
         * @return 当前构建器
         */
        public Builder embeddingClient(EmbeddingClient embeddingClient) {
            this.embeddingClient = embeddingClient;
            return this;
        }

        /**
         * 设置图片 OCR 文本提取器（仅图片文件使用）。
         *
         * @param textExtractor 提取器实例，可为 null（非图片场景）
         * @return 当前构建器
         */
        public Builder textExtractor(TextExtractor textExtractor) {
            this.textExtractor = textExtractor;
            return this;
        }

        /**
         * 设置文本分块器。
         *
         * @param textSplitter 分块器实例
         * @return 当前构建器
         */
        public Builder textSplitter(TextSplitter textSplitter) {
            this.textSplitter = textSplitter;
            return this;
        }

        /**
         * 设置向量存储。
         *
         * @param vectorStorage 存储实例
         * @return 当前构建器
         */
        public Builder vectorStorage(VectorStorage vectorStorage) {
            this.vectorStorage = vectorStorage;
            return this;
        }

        /**
         * 设置上传文件根目录。
         *
         * @param uploadDir 目录路径
         * @return 当前构建器
         */
        public Builder uploadDir(String uploadDir) {
            this.uploadDir = uploadDir;
            return this;
        }

        /**
         * 设置查询 TopK。
         *
         * @param topK 返回的最大来源数量，最小 1
         * @return 当前构建器
         */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * 设置查询相似度阈值。
         *
         * @param similarityThreshold 阈值 0~1，低于该值的检索结果被过滤
         * @return 当前构建器
         */
        public Builder similarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        /**
         * 设置系统提示词（可选）。
         *
         * @param systemPrompt 系统提示词，null 表示不附加
         * @return 当前构建器
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * 构建 RAG 管道实例。
         *
         * @return 配置完成的 {@link RagPipeline} 实例，不为 null
         */
        public RagPipeline build() {
            RagClientSetting setting = RagClientSetting.builder()
                    .chatClient(chatClient)
                    .embeddingClient(embeddingClient)
                    .textExtractor(textExtractor)
                    .textSplitter(textSplitter)
                    .vectorStorage(vectorStorage)
                    .uploadDir(uploadDir)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .systemPrompt(systemPrompt)
                    .build();
            return new RagPipeline(setting);
        }
    }

    /**
     * RAG 管线内部上下文：贯穿入库/查询各节点的临时数据载体。
     * <p>字段均为「当前节点写入、后续节点读取」的流水线状态，方法名即字段名（JavaBean 风格）。</p>
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static class RagContext {
        /** 文档 ID（入库节点写入） */
        private String docId;
        /** 文件名 */
        private String fileName;
        /** 文件类型（扩展名） */
        private String fileType;
        /** 文件原始字节 */
        private byte[] data;
        /** 查询语句 */
        private String query;
        /** 查询 TopK */
        private int topK;
        /** 查询相似度阈值 */
        private double threshold;

        /** 当前保存后的文件 ID */
        private String currentFileId;
        /** 当前抽取出的纯文本 */
        private String currentText;
        /** 当前分块列表 */
        private List<TextChunk> currentChunks;
        /** 当前入库的分块数量 */
        private int currentChunkCount;
        /** 当前节点记录的错误信息（null 表示无错误） */
        private String currentError;
        /** 当前查询语句的向量 */
        private float[] currentQueryVector;
        /** 当前检索命中的向量列表 */
        private List<Vector> currentResults;
        /** 当前过滤后的来源列表 */
        private List<RagResponse.Source> currentSources;
        /** 当前模型生成的回答 */
        private String currentAnswer;
        /** 当前组装的查询响应 */
        private RagResponse currentResponse;
        /** 是否处理中（预留） */
        private boolean processing;
        /** 当前入库的文档元数据 */
        private RagDocument currentDocument;

        /** 获取文档 ID */
        public String docId() { return docId; }
        /** 设置文档 ID */
        public void docId(String docId) { this.docId = docId; }
        /** 获取文件名 */
        public String fileName() { return fileName; }
        /** 设置文件名 */
        public void fileName(String fileName) { this.fileName = fileName; }
        /** 获取文件类型 */
        public String fileType() { return fileType; }
        /** 设置文件类型 */
        public void fileType(String fileType) { this.fileType = fileType; }
        /** 获取文件字节 */
        public byte[] data() { return data; }
        /** 设置文件字节 */
        public void data(byte[] data) { this.data = data; }
        /** 获取查询语句 */
        public String query() { return query; }
        /** 设置查询语句 */
        public void query(String query) { this.query = query; }
        /** 获取 TopK */
        public int topK() { return topK; }
        /** 设置 TopK */
        public void topK(int topK) { this.topK = topK; }
        /** 获取相似度阈值 */
        public double threshold() { return threshold; }
        /** 设置相似度阈值 */
        public void threshold(double threshold) { this.threshold = threshold; }
        /** 获取文件 ID */
        public String currentFileId() { return currentFileId; }
        /** 设置文件 ID */
        public void currentFileId(String currentFileId) { this.currentFileId = currentFileId; }
        /** 获取抽取文本 */
        public String currentText() { return currentText; }
        /** 设置抽取文本 */
        public void currentText(String currentText) { this.currentText = currentText; }
        /** 获取分块列表 */
        public List<TextChunk> currentChunks() { return currentChunks; }
        /** 设置分块列表 */
        public void currentChunks(List<TextChunk> currentChunks) { this.currentChunks = currentChunks; }
        /** 获取入库分块数 */
        public int currentChunkCount() { return currentChunkCount; }
        /** 设置入库分块数 */
        public void currentChunkCount(int currentChunkCount) { this.currentChunkCount = currentChunkCount; }
        /** 获取错误信息 */
        public String currentError() { return currentError; }
        /** 设置错误信息 */
        public void currentError(String currentError) { this.currentError = currentError; }
        /** 获取查询向量 */
        public float[] currentQueryVector() { return currentQueryVector; }
        /** 设置查询向量 */
        public void currentQueryVector(float[] currentQueryVector) { this.currentQueryVector = currentQueryVector; }
        /** 获取检索命中向量 */
        public List<Vector> currentResults() { return currentResults; }
        /** 设置检索命中向量 */
        public void currentResults(List<Vector> currentResults) { this.currentResults = currentResults; }
        /** 获取过滤后来源 */
        public List<RagResponse.Source> currentSources() { return currentSources; }
        /** 设置过滤后来源 */
        public void currentSources(List<RagResponse.Source> currentSources) { this.currentSources = currentSources; }
        /** 获取模型回答 */
        public String currentAnswer() { return currentAnswer; }
        /** 设置模型回答 */
        public void currentAnswer(String currentAnswer) { this.currentAnswer = currentAnswer; }
        /** 获取查询响应 */
        public RagResponse currentResponse() { return currentResponse; }
        /** 设置查询响应 */
        public void currentResponse(RagResponse currentResponse) { this.currentResponse = currentResponse; }
        /** 获取处理中标志 */
        public boolean processing() { return processing; }
        /** 设置处理中标志 */
        public void processing(boolean processing) { this.processing = processing; }
        /** 获取入库文档元数据 */
        public RagDocument currentDocument() { return currentDocument; }
        /** 设置入库文档元数据 */
        public void currentDocument(RagDocument currentDocument) { this.currentDocument = currentDocument; }
    }

    /**
     * 向量分块元数据：随分块向量一同存入向量库的固定 5 键结构。
     *
     * <p>用 record 承载（规约八：固定结构数据禁用 Map）；写库时展开为
     * {@code Map<String,Object>} 以匹配 {@link VectorStorage} 的通用元数据签名。</p>
     *
     * @param content    分块原文
     * @param docId      文档 ID
     * @param fileName   文件名
     * @param fileType   文件类型（扩展名）
     * @param chunkIndex 分块序号
     * @author CH
     * @since 4.0.0.42
     */
    private record VectorChunkMeta(
            String content,
            String docId,
            String fileName,
            String fileType,
            int chunkIndex
    ) {
    }
}
