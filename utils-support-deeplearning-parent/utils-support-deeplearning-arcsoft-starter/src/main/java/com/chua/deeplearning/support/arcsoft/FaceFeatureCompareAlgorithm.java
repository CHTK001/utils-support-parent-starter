package com.chua.deeplearning.support.arcsoft;

import com.arcsoft.face.EngineConfiguration;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceSimilar;
import com.arcsoft.face.FunctionConfiguration;
import com.arcsoft.face.enums.DetectMode;
import com.arcsoft.face.enums.DetectOrient;
import com.arcsoft.face.enums.ErrorInfo;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ArrayUtils;
import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.deeplearning.support.environment.DeeplearningEnvironment;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
   * 基于虹软 arcsoft SDK 的人脸特征相似度比较算法
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("arcsoft")
public class FaceFeatureCompareAlgorithm implements VectorCompareAlgorithm, AutoCloseable {

    /** 人脸引擎 */
    /** Face引擎 */
    private final FaceEngine faceEngine;

    /** 创建 face特征comparealgorithm 实例 */
    public FaceFeatureCompareAlgorithm() {
        DeeplearningEnvironment env = DeeplearningEnvironment.of("arcsoft");
        String modelPath = env.getModelPath();
        if (modelPath == null || modelPath.isEmpty()) {
            throw new IllegalStateException("人脸识别引擎路径未配置，请设置 deeplearning.arcsoft.model-path");
        }

        Path dllDir = Path.of(modelPath).resolve("arcsoft");
        try {
            Files.createDirectories(dllDir);
        } catch (Exception e) {
            throw new IllegalStateException("创建 DLL 目录失败: " + dllDir, e);
        }

        NativeLoader.of("arcsoft")
            .from(FaceFeatureCompareAlgorithm.class.getClassLoader())
            .toTarget(dllDir)
            .glob("libarcsoft_face*")
            .extractOnly(true)
            .load();

        String libPath = dllDir.toAbsolutePath().toString();
        if (!Files.exists(dllDir.resolve("libarcsoft_face_engine_jni.dll"))
                && !Files.exists(dllDir.resolve("libarcsoft_face_engine_jni.so"))) {
            throw new IllegalStateException("虹软 DLL 未找到: " + libPath);
        }

        log.info("[FaceFeatureCompareAlgorithm] 加载 DLL 目录: {}", libPath);
        faceEngine = new FaceEngine(libPath);

        String appId = env.getAppId();
        String appSecret = env.getAppSecret();
        if (appId != null && !appId.isEmpty() && appSecret != null && !appSecret.isEmpty()) {
            int code = faceEngine.activeOnline(appId, appSecret);
            if (code != ErrorInfo.MOK.getValue() && code != ErrorInfo.MERR_ASF_ALREADY_ACTIVATED.getValue()) {
                throw new IllegalStateException("人脸识别引擎激活失败，错误码: " + code);
            }
        } else {
            log.warn("[FaceFeatureCompareAlgorithm] appId 或 appSecret 未配置，跳过激活");
        }

        EngineConfiguration cfg = new EngineConfiguration();
        cfg.setDetectMode(DetectMode.ASF_DETECT_MODE_IMAGE);
        cfg.setDetectFaceOrientPriority(DetectOrient.ASF_OP_ALL_OUT);
        cfg.setDetectFaceMaxNum(1);
        FunctionConfiguration func = new FunctionConfiguration();
        func.setSupportFaceDetect(true);
        cfg.setFunctionConfiguration(func);
        int code = faceEngine.init(cfg);
        if (code != ErrorInfo.MOK.getValue()) {
            throw new IllegalStateException("人脸识别引擎初始化失败，错误码: " + code);
        }
        log.info("[FaceFeatureCompareAlgorithm] 引擎初始化完成");
    }

    @Override
    /** 关闭 */
    public void close() {
        faceEngine.unInit();
    }

    @Override
    /** 名称 */
    public String name() {
        return "FACE_COMPARE";
    }

    @Override
    /** 比较 */
    public float compare(float[] feature1, float[] feature2) {
        if (feature1 == null || feature2 == null) {
            return 0.0f;
        }
        FaceFeature ff1 = new FaceFeature();
        ff1.setFeatureData(ArrayUtils.transToByteArray(feature1));
        FaceFeature ff2 = new FaceFeature();
        ff2.setFeatureData(ArrayUtils.transToByteArray(feature2));
        FaceSimilar similar = new FaceSimilar();
        faceEngine.compareFaceFeature(ff1, ff2, similar);
        return 1f - Math.min(similar.getScore(), 100f) / 100f;
    }
}
