package com.chua.image.support.filter.lama;

import com.chua.common.support.base.reflection.ConstructorStation;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * lama ONNX推理类
 * <p>
 * 基于ONNX Runtime实现的lama图像修复模型推理
 * 支持CPU和GPU推理，提供图像修复功能
 * </p>
 * 
 * <p>
 * 使用此类需要添加以下依赖：
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;com.microsoft.onnxruntime&lt;/groupId&gt;
 *     &lt;artifactId&gt;onnxruntime&lt;/artifactId&gt;
 *     &lt;version&gt;1.17.1&lt;/version&gt;
 * &lt;/dependency&gt;
 * </pre>
 * </p>
 *
 * @author CH
 * @since 2024/7/29
 */
@Slf4j
public class LaMaOnnxInfer implements AutoCloseable {

    /** 配置 */
    private final LaMaConfiguration config;
    /** ort环境 */
    private Object ortEnvironment;
    /** ort会话 */
    private Object ortSession;
    /** 是否已初始化 */
    private boolean initialized = false;

    /**
     * 构造函数
     *
     * @param config lama配置
     */
    public LaMaOnnxInfer(LaMaConfiguration config) {
        this.config = config;
        this.config.validate();
        initialize();
    }

    /**
     * 初始化ONNX Runtime
     */
    private void initialize() {
        try {
            // 使用反射加载ONNX Runtime类，避免编译时依赖
            Class<?> ortEnvClass = ClassUtils.forName("ai.onnxruntime.OrtEnvironment");
            Class<?> ortSessionClass = ClassUtils.forName("ai.onnxruntime.OrtSession");
            Class<?> sessionOptionsClass = ClassUtils.forName("ai.onnxruntime.OrtSession$SessionOptions");
            Class<?> optLevelClass = ClassUtils.forName("ai.onnxruntime.OrtSession$SessionOptions$OptLevel");
            Class<?> logLevelClass = ClassUtils.forName("ai.onnxruntime.OrtLoggingLevel");

            if (ortEnvClass == null || sessionOptionsClass == null || optLevelClass == null || logLevelClass == null) {
                throw new RuntimeException("未找到ONNX Runtime依赖，请添加相应的Maven依赖");
            }

            // 获取环境实例
            Method getEnvironmentMethod = ClassUtils.findMethod(ortEnvClass, "getEnvironment");
            if (getEnvironmentMethod != null) {
                ortEnvironment = ClassUtils.invokeMethod(getEnvironmentMethod, null);
            }

            // 创建会话选项
            Constructor<?> constructor = ClassUtils.getConstructor(sessionOptionsClass, new Class<?>[0]);
            Object sessionOptions = ConstructorStation.newInstance(constructor);

            // 设置线程数
            Method setInterOpNumThreadsMethod = ClassUtils.findMethod(sessionOptionsClass, "setInterOpNumThreads", int.class);
            if (setInterOpNumThreadsMethod != null) {
                ClassUtils.invokeMethod(setInterOpNumThreadsMethod, sessionOptions, config.getThreads());
            }

            // 设置优化级别
            var basicOptField = ClassUtils.findField(optLevelClass, "BASIC_OPT");
            Object basicOpt = ClassUtils.getFieldValue(basicOptField, null);
            Method setOptimizationLevelMethod = ClassUtils.findMethod(sessionOptionsClass, "setOptimizationLevel", optLevelClass);
            if (setOptimizationLevelMethod != null && basicOpt != null) {
                ClassUtils.invokeMethod(setOptimizationLevelMethod, sessionOptions, basicOpt);
            }

            // 设置日志级别
            var errorLevelField = ClassUtils.findField(logLevelClass, "ORT_LOGGING_LEVEL_ERROR");
            Object errorLevel = ClassUtils.getFieldValue(errorLevelField, null);
            Method setSessionLogLevelMethod = ClassUtils.findMethod(sessionOptionsClass, "setSessionLogLevel", logLevelClass);
            if (setSessionLogLevelMethod != null && errorLevel != null) {
                ClassUtils.invokeMethod(setSessionLogLevelMethod, sessionOptions, errorLevel);
            }

            // 如果启用GPU
            if (config.isUseGpu()) {
                try {
                    Method addCUDAMethod = ClassUtils.findMethod(sessionOptionsClass, "addCUDA", int.class);
                    if (addCUDAMethod != null) {
                        ClassUtils.invokeMethod(addCUDAMethod, sessionOptions, config.getGpuDeviceId());
                        log.info("启用GPU加速，设备ID: {}", config.getGpuDeviceId());
                    }
                } catch (Exception e) {
                    log.warn("无法启用GPU加速，将使用CPU: {}", e.getMessage());
                }
            }

            // 验证模型文件存在
            if (!Files.exists(Paths.get(config.getModelPath()))) {
                throw new IllegalArgumentException("模型文件不存在: " + config.getModelPath());
            }

            // 创建会话
            Method createSessionMethod = ClassUtils.findMethod(ortEnvClass, "createSession", String.class, sessionOptionsClass);
            if (createSessionMethod != null) {
                ortSession = ClassUtils.invokeMethod(createSessionMethod, ortEnvironment, config.getModelPath(), sessionOptions);
            }

            initialized = true;
            log.info("LaMa ONNX推理器初始化成功: {}", config.getModelPath());

        } catch (Exception e) {
            throw new RuntimeException("初始化ONNX Runtime失败", e);
        }
    }

    /**
     * 执行图像修复推理
     *
     * @param image 输入图像
     * @return 修复后的图像
     */
    public BufferedImage infer(BufferedImage image) {
        return infer(image, null);
    }

    /**
     * 执行图像修复推理
     *
     * @param image 输入图像
     * @param mask  修复mask（可选）
     * @return 修复后的图像
     */
    public BufferedImage infer(BufferedImage image, BufferedImage mask) {
        if (!initialized) {
            throw new IllegalStateException("推理器未初始化");
        }

        try {
            // 保存原始尺寸
            int originalWidth = image.getWidth();
            int originalHeight = image.getHeight();

            // 预处理图像
            float[] imageData = LaMaImageUtils.imageToTensor(image, config);
            float[] maskData;

            if (mask != null) {
                // 使用提供的mask
                maskData = LaMaImageUtils.imageToTensor(mask, config);
                // 转换为单通道mask（取R通道）
                int size = config.getInputSize();
                float[] singleChannelMask = new float[size * size];
                for (int i = 0; i < size * size; i++) {
                    singleChannelMask[i] = imageData[i] > 0.5f ? 1.0f : 0.0f;
                }
                maskData = singleChannelMask;
            } else {
                // 生成mask
                maskData = LaMaImageUtils.generateMask(image, config);
            }

            // 执行推理
            float[] outputData = runInference(imageData, maskData);

            // 后处理
            BufferedImage result = LaMaImageUtils.tensorToImage(outputData, config);

            // 应用后处理优化
            result = LaMaImageUtils.applyPostProcessing(result, config);

            // 如果需要保持原始尺寸
            if (config.isKeepOriginalSize() && 
                (originalWidth != config.getInputSize() || originalHeight != config.getInputSize())) {
                result = LaMaImageUtils.resizeImage(result, originalWidth, originalHeight);
            }

            // 应用边缘羽化
            if (config.getFeatherRadius() > 0) {
                BufferedImage originalResized = config.isKeepOriginalSize() ? 
                    image : LaMaImageUtils.resizeImage(image, config.getInputSize(), config.getInputSize());
                result = LaMaImageUtils.applyFeathering(originalResized, result, maskData, config.getFeatherRadius());
            }

            return result;

        } catch (Exception e) {
            log.error("图像修复推理失败", e);
            throw new RuntimeException("图像修复推理失败", e);
        }
    }

    /**
     * 执行ONNX推理
     *
     * @param imageData 图像数据
     * @param maskData  mask数据
     * @return 推理结果
     */
    private float[] runInference(float[] imageData, float[] maskData) throws Exception {
        Class<?> onnxTensorClass = ClassUtils.forName("ai.onnxruntime.OnnxTensor");
        Class<?> ortSessionClass = ClassUtils.forName("ai.onnxruntime.OrtSession");
        if (onnxTensorClass == null || ortSessionClass == null) {
            throw new IllegalStateException("ONNX Runtime 类未找到，请添加 onnxruntime 依赖");
        }

        // 创建输入张量
        Method createTensorMethod = ClassUtils.findMethod(onnxTensorClass, "createTensor", Object.class, long[].class);
        if (createTensorMethod == null) {
            throw new IllegalStateException("createTensor 方法未找到");
        }
        Object imageTensor = ClassUtils.invokeMethod(createTensorMethod, null, ortEnvironment, imageData, config.getInputShape());
        Object maskTensor = ClassUtils.invokeMethod(createTensorMethod, null, ortEnvironment, maskData, config.getMaskShape());

        // 准备输入
        Map<String, Object> inputs = new HashMap<>();
        
        // 获取输入名称（通常LaMa模型的输入名称是"image"和"mask"）
        Method getInputNamesMethod = ClassUtils.findMethod(ortSessionClass, "getInputNames");
        if (getInputNamesMethod == null) {
            throw new IllegalStateException("getInputNames 方法未找到");
        }
        Object inputNames = ClassUtils.invokeMethod(getInputNamesMethod, ortSession);
        Method toArrayMethod = ClassUtils.findMethod(inputNames.getClass(), "toArray", Class.class);
        if (toArrayMethod == null) {
            throw new IllegalStateException("toArray 方法未找到");
        }
        String[] nameArray = (String[]) ClassUtils.invokeMethod(toArrayMethod, inputNames, String[].class);

        if (nameArray.length >= 2) {
            // 通常是"image"
            inputs.put(nameArray[0], imageTensor);
            // 通常是"mask"
            inputs.put(nameArray[1], maskTensor);
        } else {
 // 如果只有一个输入，可能需要合并镜像和mask
            inputs.put(nameArray[0], imageTensor);
        }

        // 执行推理
        Method runMethod = ClassUtils.findMethod(ortSessionClass, "run", Map.class);
        if (runMethod == null) {
            throw new IllegalStateException("run 方法未找到");
        }
        Object result = ClassUtils.invokeMethod(runMethod, ortSession, inputs);

        try {
            // 获取输出
            Method getMethod = ClassUtils.findMethod(result.getClass(), "get", int.class);
            if (getMethod == null) {
                throw new IllegalStateException("get 方法未找到");
            }
            Object outputValue = ClassUtils.invokeMethod(getMethod, result, 0);
            Method getValueMethod = ClassUtils.findMethod(outputValue.getClass(), "getValue");
            if (getValueMethod == null) {
                throw new IllegalStateException("getValue 方法未找到");
            }
            Object tensorValue = ClassUtils.invokeMethod(getValueMethod, outputValue);

            // 转换输出数据
            float[] outputData;
if (tensorValue instanceof float[][][]) {
                float[][][] output3D = (float[][][]) tensorValue;
 // 取第一个批量
                outputData = flatten3DArray(output3D);
            } else if (tensorValue instanceof float[][]) {
                float[][] output2D = (float[][]) tensorValue;
                outputData = flatten2DArray(output2D);
            } else {
                outputData = (float[]) tensorValue;
            }

            return outputData;

        } finally {
            // 清理资源
            try {
                Method closeMethod = ClassUtils.findMethod(imageTensor.getClass(), "close");
                if (closeMethod != null) {
                    ClassUtils.invokeMethod(closeMethod, imageTensor);
                    ClassUtils.invokeMethod(closeMethod, maskTensor);
                    ClassUtils.invokeMethod(closeMethod, result);
                }
            } catch (Exception e) {
                log.warn("清理推理资源时出错", e);
            }
        }
    }

    /**
     * 展平3D数组
     * @param array3D array3D
     * @return flatten3DArray的结果
     */
    private float[] flatten3DArray(float[][][] array3D) {
        int channels = array3D[0].length;
        int spatial = array3D[0][0].length;
        
        float[] flattened = new float[channels * spatial];
        
        for (int c = 0; c < channels; c++) {
            System.arraycopy(array3D[0][c], 0, flattened, c * spatial, spatial);
        }
        
        return flattened;
    }

    /**
     * 展平2D数组
     * @param array2D array2D
     * @return flatten2DArray的结果
     */
    private float[] flatten2DArray(float[][] array2D) {
        int height = array2D.length;
        int width = array2D[0].length;
        
        float[] flattened = new float[height * width];
        
        for (int h = 0; h < height; h++) {
            System.arraycopy(array2D[h], 0, flattened, h * width, width);
        }
        
        return flattened;
    }

    /**
     * 检查推理器是否已初始化
     *
     * @return 是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取配置信息
     *
     * @return 配置对象
     */
    public LaMaConfiguration getConfig() {
        return config;
    }

    /**
     * 获取模型信息
     *
     * @return 模型信息字符串
     */
    public String getModelInfo() {
        if (!initialized) {
            return "推理器未初始化";
        }

        try {
            Class<?> ortSessionClass = ClassUtils.forName("ai.onnxruntime.OrtSession");
            if (ortSessionClass == null) {
                return "ONNX Runtime 类未找到";
            }
            
            Method getInputNamesMethod = ClassUtils.findMethod(ortSessionClass, "getInputNames");
            Method getOutputNamesMethod = ClassUtils.findMethod(ortSessionClass, "getOutputNames");
            if (getInputNamesMethod == null || getOutputNamesMethod == null) {
                return "无法获取模型信息方法";
            }
            Object inputNames = ClassUtils.invokeMethod(getInputNamesMethod, ortSession);
            Object outputNames = ClassUtils.invokeMethod(getOutputNamesMethod, ortSession);
            
            return String.format("LaMa模型信息 - 输入: %s, 输出: %s, 尺寸: %dx%d", 
                inputNames.toString(), outputNames.toString(), 
                config.getInputSize(), config.getInputSize());
                
        } catch (Exception e) {
            return "无法获取模型信息: " + e.getMessage();
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (initialized && ortSession != null) {
            try {
                Method closeMethod = ClassUtils.findMethod(ortSession.getClass(), "close");
                if (closeMethod != null) {
                    ClassUtils.invokeMethod(closeMethod, ortSession);
                }
                log.info("LaMa ONNX推理器关闭");
            } catch (Exception e) {
                log.error("关闭ONNX会话时出错", e);
            }
        }
        initialized = false;
    }
}
