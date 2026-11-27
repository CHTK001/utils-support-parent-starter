package com.chua.jitllm.support.embedding;

import com.chua.deeplearning.support.engine.DetectionConfigurable;
import com.chua.deeplearning.support.engine.DeviceSelector;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.args.PoolingType;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Locale;
import com.chua.common.support.utils.CollectionUtils;

/**
 * jitLLM 本地文本嵌入翻译器（llama.cpp 进程内 GGUF 推理）。
 *
 * <p>利用 llama.cpp 的 embedding 能力将文本编码为稠密向量（{@code String → float[]}），
 * 供 {@code JitllmEmbeddingClient} 与 RAG 检索链路使用。模型文件路径通过框架反射
 * {@code setModelPath(String)} 注入（与 {@code Llama3ChatTranslator} 同约定），
 * 也可按 modelId 从 {@link ModelRegistry} 解析。</p>
 *
 * <p>支持运行时参数注入（通过 {@link DetectionConfigurable#configure(Map)}）：</p>
 * <ul>
 *   <li>{@code useGpu} - Boolean：是否启用 GPU（null 时退化为 CPU）</li>
 *   <li>{@code gpuLayers} - Integer：分配给 GPU 的层数（-1 自动 / 0 CPU / N 指定层）</li>
 *   <li>{@code ctxSize} - Integer：上下文窗口大小（嵌入模型按各自训练长度截断）</li>
 *   <li>{@code threads} - Integer：推理线程数</li>
 *   <li>{@code pooling} - String：池化方式 mean/cls/last/none（默认 mean）</li>
 *   <li>{@code device} - String：设备选择（auto/cpu/gpu/cuda）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class JitllmEmbeddingTranslator implements ITranslator<String, float[]>, DetectionConfigurable, AutoCloseable {

    /**
     * 默认上下文大小（嵌入输入按各模型训练长度截断，2048 覆盖常见 RAG 分块）
     */
    private static final int DEFAULT_CTX_SIZE = 2048;

    /**
     * 默认池化方式（llama.cpp 嵌入默认 mean）
     */
    private static final String DEFAULT_POOLING = "mean";

    private final String modelId; // 模型标识
    private volatile LlamaModel model; // 模型
    private volatile boolean initialized; // 初始化

    /**
     * 运行时配置的 GPU 层数（空 = 使用默认）
     */
    private volatile Integer gpuLayers;
    /**
     * 运行时配置的上下文大小
     */
    private volatile Integer ctxSize;
    /**
     * 运行时配置的线程数
     */
    private volatile Integer threads;
    /**
     * 运行时配置的池化方式（mean/cls/last/none）
     */
    private volatile String pooling;
    /**
     * 运行时配置的 device 选择（空/blank = auto）
     */
    private volatile String device;
    /**
     * 框架反射注入的模型文件路径（{@code setModelPath(String)}），优先于按 modelId 解析
     */
    private volatile String modelPath;

    /**
     * 默认构造器，模型路径按 modelId 从注册表解析。
     */
    public JitllmEmbeddingTranslator() {
        this("bge-m3");
    }

    /**
     * 构造器。
     *
     * @param modelId 模型 标识
     */
    public JitllmEmbeddingTranslator(String modelId) {
        this.modelId = modelId;
    }

    @Override
    public String name() {
        return modelId;
    }

    /**
     * 注入运行参数（仅首次实例化前生效）。
     *
     * <p>参数键约定（与 {@code DetectionConfiguration.systemOption} 对齐）：</p>
     * <ul>
     *   <li>{@code useGpu} → {@link Boolean}：true/false（null 时由 device 自动决定）</li>
     *   <li>{@code gpuLayers} → {@link Integer}：-1/0/N</li>
     *   <li>{@code ctxSize} → {@link Integer}</li>
     *   <li>{@code threads} → {@link Integer}</li>
     *   <li>{@code pooling} → {@link String}：mean/cls/last/none</li>
     *   <li>{@code device} → {@link String}（auto/cpu/gpu/cuda）</li>
     * </ul>
     */
    @Override
    public void configure(Map<String, Object> options) {
        if (CollectionUtils.isEmpty(options)) {
            return;
        }
        synchronized (this) {
            if (model != null) {
                log.warn("[Jitllm-Embed:{}]{}: 模型已初始化，参数注入被忽略: {}", modelId, System.currentTimeMillis(),
                         options.keySet());
                return;
            }
            Object v = options.get("useGpu");
            if (v instanceof Boolean b) {
                this.device = b ? "gpu" : "cpu";
            }
            v = options.get("device");
            if (v != null && !v.toString().isBlank()) {
                this.device = v.toString().trim().toLowerCase(Locale.ROOT);
            }
            v = options.get("gpuLayers");
            if (v instanceof Number n) {
                this.gpuLayers = n.intValue();
            }
            v = options.get("ctxSize");
            if (v instanceof Number n) {
                this.ctxSize = n.intValue();
            }
            v = options.get("threads");
            if (v instanceof Number n) {
                this.threads = n.intValue();
            }
            v = options.get("pooling");
            if (v != null && !v.toString().isBlank()) {
                this.pooling = v.toString().trim().toLowerCase(Locale.ROOT);
            }
            log.info("[Jitllm-Embed:{}]{} 参数注入完成: {}", modelId, System.currentTimeMillis(), options);
        }
    }

    /**
     * 框架反射注入模型路径（{@code setModelPath(String)}），与 {@code Llama3ChatTranslator} 同约定。
     *
     * @param modelPath 注册表中解析出的模型文件路径
     */
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public float[] translate(String input) {
        if (input == null || input.isBlank()) {
            return new float[0];
        }
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path resolvedPath = resolveInjectableModelPath();
                        int resolvedGpuLayers = resolveGpuLayers();
                        int resolvedCtxSize = resolveCtxSize();
                        int resolvedThreads = resolveThreads();
                        String resolvedDevice = DeviceSelector.resolve(device);
                        boolean useGpu = "gpu".equals(resolvedDevice) || "cuda".equals(resolvedDevice);

                        log.info("[Jitllm-Embed:{}] 开始加载 GGUF 嵌入模型（device={} gpuLayers={} ctxSize={} threads={} pooling={}）: {}",
                                modelId, resolvedDevice, resolvedGpuLayers, resolvedCtxSize, resolvedThreads,
                                resolvePoolingType(), resolvedPath);

                        ModelParameters parameters = new ModelParameters()
                                .setModel(resolvedPath.toString())
                                .setCtxSize(resolvedCtxSize)
                                .setThreads(resolvedThreads)
                                .setPoolingType(resolvePoolingType())
                                .enableEmbedding();
                        if (useGpu) {
                            parameters.setGpuLayers(resolvedGpuLayers);
                        } else {
                            parameters.setGpuLayers(0);
                        }
                        model = new LlamaModel(parameters);
                        initialized = true;
                        log.info("[Jitllm-Embed:{}] 嵌入模型加载成功 (device={})", modelId, resolvedDevice);
                    } catch (Exception e) {
                        throw new RuntimeException("[Jitllm-Embed:" + modelId + "] 嵌入模型加载失败: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            float[] vector = model.embed(input);
            return vector != null ? vector : new float[0];
        } catch (Exception e) {
            log.warn("[Jitllm-Embed:{}] 嵌入推理失败: {}", modelId, e.getMessage(), e);
            throw new RuntimeException("[Jitllm-Embed:" + modelId + "] 嵌入推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析池化方式：mean/cls/last/none，未知取值回落 mean。
     *
     * @return llama.cpp 池化枚举
     */
    private PoolingType resolvePoolingType() {
        String value = pooling != null ? pooling : DEFAULT_POOLING;
        return switch (value) {
            case "cls" -> PoolingType.CLS;
            case "last" -> PoolingType.LAST;
            case "none" -> PoolingType.NONE;
            default -> PoolingType.MEAN;
        };
    }

    /**
     * 解析模型文件路径：优先框架注入的 {@code modelPath}（走
     * {@link ModelRegistry#resolveConfiguredPath} 归一化），否则按 modelId 解析。
     *
     * @return 模型文件路径
     */
    private Path resolveInjectableModelPath() {
        if (modelPath != null && !modelPath.isBlank()) {
            Path injected = ModelRegistry.resolveConfiguredPath(modelPath);
            return injected != null ? injected : Paths.get(modelPath);
        }
        return ModelRegistry.resolveModelPath(modelId);
    }

    /**
     * 解析 gpulayers：优先运行时配置，其次默认 -1（全部 GPU）。
     * @return resolveGpuLayers的结果
     */
    private int resolveGpuLayers() {
        if (gpuLayers != null) {
            return gpuLayers;
        }
        // 若 device 指定了 cpu，返回 0；否则默认 -1（全部 GPU 层）
        if ("cpu".equals(device)) {
            return 0;
        }
        return -1;
    }

    /**
     * 解析 ctx大小：优先运行时配置，其次默认 2048。
     * @return resolvectx大小的结果
     */
    private int resolveCtxSize() {
        return ctxSize != null ? ctxSize : DEFAULT_CTX_SIZE;
    }

    /**
     * 解析 threads：优先运行时配置，其次 CPU 核心数。
     * @return resolveThreads的结果
     */
    private int resolveThreads() {
        return threads != null ? threads : Runtime.getRuntime().availableProcessors();
    }

    @Override
    public void close() {
        if (model != null) {
            try {
                model.close();
                log.info("[Jitllm-Embed:{}] 嵌入模型已关闭", modelId);
            } catch (Exception e) {
                log.warn("[Jitllm-Embed:{}] 关闭嵌入模型失败: {}", modelId, e.getMessage(), e);
            }
            model = null;
            initialized = false;
        }
    }
}
