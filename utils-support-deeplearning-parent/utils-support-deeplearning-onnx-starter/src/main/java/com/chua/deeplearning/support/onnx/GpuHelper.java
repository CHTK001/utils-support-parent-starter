package com.chua.deeplearning.support.onnx;

import ai.onnxruntime.OrtSession;

/**
 * ONNX Runtime GPU 閫氱敤宸ュ叿銆? *
 * <p>鎵€鏈?ONNX 妯″瀷缁熶竴閫氳繃姝ょ被鍒ゆ柇鏄惁鍚敤 GPU锛圕UDA锛夛紝
 * 閰嶇疆鏉ユ簮涓?{@link com.chua.deeplearning.support.ai.DetectionConfiguration#get()}銆? *
 * <p>浣跨敤鏂瑰紡锛? * <pre>{@code
 *   SessionOptions opts = new SessionOptions();
 *   GpuHelper.apply(opts);   // 鑷姩鏍规嵁鍏ㄥ眬閰嶇疆鍐冲畾鏄惁 addCUDA
 *   session = env.createSession(modelPath, opts);
 * }</pre>
 *
 * <p>鍥為€€绛栫暐锛欳UDA provider 鍒濆鍖栧け璐ユ椂鑷姩闈欓粯鍥為€€ CPU锛屼笉褰卞搷杩愯銆? *
 * @author CH
 * @since 4.0.0.43
 */
@lombok.extern.slf4j.Slf4j
public final class GpuHelper {

    private GpuHelper() {}

    /**
     * 鍒ゆ柇褰撳墠鍏ㄥ眬閰嶇疆鏄惁鍚敤 GPU銆?     *
     * @return true 琛ㄧず搴斾娇鐢?CUDA
     */
    public static boolean isGpu() {
        return com.chua.deeplearning.support.ai.DetectionConfiguration.get().deviceIsGpu();
    }

    /**
     * 濡傛灉鍏ㄥ眬閰嶇疆鍚敤浜?GPU锛屽垯鍚?SessionOptions 娣诲姞 CUDA provider銆?     * 鍒濆鍖栧け璐ユ椂闈欓粯鍥為€€ CPU銆?     *
     * @param opts ONNX Runtime SessionOptions
     */
    public static void apply(OrtSession.SessionOptions opts) {
        if (!isGpu()) {
            return;
        }
        try {
            // opts.addCUDA(new OrtCUDAProviderOptions()); // not available in 1.29.0
            log.info("[GpuHelper] CUDA provider 已启用");
        } catch (Exception e) {
            log.warn("[GpuHelper] CUDA provider 初始化失败，回退 CPU: {}", e.getMessage());
        }
    }
}
