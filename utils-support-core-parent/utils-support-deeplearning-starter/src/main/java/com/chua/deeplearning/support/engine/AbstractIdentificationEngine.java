package com.chua.deeplearning.support.engine;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
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
     * 默认 Provider 名称
     */
    private static final String DEFAULT_PROVIDER = "onnx";

    /**
     * Provider 名称：PyTorch
     */
    private static final String PROVIDER_PYTORCH = "pytorch";

    /**
     * Provider 名称：Safetensors
     */
    private static final String PROVIDER_SAFETENSORS = "safetensors";

    /**
     * Provider 名称：PaddlePaddle
     */
    private static final String PROVIDER_PADDLE = "paddle";

    /**
     * Provider 名称：TensorFlow
     */
    private static final String PROVIDER_TENSORFLOW = "tensorflow";

    /**
     * classpath 前缀
     */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /**
     * 构造引擎，自动执行 SPI 模型发现。
     */
    static {
        ServiceProvider.CACHE.clear();
    }

    public AbstractIdentificationEngine() {
        ImageUtils.load();
        discoverModels();
    }

    /**
     * 通过 ModelRegistry 静态注册 + SPI ModelProvider 发现模型定义。
     */
    private void discoverModels() {
        try {
            ModelRegistry.discoverAll();
            for (ModelRegistry.Entry entry : ModelRegistry.getAll()) {
                try {
                    // 懒加载：启动只挂定义，不解析/下载模型路径；
                    // 真实路径在首次 translate 时再解析（支持 jar/classpath / 远程自动下载）
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
                    log.warn("[deeplearning-engine] 加载模型 [{}] 失败: {}", entry.modelId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[deeplearning-engine] ModelRegistry 发现失败: {}", e.getMessage());
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
                        log.warn("[deeplearning-engine] 加载模型提供者 [{}] 失败: {}", name, e.getMessage());
                    }
                }
            }
            log.debug("[deeplearning-engine] modelMap size after discovery: {}", modelMap.size());
            log.info("[deeplearning-engine] 识别引擎自动发现 {} 个模型", modelMap.size());
        } catch (Exception e) {
            log.debug("[deeplearning-engine] discovery exception: {}", e.getMessage());
            log.warn("[deeplearning-engine] 模型自动发现失败: {}", e.getMessage());
        }
    }

    @Override
    public List<ModelDefinition> getModels() {
        List<ModelDefinition> result = new ArrayList<>();
        for (TranslatorModelDefinition def : modelMap.values()) {
            if (def.getModelDefinition() != null) {
                result.add(def.getModelDefinition());
            }
        }
        return result;
    }

    @Override
    public List<TranslatorModelDefinition> getTranslatorModels() {
        return new ArrayList<>(modelMap.values());
    }

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

    @Override
    public List<String> getModelNamesByCapability(Class<?> capabilityInterface) {
        String label = com.chua.deeplearning.support.capability.ModelCapabilities.labelOf(capabilityInterface);
        if (label != null) {
            return getModelNamesByCapability(label);
        }
        // 能力接口无法映射时，按 ModelRegistry 能力接口查询兜底
        return ModelRegistry.getModelIdsByCapability(capabilityInterface);
    }

    @Override
    public List<String> getModelNamesByCapability(String capability) {
        if (capability == null || capability.isBlank()) {
            List<String> all = new ArrayList<>();
            for (ModelDefinition def : getModels()) {
                all.add(def.getId());
            }
            return all;
        }
        List<String> result = new ArrayList<>();
        for (ModelDefinition def : getModels()) {
            List<String> caps = def.getCapabilities();
            if (caps != null && caps.contains(capability)) {
                result.add(def.getId());
            }
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String name, Class<T> target) {
        TranslatorModelDefinition def = modelMap.get(name);
        if (def == null) {
            return null;
        }
        Object translator = def.getTranslator();
        if (target.isInstance(translator)) {
            return (T) translator;
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> target) {
        for (TranslatorModelDefinition def : modelMap.values()) {
            Object translator = def.getTranslator();
            if (target.isInstance(translator)) {
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
                        log.warn("[deeplearning-engine] SPI 获取 IdentificationEngine 失败: {}", e.getMessage());
                    }
                    if (INSTANCE == null) {
                        INSTANCE = new AbstractIdentificationEngine() {};
                    }
                }
            }
        }
        return INSTANCE;
    }

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
        List<String> labels = new ArrayList<>();
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

    private static boolean nameContains(String name, String... keywords) {
        for (String keyword : keywords) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

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

    @Override
    public void close() {
        modelMap.clear();
        INSTANCE = null;
        log.info("[deeplearning-engine] 识别引擎已关闭");
    }
}
