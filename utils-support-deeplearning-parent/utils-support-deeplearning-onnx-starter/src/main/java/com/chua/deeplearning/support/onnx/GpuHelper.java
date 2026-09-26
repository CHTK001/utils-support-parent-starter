package com.chua.deeplearning.support.onnx;

import ai.onnxruntime.OrtSession;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;

/**
 * ONNX Runtime GPU 通用工具。
 * <p>所有裸 {@link ai.onnxruntime.OrtSession} 的 ONNX 模型统一通过此类判断是否启用 GPU（CUDA）。
 * 配置来源为 {@link com.chua.deeplearning.support.ai.DetectionConfiguration#get()}。
 * <p>使用方式：</p>
 * <pre>{@code
 *   SessionOptions opts = new SessionOptions();
 *   GpuHelper.apply(opts);   // 自动根据全局配置决定是否 addCUDA
 *   session = env.createSession(modelPath, opts);
 * }</pre>
 * <p>回退策略：</p>
 * <ul>
 *   <li>仅当运行环境同时具备 CUDA provider 原生库时才会真正调用 {@code addCUDA}；</li>
 *   <li>缺少原生库或初始化抛异常时静默回退为纯 CPU，不影响功能运行。</li>
 * </ul>
 * <p>注意：CPU 版 {@code onnxruntime} 构件不携带 {@code onnxruntime_providers_cuda} 原生库，
 * 因此 {@link #cudaAvailable()} 为 false 时即便配置要求 GPU 也会走 CPU。需要真实 GPU 加速时，
 * 请将依赖切换为 {@code com.microsoft.onnxruntime:onnxruntime_gpu}，并保证本机具备
 * 对应版本的 CUDA / cuDNN 运行时。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@lombok.extern.slf4j.Slf4j
public final class GpuHelper {

    /**
     * CUDA provider 原生库资源路径（Windows）。
     */
    private static final String CUDA_MARKER_WINDOWS =
            "ai/onnxruntime/native/win-x64/onnxruntime_providers_cuda.dll";

    /**
     * CUDA provider 原生库资源路径（Linux）。
     */
    private static final String CUDA_MARKER_LINUX =
            "ai/onnxruntime/native/linux-x64/libonnxruntime_providers_cuda.so";

    /**
     * gpu 助手。
     */
    private GpuHelper() {}

    /**
     * 判断当前是否应启用 GPU。
     * <p>以 {@link com.chua.deeplearning.support.engine.DeviceSelector} 为唯一裁决点，
     * 与 DJL（{@code DjlModelFactory}）路径保持一致：需同时满足 NVIDIA 驱动可探测
     * （nvidia-smi）与 classpath 含 CUDA provider 原生库。</p>
     *
     * @return true 表示应使用 CUDA
     */
    public static boolean isGpu() {
        return "gpu".equals(com.chua.deeplearning.support.engine.DeviceSelector.resolve(null));
    }

    /**
     * 判断指定模型是否应启用 GPU（读取该 modelId 的模型级 device 参数）。
     *
     * @param modelId 模型标识
     * @return true 表示该模型应使用 CUDA
     */
    public static boolean isGpu(String modelId) {
        String device = com.chua.deeplearning.support.engine.ModelParams.resolveDevice(modelId, null);
        return "gpu".equals(device);
    }

    /**
     * 判断 classpath 中是否具备 CUDA provider 原生库。
     * <p>CPU 版 onnxruntime 构件不含该库，此时即便驱动存在也无法真正走 CUDA。</p>
     *
     * @return true 表示原生库存在，可以启用 CUDA
     */
    public static boolean cudaAvailable() {
        ClassLoader loader = GpuHelper.class.getClassLoader();
        if (loader == null) {
            return false;
        }
        return loader.getResource(CUDA_MARKER_WINDOWS) != null
                || loader.getResource(CUDA_MARKER_LINUX) != null;
    }

    /**
     * 如果应启用 GPU 且 CUDA 原生库可用，则给会话选项追加 CUDA provider。
     * <p>初始化失败时静默回退为 CPU，不抛异常。</p>
     *
     * @param opts ONNX Runtime 会话选项
     */
    public static void apply(OrtSession.SessionOptions opts) {
        apply(opts, isGpu());
    }

    /**
     * 按模型级 device 参数决定是否为会话追加 CUDA provider。
     *
     * @param opts    ONNX Runtime 会话选项
     * @param modelId 模型标识
     */
    public static void apply(OrtSession.SessionOptions opts, String modelId) {
        if (opts == null || modelId == null || modelId.isBlank()) {
            apply(opts);
            return;
        }
        apply(opts, isGpu(modelId));
    }

    /**
     * 是否追加 CUDA provider 的实际执行逻辑。
     *
     * @param opts  ONNX Runtime 会话选项
     * @param want true 表示请求启用 GPU
     */
    private static void apply(OrtSession.SessionOptions opts, boolean want) {
        if (opts == null || !want) {
            return;
        }
        if (!cudaAvailable()) {
            log.warn("[GpuHelper] 判定为 GPU 模式，但 classpath 中缺少 onnxruntime_providers_cuda 原生库，"
                    + "继续使用 CPU（如需 GPU 加速请改用 onnxruntime_gpu 构件）");
            return;
        }
        try {
            opts.addCUDA(new OrtCUDAProviderOptions());
            log.info("[GpuHelper] CUDA provider 已启用");
        } catch (Throwable e) {
            log.warn("[GpuHelper] CUDA provider 初始化失败，回退 CPU: {}", e.getMessage());
        }
    }
}
