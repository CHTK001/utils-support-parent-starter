package com.chua.deeplearning.support.gpu_llama3.translator;

import com.chua.deeplearning.support.engine.DetectionConfigurable;
import com.chua.deeplearning.support.engine.DeviceSelector;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaIterator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Map;

/**
 * Llama 3 GPU 对话翻译器。
 * <p>
 * 利用 llama.cpp 的 GPU 加速能力进行 Llama 3 模型的文本生成推理。
 * 通过 模型参数 配置 GPU 层数和上下文大小。
 * </p>
 *
 * <p>支持运行时参数注入（通过 {@link DetectionConfigurable#configure(Map)} 或 {@link ModelParameters}）：</p>
 * <ul>
 *   <li>{@code useGpu} - Boolean：是否启用 GPU（null 时退化为 CPU）</li>
 *   <li>{@code gpuLayers} - Integer：分配给 GPU 的层数（-1 自动 / 0 CPU / N 指定层）</li>
 *   <li>{@code ctxSize} - Integer：上下文窗口大小</li>
 *   <li>{@code topK} - Integer：Top-K 采样</li>
 *   <li>{@code temperature} - Double：采样温度</li>
 *   <li>{@code nPredict} - Integer：最大输出 token 数</li>
 *   <li>{@code threads} - Integer：推理线程数</li>
 *   <li>{@code device} - String：设备选择（auto/cpu/gpu/cuda）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class Llama3ChatTranslator implements ITranslator<String, String>, DetectionConfigurable, AutoCloseable {

    /**
     * 单次回答最大 令牌 数
    */
    private static final int DEFAULT_N_PREDICT = 512;
    /**
     * Llama 3 对话 结束标记
    */
    private static final String END_TOKEN = "<|end_of_text|>";
    /**
     * 默认上下文大小
    */
    private static final int DEFAULT_CTX_SIZE = 4096;
    /**
     * 默认 Top-K
    */
    private static final int DEFAULT_TOP_K = 40;
    /**
     * 默认温度
    */
    private static final float DEFAULT_TEMPERATURE = 0.7f;

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
     * 运行时配置的 Top-K
    */
    private volatile Integer topK;
    /**
     * 运行时配置的温度
    */
    private volatile Float temperature;
    /**
     * 运行时配置的最大输出 令牌
    */
    private volatile Integer nPredict;
    /**
     * 运行时配置的线程数
    */
    private volatile Integer threads;
    /**
     * 运行时配置的 device 选择（空/blank = auto）
    */
    private volatile String device;

    /**
     * 默认构造器，使用 llama-3-8b-it 模型。
     */
    public Llama3ChatTranslator() {
        this("llama-3-8b-it");
    }

    /**
     * 构造器。
     *
     * @param modelId 模型 标识
     */
    public Llama3ChatTranslator(String modelId) {
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
     *   <li>{@code topK} → {@link Integer}</li>
     *   <li>{@code temperature} → {@link Double}</li>
     *   <li>{@code nPredict} → {@link Integer}（等价于 maxTokens）</li>
     *   <li>{@code threads} → {@link Integer}</li>
     *   <li>{@code device} → {@link String}（auto/cpu/gpu/cuda）</li>
     * </ul>
     */
    @Override
    public void configure(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (model != null) {
                log.warn("[Llama3-GPU:{}]{}: 模型已初始化，参数注入被忽略: {}", modelId, System.currentTimeMillis(), options.keySet());
                return;
            }
            Object v = options.get("useGpu");
            if (v instanceof Boolean b) {
                this.device = b ? "gpu" : "cpu";
            }
            v = options.get("device");
            if (v != null && !v.toString().isBlank()) {
                this.device = v.toString().trim().toLowerCase();
            }
            v = options.get("gpuLayers");
            if (v instanceof Number n) {
                this.gpuLayers = n.intValue();
            }
            v = options.get("ctxSize");
            if (v instanceof Number n) {
                this.ctxSize = n.intValue();
            }
            v = options.get("topK");
            if (v instanceof Number n) {
                this.topK = n.intValue();
            }
            v = options.get("temperature");
            if (v instanceof Number n) {
                this.temperature = n.floatValue();
            }
            v = options.get("nPredict");
            if (v instanceof Number n) {
                this.nPredict = n.intValue();
            }
            v = options.get("threads");
            if (v instanceof Number n) {
                this.threads = n.intValue();
            }
            log.info("[Llama3-GPU:{}]{:} 参数注入完成: {}", modelId, System.currentTimeMillis(), options);
        }
    }

    @Override
    public String translate(String input) {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        Path modelPath = ModelRegistry.resolveModelPath(modelId);
                        int resolvedGpuLayers = resolveGpuLayers();
                        int resolvedCtxSize = resolveCtxSize();
                        int resolvedThreads = resolveThreads();
                        String resolvedDevice = DeviceSelector.resolve(device);
                        boolean useGpu = "gpu".equals(resolvedDevice) || "cuda".equals(resolvedDevice);

                        log.info("[Llama3-GPU:{}] 开始加载 GGUF 模型（device={} gpuLayers={} ctxSize={} threads={}）: {}",
                                modelId, resolvedDevice, resolvedGpuLayers, resolvedCtxSize, resolvedThreads, modelPath);

                        ModelParameters parameters = new ModelParameters()
                                .setModel(modelPath.toString())
                                .setCtxSize(resolvedCtxSize)
                                .setThreads(resolvedThreads);
                        if (useGpu) {
                            parameters.setGpuLayers(resolvedGpuLayers);
                        } else {
                            parameters.setGpuLayers(0);
                        }
                        model = new LlamaModel(parameters);
                        initialized = true;
                        log.info("[Llama3-GPU:{}] 模型加载成功 (device={})", modelId, resolvedDevice);
                    } catch (Exception e) {
                        throw new RuntimeException("[Llama3-GPU:" + modelId + "] 模型加载失败: " + e.getMessage(), e);
                    }
                }
            }
        }
        try {
            float temp = temperature != null ? temperature : DEFAULT_TEMPERATURE;
            int topk = topK != null ? topK : DEFAULT_TOP_K;
            int npredict = nPredict != null ? nPredict : DEFAULT_N_PREDICT;
            InferenceParameters inferParams = new InferenceParameters(input)
                    .setTemperature(temp)
                    .setTopK(topk)
                    .setNPredict(npredict);
            return generateWithLimit(inferParams);
        } catch (Exception e) {
            log.warn("[Llama3-GPU:{}] 推理失败: {}", modelId, e.getMessage());
            return "";
        }
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
     * 解析 ctx大小：优先运行时配置，其次默认 4096。
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

    /**
     * 逐 令牌 生成，遇结束符或达到上限提前终止。
     * @param parameters 参数
     * @return generatewith限制的结果
     */
    private String generateWithLimit(InferenceParameters parameters) {
        StringBuilder sb = new StringBuilder();
        LlamaIterator it = model.generate(parameters).iterator();
        int tokens = 0;
        try {
                while (it.hasNext() && tokens < nPredict) {
                LlamaOutput out = it.next();
                if (out == null || out.text == null) {
                    break;
                }
                String text = out.text;
                int idx = text.indexOf(END_TOKEN);
                if (idx >= 0) {
                    sb.append(text, 0, idx);
                    break;
                }
                sb.append(text);
                tokens++;
            }
        } finally {
            it.cancel();
        }
        return sb.toString().trim();
    }

    @Override
    public void close() {
        if (model != null) {
            try {
                model.close();
                log.info("[Llama3-GPU:{}] 模型已关闭", modelId);
            } catch (Exception e) {
                log.warn("[Llama3-GPU:{}] 关闭模型失败: {}", modelId, e.getMessage());
            }
            model = null;
            initialized = false;
        }
    }
}
