package com.chua.deeplearning.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Path;

/**
 * 抽象识别引擎。
 * <p>通过 SPI 自动发现 {@link ModelProvider} 实现，构建模型注册表，
 * 支持按名称和类型查找模型实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractIdentificationEngine implements IdentificationEngine {

    /**
     * 模型名称到定义的映射
     */
    protected final Map<String, TranslatorModelDefinition> modelMap = new ConcurrentHashMap<>();

    /**
     * 模型最近一次被取用的时间戳（毫秒）：modelId → lastUsed。
     *
     * <p>仅用于空闲卸载判断；不参与任何推理逻辑。</p>
     */
    private final Map<String, Long> lastUsedAt = new ConcurrentHashMap<>();

    /**
     * 空闲卸载阈值（毫秒）：模型超过该时长未被取用即释放其推理会话。
     *
     * <p>取值 {@code deeplearning.model.idle-unload-ms}，默认 10 分钟；
     * 设为 0 或负数可关闭该能力。</p>
     *
     * <p>为什么需要：CV 模型（如超分/上色/detection）的 ONNX 会话常驻数百 MB
     * 到数 GB 的原生内存，且 ONNX Runtime 的 CPU arena 不会把内存还给操作系统，
     * 空闲后 RSS 依然高企（实测上色模型加载后常驻约 3GB）。释放后下一次调用会
     * 自动重新加载（{@code DjlModelFactory.predict → ensureInitialized}），
     * 代价只是首个请求的加载延迟，由前端加载动画覆盖。</p>
     */
    private static final long IDLE_UNLOAD_MS =
            Long.getLong("deeplearning.model.idle-unload-ms", 600_000L);

    /**
     * 空闲检查周期（毫秒）：取值 {@code deeplearning.model.idle-check-ms}，默认 60 秒。
     */
    private static final long IDLE_CHECK_MS =
            Long.getLong("deeplearning.model.idle-check-ms", 60_000L);

    /**
     * 空闲回收线程是否已启动（进程内只需一个）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean REAPER_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 默认 提供者 名称
     */
    private static final String DEFAULT_PROVIDER = "onnx";

    /**
     * 提供者 名称：pytorch
     */
    private static final String PROVIDER_PYTORCH = "pytorch";

    /**
     * 提供者 名称：Safetensors
     */
    private static final String PROVIDER_SAFETENSORS = "safetensors";

    /**
     * 提供者 名称：PaddlePaddle
     */
    private static final String PROVIDER_PADDLE = "paddle";

    /**
     * 提供者 名称：tensor流
     */
    private static final String PROVIDER_TENSORFLOW = "tensorflow";

    /**
     * 类路径 前缀
     */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /**
     * 构造引擎，自动执行 SPI 模型发现。
     */
    static {
        ServiceProvider.CACHE.clear();
    }

    /**
     * 创建 抽象identificationengine 实例
    */
    public AbstractIdentificationEngine() {
        ImageUtils.load();
        discoverModels();
        startIdleReaper();
    }

    /**
     * 启动空闲回收守护线程（进程内只启动一次；阈值为 0 时不启动）。
     *
     * <p>用守护线程而非 Spring 调度：本类位于基础库，不能强依赖上层容器；
     * 守护属性保证不阻塞 JVM 退出。</p>
     */
    private void startIdleReaper() {
        if (IDLE_UNLOAD_MS <= 0 || IDLE_CHECK_MS <= 0) {
            return;
        }
        if (!REAPER_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(IDLE_CHECK_MS);
                    int n = evictIdle(IDLE_UNLOAD_MS);
                    if (n > 0) {
                        log.info("[deeplearning-engine] 空闲卸载 {} 个模型会话，释放原生活存（阈值={}ms）", n, IDLE_UNLOAD_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    // 回收失败不能影响推理：吞掉并继续下一轮
                    log.debug("[deeplearning-engine] 空闲卸载异常: {}", e.getMessage());
                }
            }
        }, "dl-idle-reaper");
        t.setDaemon(true);
        t.start();
        log.info("[deeplearning-engine] 空闲卸载已启用：超过 {}ms 未使用即释放模型会话（检查周期 {}ms）",
                IDLE_UNLOAD_MS, IDLE_CHECK_MS);
    }

    /**
     * 记录模型被取用（刷新其空闲计时）。
     *
     * @param modelId 模型标识
     */
    private void touch(String modelId) {
        if (modelId != null && IDLE_UNLOAD_MS > 0) {
            lastUsedAt.put(modelId, System.currentTimeMillis());
        }
    }

    /**
     * 释放指定模型的推理会话并从注册表移除（下次取用会自动重建）。
     *
     * <p>先摘除条目再关闭：避免关闭期间新请求又拿到正在释放的实例；
     * 即便有调用方已持有引用，其后续 {@code predict} 也会重新初始化，
     * 不会读到半关闭状态。</p>
     *
     * @param modelId 模型标识
     * @return true 表示确实释放了一个已注册模型
     */
    public boolean evict(String modelId) {
        if (modelId == null) {
            return false;
        }
        TranslatorModelDefinition def = modelMap.remove(modelId);
        lastUsedAt.remove(modelId);
        if (def == null) {
            return false;
        }
        closeQuietly(def);
        return true;
    }

    /**
     * 释放所有空闲超时的模型会话。
     *
     * @param idleMillis 空闲阈值（毫秒）
     * @return 被释放的模型数量
     */
    public int evictIdle(long idleMillis) {
        if (idleMillis <= 0 || modelMap.isEmpty()) {
            return 0;
        }
        long deadline = System.currentTimeMillis() - idleMillis;
        int released = 0;
        for (String modelId : modelMap.keySet()) {
            Long used = lastUsedAt.get(modelId);
            // 从未被取用过的模型没有加载会话，无需回收
            if (used == null || used > deadline) {
                continue;
            }
            if (evict(modelId)) {
                released++;
            }
        }
        return released;
    }

    /**
     * 关闭模型定义持有的 translator（容错，不向外抛异常）。
     *
     * @param def 模型定义
     */
    private static void closeQuietly(TranslatorModelDefinition def) {
        Object translator = def.getTranslator();
        if (translator instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("[deeplearning-engine] 关闭 translator 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 通过 模型registry 静态注册 + SPI 模型提供者 发现模型定义。
     */
    private void discoverModels() {
        try {
            ModelRegistry.discoverAll();
            for (ModelRegistry.Entry entry : ModelRegistry.getAll()) {
                try {
                    // 懒加载：启动只挂定义，不解析/下载模型路径；
 // 真实路径在首次 translate 时再解析（支持 jar/类路径 / 远程自动下载）
                    ITranslator<?, ?> translator = ModelRegistry.createTranslator(entry.modelId(), null);
                    String provider = resolveProvider(entry.relativePath());
                    String pathText = entry.relativePath() != null ? entry.relativePath() : "";
                    TranslatorModelDefinition def = TranslatorModelDefinition.builder()
                            .modelDefinition(ModelDefinition.builder()
                                    .id(entry.modelId())
                                    .name(entry.modelId())
                                    .provider(provider)
                                    .description(provider.toUpperCase() + ": " + entry.modelId())
                                    .capabilities(capabilityLabels(entry))
                                    .downloadUrl(entry.downloadUrl())
                                    .compress(entry.compress())
                                    .downloadFileName(entry.downloadFileName())
                                    .build())
                            .translator(translator)
                            .config(Map.of("path", pathText))
                            .build();
                    register(def);
                } catch (Exception e) {
                    log.warn("[deeplearning-engine] 加载模型 [{}] 失败: {}", entry.modelId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.warn("[deeplearning-engine] ModelRegistry 发现失败: {}", e.getMessage(), e);
        }

        try {
            ServiceProvider<ModelProvider> provider = ServiceProvider.of(ModelProvider.class);
            log.debug("[deeplearning-engine] ModelProvider classloader: {}", ModelProvider.class.getClassLoader());
            Set<String> names = provider.getExtensions();
            log.debug("[deeplearning-engine] ModelProvider names: {}", names);
            if (names != null) {
                for (String name : names) {
                    try {
                        ModelProvider modelProvider = provider.getExtension(name);
                        if (modelProvider != null) {
                            if (modelProvider instanceof BulkModelProvider bulkProvider) {
                                List<TranslatorModelDefinition> defs = bulkProvider.getAll();
                                if (defs != null) {
                                    for (TranslatorModelDefinition def : defs) {
                                        if (def != null) {
                                            register(def);
                                        }
                                    }
                                }
                            } else {
                                TranslatorModelDefinition def = modelProvider.getDefinition();
                                if (def != null) {
                                    register(def);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[deeplearning-engine] load provider [{}] failed: {}", name, e.getMessage());
                        log.warn("[deeplearning-engine] 加载模型提供者 [{}] 失败: {}", name, e.getMessage(), e);
                    }
                }
            }
            log.debug("[deeplearning-engine] modelMap size after discovery: {}", modelMap.size());
            log.info("[deeplearning-engine] 识别引擎自动发现 {} 个模型", modelMap.size());
        } catch (Exception e) {
            log.debug("[deeplearning-engine] discovery exception: {}", e.getMessage());
            log.warn("[deeplearning-engine] 模型自动发现失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取模型
    */
    @Override
    public List<ModelDefinition> getModels() {
        List<ModelDefinition> result = Wraps.list();
        for (TranslatorModelDefinition def : modelMap.values()) {
            if (def.getModelDefinition() != null) {
                result.add(def.getModelDefinition());
            }
        }
        return result;
    }

    /**
     * 获取translator模型
    */
    @Override
    public List<TranslatorModelDefinition> getTranslatorModels() {
        return Wraps.list(modelMap.values());
    }

    /**
     * 注册
    */
    @Override
    public void register(TranslatorModelDefinition definition) {
        if (definition == null) {
            return;
        }
        if (definition.getName() == null) {
            log.warn("[deeplearning-engine] 注册的模型定义名称为空，已忽略");
            return;
        }
        modelMap.put(definition.getName(), definition);
        log.debug("[deeplearning-engine] 注册模型: {} ({})", definition.getName(), definition.getProvider());
    }

    /**
     * 获取模型名称bycapability
    */
    @Override
    public List<String> getModelNamesByCapability(Class<?> capabilityInterface) {
        String label = com.chua.deeplearning.support.capability.ModelCapabilities.labelOf(capabilityInterface);
        if (label != null) {
            return getModelNamesByCapability(label);
        }
 // 能力接口无法映射时，按 模型registry 能力接口查询兜底
        return ModelRegistry.getModelIdsByCapability(capabilityInterface);
    }

    /**
     * 获取模型名称bycapability
    */
    @Override
    public List<String> getModelNamesByCapability(String capability) {
        if (capability == null || capability.isBlank()) {
            List<String> all = Wraps.list();
            for (ModelDefinition def : getModels()) {
                all.add(def.getId());
            }
            return all;
        }
        List<String> result = Wraps.list();
        for (ModelDefinition def : getModels()) {
            List<String> caps = def.getCapabilities();
            if (caps != null && caps.contains(capability)) {
                result.add(def.getId());
            }
        }
        return result;
    }

    /**
     * 按模型标识即时构建定义（用于启动后才注册的外部目录模型）。
     *
     * <p>与 {@link #discoverModels()} 内单条构建逻辑一致：从 {@link ModelRegistry} 取条目，
     * 创建懒加载 Translator，组装并注册 {@link TranslatorModelDefinition}。</p>
     *
     * @param modelId 模型标识
     * @return 已注册的定义；条目不存在或构建失败返回 空
     */
    protected TranslatorModelDefinition buildDefinitionForModelId(String modelId) {
        ModelRegistry.Entry entry = ModelRegistry.get(modelId);
        if (entry == null) {
            return null;
        }
        try {
            ITranslator<?, ?> translator = ModelRegistry.createTranslator(entry.modelId(), null);
            String provider = resolveProvider(entry.relativePath());
            String pathText = entry.relativePath() != null ? entry.relativePath() : "";
            TranslatorModelDefinition def = TranslatorModelDefinition.builder()
                    .modelDefinition(ModelDefinition.builder()
                            .id(entry.modelId())
                            .name(entry.modelId())
                            .provider(provider)
                            .description(provider.toUpperCase() + ": " + entry.modelId())
                            .capabilities(capabilityLabels(entry))
                            .downloadUrl(entry.downloadUrl())
                            .compress(entry.compress())
                            .downloadFileName(entry.downloadFileName())
                            .build())
                    .translator(translator)
                    .config(Map.of("path", pathText))
                    .build();
            register(def);
            return def;
        } catch (Exception e) {
            log.warn("[deeplearning-engine] 懒加载模型 [{}] 失败: {}", modelId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取
     *
     * @param name 名称
     * @param target Target
     * @return 获取的结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String name, Class<T> target) {
        TranslatorModelDefinition def = modelMap.get(name);
        if (def == null) {
            def = buildDefinitionForModelId(name);
        }
        if (def == null) {
            return null;
        }
        // 取用即续命：空闲卸载据此判断该模型是否仍在使用中
        touch(name);
        // 无显式 options 时，同样应用该 modelId 的模型级持久化参数
        java.util.Map<String, Object> merged = ModelParams.merge(name, null);
        Object translator = def.getTranslator();
        if (!merged.isEmpty() && translator instanceof DetectionConfigurable configurable) {
            configurable.configure(merged);
        }
        if (target.isInstance(translator)) {
            return (T) translator;
        }
        return null;
    }

    /**
     * 获取并注入运行参数
     *
     * @param name 名称
     * @param target Target
     * @param options 期权
     * @return 获取的结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String name, Class<T> target, java.util.Map<String, Object> options) {
        TranslatorModelDefinition def = modelMap.get(name);
        if (def == null) {
            def = buildDefinitionForModelId(name);
        }
        if (def == null) {
            return null;
        }
        // 取用即续命：空闲卸载据此判断该模型是否仍在使用中
        touch(name);
        // 合并该 modelId 的模型级持久化参数（device / threshold / 模型特有参数等）。
        // 调用方显式传入的键优先级更高；无任何配置时不产生额外开销语义。
        java.util.Map<String, Object> merged = ModelParams.merge(name, options);
        Object translator = def.getTranslator();
        if (translator instanceof DetectionConfigurable configurable) {
            configurable.configure(merged);
        }
        if (target.isInstance(translator)) {
            return (T) translator;
        }
        return null;
    }

    /**
     * 获取
     *
     * @param target Target
     * @return 获取的结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> target) {
        for (Map.Entry<String, TranslatorModelDefinition> en : modelMap.entrySet()) {
            Object translator = en.getValue().getTranslator();
            if (target.isInstance(translator)) {
                // 按能力遍历也视为一次取用，避免刚拿到就被空闲回收
                touch(en.getKey());
                return (T) translator;
            }
        }
        return null;
    }

    /**
     * 获取引擎全局实例（单例）。
     * <p>优先通过 SPI 获取已注册的 {@link IdentificationEngine} 实现（如 ONNX），
     * 若无则返回默认匿名实例。</p>
     *
     * @return IdentificationEngine 实例
     */
    public static IdentificationEngine getInstance() {
        if (INSTANCE == null) {
            synchronized (AbstractIdentificationEngine.class) {
                if (INSTANCE == null) {
                    try {
                        ServiceProvider<IdentificationEngine> provider = ServiceProvider.of(IdentificationEngine.class);
                        INSTANCE = provider.getDefault();
                    } catch (Exception e) {
                        log.warn("[deeplearning-engine] SPI 获取 IdentificationEngine 失败: {}", e.getMessage(), e);
                    }
                    if (INSTANCE == null) {
                        INSTANCE = new AbstractIdentificationEngine() {};
                    }
                }
            }
        }
        return INSTANCE;
    }

    /**
     * INSTANCE
    */
    private static volatile IdentificationEngine INSTANCE;

    /**
     * 根据模型注册条目的能力接口生成能力标签列表。
     *
     * <p>优先使用能力接口映射（如 {@code ImageDetector → detect}）；
     * 未声明能力接口或无法识别时，回退按名称约定识别（OCR/版面/姿态等）。</p>
     *
     * @param entry 模型注册条目
     * @return 能力标签列表（可为空列表）
     */
    private static List<String> capabilityLabels(ModelRegistry.Entry entry) {
        List<String> labels = Wraps.list();
        String label = com.chua.deeplearning.support.capability.ModelCapabilities.labelOf(entry.capabilityInterface());
        if (label != null) {
            labels.add(label);
        }
        String name = entry.modelId() == null ? "" : entry.modelId().toLowerCase();
        // 名称约定回退：OCR / 版面 / 姿态 / 文本嵌入
        if (label == null || label.isBlank()) {
            if (nameContains(name, "ocr", "pp-word", "svtr", "paddle-ocr", "table-struct", "pp-structure", "docling")) {
                labels.add(com.chua.deeplearning.support.capability.ModelCapabilities.OCR);
            }
            if (nameContains(name, "layout", "d4la", "docstruct", "cdla", "doclaynet")) {
                labels.add(com.chua.deeplearning.support.capability.ModelCapabilities.LAYOUT);
            }
            if (nameContains(name, "pose", "vit-pose", "yolov8n-pose")) {
                labels.add(com.chua.deeplearning.support.capability.ModelCapabilities.POSE);
            }
            if (nameContains(name, "bge", "minilm", "embedding", "clip-text")) {
                labels.add(com.chua.deeplearning.support.capability.ModelCapabilities.EMBEDDING);
            }
            if (nameContains(name, "translate", "nllb", "opus-mt", "opus_alt")) {
                labels.add(com.chua.deeplearning.support.capability.ModelCapabilities.TRANSLATE);
            }
        }
        return labels;
    }

    /**
     * 名称contains
     *
     * @param name 名称
     * @param keywords keywords
     * @return 名称contains的结果
     */
    private static boolean nameContains(String name, String... keywords) {
        for (String keyword : keywords) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析提供者
     *
     * @param relativePath relative路径
     * @return resolve提供者的结果
     */
    private static String resolveProvider(String relativePath) {
        if (relativePath == null) {
            return DEFAULT_PROVIDER;
        }
        String lower = relativePath.toLowerCase().replace('\\', '/');
        if (lower.startsWith(CLASSPATH_PREFIX)) {
            lower = lower.substring(CLASSPATH_PREFIX.length());
        }
        if (lower.endsWith(".pt") || lower.endsWith(".pth") || lower.contains("/pytorch/") || lower.contains("/pt/")) {
            return PROVIDER_PYTORCH;
        }
        if (lower.endsWith(".safetensors") || lower.contains("/safetensors/")) {
            return PROVIDER_SAFETENSORS;
        }
        if (lower.endsWith(".pdmodel") || lower.endsWith(".pdiparams") || lower.contains("/paddle/")) {
            return PROVIDER_PADDLE;
        }
        if (lower.endsWith(".pb") || lower.endsWith(".savedmodel") || lower.contains("/tensorflow/") || lower.contains("/tf/")) {
            return PROVIDER_TENSORFLOW;
        }
        return DEFAULT_PROVIDER;
    }

    /**
     * 关闭
    */
    @Override
    public void close() {
        modelMap.clear();
        INSTANCE = null;
        log.info("[deeplearning-engine] 识别引擎已关闭");
    }
}
