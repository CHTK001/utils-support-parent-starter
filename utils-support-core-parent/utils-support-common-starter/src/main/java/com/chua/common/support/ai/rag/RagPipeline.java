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

@Slf4j
public class RagPipeline implements RagClient {

    private static final String NODE_SAVE = "save";
    private static final String NODE_EXTRACT = "extract";
    private static final String NODE_SPLIT = "split";
    private static final String NODE_EMBED = "embed";
    private static final String NODE_STORE = "store";
    private static final String NODE_COLLECT = "collect";
    private static final String NODE_END = "end";
    private static final String NODE_EMBED_QUERY = "embedQuery";
    private static final String NODE_SEARCH = "search";
    private static final String NODE_FILTER = "filter";
    private static final String NODE_GENERATE = "generate";

    private static final String UPLOAD_FILES_SUBDIR = "files";
    private static final String META_CONTENT = "content";
    private static final String META_DOC_ID = "docId";
    private static final String META_FILE_NAME = "fileName";
    private static final String META_FILE_TYPE = "fileType";
    private static final String META_CHUNK_INDEX = "chunkIndex";
    private static final String FILE_NAME_SEPARATOR = "_";
    private static final String PARAGRAPH_BREAK = "\n\n";
    private static final String CONTEXT_HEADER = "基于以下上下文回答问题。\n\n上下文:";
    private static final String QUESTION_PREFIX = "\n\n问题: ";
    private static final String STATUS_READY = "READY";
    private static final String UUID_DASH = "-";
    private static final String EMPTY = "";

    private final RagClientSetting setting;
    private final VectorStorage vectorStorage;
    private final TextSplitter textSplitter;
    private final VectorService vectorService;
    private final UploadProvider uploadProvider;
    private final Path uploadDir;
    private final Path filesDir;
    private final List<RagDocument> documents;
    /**
     * 分块内容缓存：chunkId（docId_chunkIndex）→ 分块文本。
     * jvector ON_DISK 等向量库不返回 metadata.content，检索后需从此缓存回读原文。
     */
    private final Map<String, String> chunkContentCache;
    private volatile int topK;
    private volatile double similarityThreshold;
    private final Pipeline ingestPipeline;
    private final Pipeline queryPipeline;

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

    private static RagContext current(PipelineContext<?> ctx) {
        return ctx.getAttribute("rag");
    }

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
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(META_CONTENT, chunks.get(i).text());
                metadata.put(META_DOC_ID, rc.docId());
                metadata.put(META_FILE_NAME, rc.fileName());
                metadata.put(META_FILE_TYPE, rc.fileType());
                metadata.put(META_CHUNK_INDEX, chunks.get(i).index());
                vectorStorage.add(new Vector(chunkId, vectors[i], metadata));
                // 记录分块内容，供检索后回读（jvector 等向量库不返回 metadata）
                chunkContentCache.put(chunkId, chunks.get(i).text());
            }
        }
        return chunks.size();
    }

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
                content = "";
            }
            Map<String, Object> meta = v.metadata() != null ? v.metadata() : new HashMap<>();
            sources.add(new RagResponse.Source(docId, content, score, meta));
        }
        return sources;
    }

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

    private String extractText(byte[] data, String fileName) {
        if (data == null) {
            return EMPTY;
        }
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
                log.warn("[RagPipeline] TextExtractor 抽取失败, 降级为 UTF-8 读取: {}", e.getMessage());
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private void runIngest(RagContext rc) {
        PipelineContext<?> ctx = new PipelineContext<>(ingestPipeline.getId(), (Object) null);
        ctx.setAttribute("rag", rc);
        ctx.setNextNodeId(NODE_SAVE);
        ingestPipeline.resume(ctx);
    }

    private void runQuery(RagContext rc) {
        PipelineContext<?> ctx = new PipelineContext<>(queryPipeline.getId(), (Object) null);
        ctx.setAttribute("rag", rc);
        ctx.setNextNodeId(NODE_EMBED_QUERY);
        queryPipeline.resume(ctx);
    }

    public Pipeline ingestPipeline() {
        return ingestPipeline;
    }

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
        vectorStorage.clear();
        chunkContentCache.clear();
        int count = 0;
        for (RagDocument doc : documents) {
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

    public static class Builder {
        private ChatClient chatClient;
        private EmbeddingClient embeddingClient;
        private TextExtractor textExtractor;
        private TextSplitter textSplitter;
        private VectorStorage vectorStorage;
        private String uploadDir = "./rag-uploads";
        private int topK = 5;
        private double similarityThreshold = 0.7;
        private String systemPrompt;

        public Builder chatClient(ChatClient chatClient) {
            this.chatClient = chatClient;
            return this;
        }

        public Builder embeddingClient(EmbeddingClient embeddingClient) {
            this.embeddingClient = embeddingClient;
            return this;
        }

        public Builder textExtractor(TextExtractor textExtractor) {
            this.textExtractor = textExtractor;
            return this;
        }

        public Builder textSplitter(TextSplitter textSplitter) {
            this.textSplitter = textSplitter;
            return this;
        }

        public Builder vectorStorage(VectorStorage vectorStorage) {
            this.vectorStorage = vectorStorage;
            return this;
        }

        public Builder uploadDir(String uploadDir) {
            this.uploadDir = uploadDir;
            return this;
        }

        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        public Builder similarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

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

    private static class RagContext {
        private String docId;
        private String fileName;
        private String fileType;
        private byte[] data;
        private String query;
        private int topK;
        private double threshold;

        private String currentFileId;
        private String currentText;
        private List<TextChunk> currentChunks;
        private int currentChunkCount;
        private String currentError;
        private float[] currentQueryVector;
        private List<Vector> currentResults;
        private List<RagResponse.Source> currentSources;
        private String currentAnswer;
        private RagResponse currentResponse;
        private boolean processing;
        private RagDocument currentDocument;

        public String docId() { return docId; }
        public void docId(String docId) { this.docId = docId; }
        public String fileName() { return fileName; }
        public void fileName(String fileName) { this.fileName = fileName; }
        public String fileType() { return fileType; }
        public void fileType(String fileType) { this.fileType = fileType; }
        public byte[] data() { return data; }
        public void data(byte[] data) { this.data = data; }
        public String query() { return query; }
        public void query(String query) { this.query = query; }
        public int topK() { return topK; }
        public void topK(int topK) { this.topK = topK; }
        public double threshold() { return threshold; }
        public void threshold(double threshold) { this.threshold = threshold; }
        public String currentFileId() { return currentFileId; }
        public void currentFileId(String currentFileId) { this.currentFileId = currentFileId; }
        public String currentText() { return currentText; }
        public void currentText(String currentText) { this.currentText = currentText; }
        public List<TextChunk> currentChunks() { return currentChunks; }
        public void currentChunks(List<TextChunk> currentChunks) { this.currentChunks = currentChunks; }
        public int currentChunkCount() { return currentChunkCount; }
        public void currentChunkCount(int currentChunkCount) { this.currentChunkCount = currentChunkCount; }
        public String currentError() { return currentError; }
        public void currentError(String currentError) { this.currentError = currentError; }
        public float[] currentQueryVector() { return currentQueryVector; }
        public void currentQueryVector(float[] currentQueryVector) { this.currentQueryVector = currentQueryVector; }
        public List<Vector> currentResults() { return currentResults; }
        public void currentResults(List<Vector> currentResults) { this.currentResults = currentResults; }
        public List<RagResponse.Source> currentSources() { return currentSources; }
        public void currentSources(List<RagResponse.Source> currentSources) { this.currentSources = currentSources; }
        public String currentAnswer() { return currentAnswer; }
        public void currentAnswer(String currentAnswer) { this.currentAnswer = currentAnswer; }
        public RagResponse currentResponse() { return currentResponse; }
        public void currentResponse(RagResponse currentResponse) { this.currentResponse = currentResponse; }
        public boolean processing() { return processing; }
        public void processing(boolean processing) { this.processing = processing; }
        public RagDocument currentDocument() { return currentDocument; }
        public void currentDocument(RagDocument currentDocument) { this.currentDocument = currentDocument; }
    }
}
