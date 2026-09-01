package com.chua.deeplearning.support.onnx;

import ai.onnxruntime.OrtSession;

/**
 * ONNX Runtime GPU 通用工具。
 *
 * <p>所有 ONNX 模型统一通过此类判断是否启用 GPU（CUDA），
 * 配置来源为 {@link com.chua.deeplearning.support.ai.DetectionConfiguration#get()}。
 *
 * <p>使用方式：
 * <pre>{@code
 *   SessionOptions opts = new SessionOptions();
 *   GpuHelper.apply(opts);   // 自动根据全局配置决定是否 addCUDA
 *   session = env.createSession(modelPath, opts);
 * }</pre>
 *
 * <p>回退策略：CUDA provider 初始化失败时自动静默回退 CPU，不影响运行。
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class GpuHelper {

    private GpuHelper() {}

    /**
     * 判断当前全局配置是否启用 GPU。
     *
     * @return true 表示应使用 CUDA
     */
    public static boolean isGpu() {
        return com.chua.deeplearning.support.ai.DetectionConfiguration.get().deviceIsGpu();
    }

    /**
     * 如果全局配置启用了 GPU，则向 SessionOptions 添加 CUDA provider。
     * 初始化失败时静默回退 CPU。
     *
     * @param opts ONNX Runtime SessionOptions
     */
    public static void apply(OrtSession.SessionOptions opts) {
        if (!isGpu()) {
            return;
        }
        try {
            opts.addCUDA(new ai.onnxruntime.providers.OrtCUDAProviderOptions());
            System.out.println("[GpuHelper] CUDA provider 已启用");
        } catch (Exception e) {
            System.err.println("[GpuHelper] CUDA provider 初始化失败，回退 CPU: " + e.getMessage());
        }
    }
}
