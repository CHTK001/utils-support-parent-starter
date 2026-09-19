package com.chua.deeplearning.support.engine;

import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.translate.Translator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DJL 模型工厂。
 * <p>按模型路径后缀自动选择引擎：.pt/.pth → PyTorch，其余默认 OnnxRuntime。</p>
 *
 * <p>设备策略（{@link DeviceSelector}）：
 * <ul>
 *   <li>auto（默认）— 自动探测 NVIDIA 驱动 + onnxruntime_gpu 构件，可用则 GPU</li>
 *   <li>GPU 加载或推理失败时，<b>自动降级</b>为 CPU 并重建会话（粘性，后续请求保持 CPU）</li>
 *   <li>显式设置通过系统属性 {@code deeplearning.device=cpu|gpu} 或注册表
 * 期权 的 {@code device} 键注入</li>
 * </ul></p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DjlModelFactory implements AutoCloseable {

    /**
     * 模型名称。
     */
    private final String modelName;

    /**
     * 模型路径。
     */
    private final Path modelPath;

    /**
     * 引擎名称，可为 空（自动推断）。
     */
    private final String engineName;

    /**
     * 设备设置：空/blank 跟随系统属性 deeplearning.device（默认 auto）。
     */
    private final String deviceSetting;

    /**
     * Translator 工厂。
     */
    private final TranslatorFactory translatorFactory;

    /**
     * 是否已初始化。
     */
    private volatile boolean initialized;

    /**
     * GPU 失败后强制降级 CPU（粘性标记）。
     */
    private volatile boolean forceCpu;

    /**
     * 当前实际使用的设备："gpu" / "cpu"，未初始化为 空。
     */
    private volatile String deviceInUse;

    /**
     * DJL 模型实例。
     */
    private Model model;

    /**
     * DJL 预测器。
     */
    private Predictor<?, ?> predictor;

    /**
     * Translator 创建工厂。
     * @author CH
     * @since 4.0.0
     */
    @FunctionalInterface
    public interface TranslatorFactory {
        /**
         * 创建 Translator。
         *
         * @return Translator 实例
         */
        Translator<?, ?> create();
    }

    /**
     * 构造工厂（自动推断引擎，设备跟随全局设置）。
     *
     * @param modelName          模型名称
     * @param modelPath          模型路径
     * @param translatorFactory  Translator 工厂
     * @return djl模型工厂的结果
     */
    public DjlModelFactory(String modelName, Path modelPath, TranslatorFactory translatorFactory) {
        this(modelName, modelPath, null, translatorFactory);
    }

    /**
     * 构造工厂（设备跟随全局设置）。
     *
     * @param modelName          模型名称
     * @param modelPath          模型路径
     * @param engineName         引擎名称（onnxruntime / pytorch / PaddlePaddle / tensor流）
     * @param translatorFactory  Translator 工厂
     * @return djl模型工厂的结果
     */
    public DjlModelFactory(String modelName, Path modelPath, String engineName, TranslatorFactory translatorFactory) {
        this(modelName, modelPath, engineName, null, translatorFactory);
    }

    /**
     * 构造工厂（指定设备设置）。
     *
     * @param modelName          模型名称
     * @param modelPath          模型路径
     * @param engineName         引擎名称，可为 空（自动推断）
     * @param deviceSetting      设备设置：auto / cpu / gpu / cuda，可为 空
     * @param translatorFactory  Translator 工厂
     */
    public DjlModelFactory(String modelName, Path modelPath, String engineName,
                           String deviceSetting, TranslatorFactory translatorFactory) {
        this.modelName = modelName;
        this.modelPath = modelPath;
        this.engineName = engineName;
        this.deviceSetting = deviceSetting;
        this.translatorFactory = translatorFactory;
    }

    /**
     * 根据路径推断引擎名。
     *
     * @param path 模型路径
     * @return 引擎名
     */
    public static String resolveEngine(Path path) {
        if (path == null) {
            return "OnnxRuntime";
        }
        String name = path.getFileName().toString().toLowerCase();
        String full = path.toString().toLowerCase().replace('\\', '/');
        if (name.endsWith(".pt") || name.endsWith(".pth") || full.contains("/pytorch/") || full.contains("/pt/")) {
            return "PyTorch";
        }
        if (name.endsWith(".pdmodel") || name.endsWith(".pdiparams") || full.contains("/paddle/")) {
            return "PaddlePaddle";
        }
        if (name.endsWith(".pb") || name.endsWith(".savedmodel") || full.contains("/tensorflow/") || full.contains("/tf/")) {
            return "TensorFlow";
        }
        return "OnnxRuntime";
    }

    /**
     * 解析本次应使用的设备。
     *
     * @return "gpu" 或 "cpu"
     */
    private String resolveRequestedDevice() {
        return forceCpu ? "cpu" : DeviceSelector.resolve(deviceSetting);
    }

    /**
     * ensure初始化
    */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            String requested = resolveRequestedDevice();
            try {
                doInit(requested);
                initialized = true;
                deviceInUse = requested;
                log.info("[deeplearning-engine] DJL 模型加载成功: engine={}, name={}, path={}, device={}",
                        engineName, modelName, modelPath, requested.toUpperCase());
            } catch (Throwable e) {
                if ("gpu".equals(requested)) {
                    // GPU 初始化失败：自动降级 CPU 重试一次
                    log.warn("[deeplearning-engine] GPU 初始化失败，自动降级 CPU: {} -> {}",
                            e.getMessage(), modelPath);
                    forceCpu = true;
                    releaseQuietly();
                    try {
                        doInit("cpu");
                        initialized = true;
                        deviceInUse = "cpu";
                        log.info("[deeplearning-engine] 已降级 CPU 加载成功: name={}", modelName);
                        return;
                    } catch (Exception cpuError) {
                        releaseQuietly();
                        throw new RuntimeException(
                                "DJL 模型加载失败(GPU 已降级仍失败): " + modelName + " -> " + modelPath, cpuError);
                    }
                }
                throw new RuntimeException("DJL 模型加载失败: " + modelName + " -> " + modelPath, e);
            }
        }
    }

    /**
     * 执行一次按指定设备的加载。
     *
     * @param device "gpu" / "cpu"
     * @throws Exception 加载异常
     */
    private void doInit(String device) throws Exception {
        String engine = (engineName == null || engineName.isBlank())
                ? resolveEngine(modelPath)
                : engineName;
        model = Model.newInstance(modelName, engine);
 // ONNX Runtime 引擎依据该属性决定是否启用 CUDA EP（ort模型.加载 读取）
        model.setProperty("ortDevice", "gpu".equals(device) ? "cuda" : "cpu");

        Path loadPath = modelPath;
        if (loadPath != null && Files.isRegularFile(loadPath)) {
            // 目录 + 文件名 加载，兼容 .pt/.onnx
            Path parent = loadPath.getParent();
            String fileName = loadPath.getFileName().toString();
            if (parent != null) {
                model.load(parent, stripExtension(fileName));
            } else {
                model.load(loadPath);
            }
        } else if (loadPath != null) {
            model.load(loadPath);
        } else {
            throw new IllegalArgumentException("模型路径为空: " + modelName);
        }
        predictor = model.newPredictor(translatorFactory.create());
    }

    /**
     * 静默释放已创建的预测器与模型。
     */
    private void releaseQuietly() {
        try {
            if (predictor != null) {
                predictor.close();
            }
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
        try {
            if (model != null) {
                model.close();
            }
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
        predictor = null;
        model = null;
        initialized = false;
    }

    /**
     * 去掉扩展名。
     *
     * @param fileName 文件名
     * @return 无扩展名名称
     */
    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx <= 0) {
            return fileName;
        }
        return fileName.substring(0, idx);
    }

    /**
     * 执行推理。
     *
     * <p>GPU 推理抛出异常时视为 CUDA 运行时故障：自动降级 CPU 并重建会话后重试一次，
     * 后续请求保持 CPU（粘性），进程内不再反复尝试 GPU。</p>
     *
     * @param input 输入
     * @param <I>   输入类型
     * @param <O>   输出类型
     * @return 输出
     */
    @SuppressWarnings("unchecked")
    public <I, O> O predict(I input) {
        ensureInitialized();
        boolean retriedAfterFallback = false;
        while (true) {
            try {
                return ((Predictor<I, O>) predictor).predict(input);
            } catch (Throwable e) {
                if (!retriedAfterFallback && "gpu".equals(deviceInUse) && !forceCpu) {
                    log.warn("[deeplearning-engine] GPU 推理失败，降级 CPU 并重试: {} -> {}",
                            modelName, e.getMessage());
                    synchronized (this) {
                        releaseQuietly();
                        forceCpu = true;
                    }
                    ensureInitialized();
                    retriedAfterFallback = true;
                    continue;
                }
                throw new RuntimeException("DJL 推理失败: " + modelName
                        + (deviceInUse != null ? " [device=" + deviceInUse + "]" : ""), e);
            }
        }
    }

    /**
     * 当前实际使用的设备。
     *
     * @return "gpu" / "cpu"；未初始化时返回 空
     */
    public String deviceInUse() {
        return deviceInUse;
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        releaseQuietly();
    }
}
