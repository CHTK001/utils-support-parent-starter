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
     * 引擎名称，可为 null（自动推断）。
     */
    private final String engineName;

    /**
     * Translator 工厂。
     */
    private final TranslatorFactory translatorFactory;

    /**
     * 是否已初始化。
     */
    private volatile boolean initialized;

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
     * 构造工厂（自动推断引擎）。
     *
     * @param modelName          模型名称
     * @param modelPath          模型路径
     * @param translatorFactory  Translator 工厂
     */
    public DjlModelFactory(String modelName, Path modelPath, TranslatorFactory translatorFactory) {
        this(modelName, modelPath, null, translatorFactory);
    }

    /**
     * 构造工厂。
     *
     * @param modelName          模型名称
     * @param modelPath          模型路径
     * @param engineName         引擎名称（OnnxRuntime / PyTorch / PaddlePaddle / TensorFlow）
     * @param translatorFactory  Translator 工厂
     */
    public DjlModelFactory(String modelName, Path modelPath, String engineName, TranslatorFactory translatorFactory) {
        this.modelName = modelName;
        this.modelPath = modelPath;
        this.engineName = engineName;
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

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        String engine = (engineName == null || engineName.isBlank())
                                ? resolveEngine(modelPath)
                                : engineName;
                        model = Model.newInstance(modelName, engine);
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
                        initialized = true;
                        log.info("DJL 模型加载成功: engine={}, name={}, path={}", engine, modelName, modelPath);
                    } catch (Exception e) {
                        throw new RuntimeException("DJL 模型加载失败: " + modelName + " -> " + modelPath, e);
                    }
                }
            }
        }
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
     * @param input 输入
     * @param <I>   输入类型
     * @param <O>   输出类型
     * @return 输出
     */
    @SuppressWarnings("unchecked")
    public <I, O> O predict(I input) {
        ensureInitialized();
        try {
            return ((Predictor<I, O>) predictor).predict(input);
        } catch (Exception e) {
            throw new RuntimeException("DJL 推理失败: " + modelName, e);
        }
    }

    @Override
    public void close() {
        if (predictor != null) {
            predictor.close();
        }
        if (model != null) {
            model.close();
        }
        initialized = false;
    }
}
