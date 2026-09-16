package com.chua.deeplearning.support.opencv;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.BulkModelProvider;
import com.chua.deeplearning.support.opencv.cascade.OpencvCascadeDetector;
import com.chua.deeplearning.support.opencv.face.OpencvFaceDetector;
import com.chua.deeplearning.support.opencv.pedestrian.OpencvHogPedestrianDetector;
import com.chua.deeplearning.support.opencv.quality.OpencvFaceQualityAssessor;
import com.chua.deeplearning.support.opencv.quality.OpencvImageQualityAssessor;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
* 纯 打开cv 批量模型提供者。
* <p>
* 一次注册人脸检测、眼睛、微笑、侧脸、人体、HOG 行人、图像质量、人脸质量等模型。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class OpencvModelProvider implements BulkModelProvider {

    /**
    * 模型提供方。
     */
    private static final String PROVIDER = "opencv";

    /**
    * 人脸模型路径。
     */
    private static final String FACE = "models/opencv/haarcascade_frontalface_default.xml";

    /**
    * 眼睛模型路径。
     */
    private static final String EYE = "models/opencv/haarcascade_eye.xml";

    /**
    * 微笑模型路径。
     */
    private static final String SMILE = "models/opencv/haarcascade_smile.xml";

    /**
    * 全身模型路径。
     */
    private static final String FULLBODY = "models/opencv/haarcascade_fullbody.xml";

    /**
    * 侧脸模型路径。
     */
    private static final String PROFILE = "models/opencv/haarcascade_profileface.xml";

    @Override
    /** 获取Definition */
    public TranslatorModelDefinition getDefinition() {
        List<TranslatorModelDefinition> all = getAll();
        return all.isEmpty() ? null : all.getFirst();
    }

    @Override
    /** 获取全部 */
    public List<TranslatorModelDefinition> getAll() {
        List<TranslatorModelDefinition> list = new ArrayList<>();
        add(list, "opencv-face-detector", FACE, () -> new OpencvFaceDetector(FACE));
        add(list, "opencv-eye-detector", EYE,
                () -> new OpencvCascadeDetector("opencv-eye-detector", EYE, "eye", 1.1, 3, 20, 20));
        add(list, "opencv-smile-detector", SMILE,
                () -> new OpencvCascadeDetector("opencv-smile-detector", SMILE, "smile", 1.1, 15, 20, 20));
        add(list, "opencv-profile-face-detector", PROFILE,
                () -> new OpencvCascadeDetector("opencv-profile-face-detector", PROFILE, "profile_face", 1.1, 3, 30, 30));
        add(list, "opencv-fullbody-detector", FULLBODY,
                () -> new OpencvCascadeDetector("opencv-fullbody-detector", FULLBODY, "fullbody", 1.1, 3, 40, 80));
        add(list, "opencv-pedestrian-hog", null, OpencvHogPedestrianDetector::new);
        add(list, "opencv-image-quality", null, OpencvImageQualityAssessor::new);
        add(list, "opencv-face-quality", FACE, () -> new OpencvFaceQualityAssessor(FACE));
        log.info("OpenCV 模型提供者共注册 {} 个模型", list.size());
        return list;
    }

    /**
    * 安全创建并加入模型定义。
    *
    * @param list      结果列表
    * @param modelId   模型 标识
    * @param modelPath 模型路径，可为空
    * @param supplier  翻译器工厂
     */
    private void add(List<TranslatorModelDefinition> list,
                     String modelId,
                     String modelPath,
                     Supplier<ITranslator<?, ?>> supplier) {
        try {
            ITranslator<?, ?> translator = supplier.get();
            long size = 0L;
            if (modelPath != null) {
                try {
                    File file = OpencvModelTranslator.resolveModelPath(modelPath);
                    if (file.exists()) {
                        size = file.length();
                    }
                } catch (Exception ignored) {
                }
            }
            list.add(TranslatorModelDefinition.builder()
                    .modelDefinition(ModelDefinition.builder()
                            .id(modelId)
                            .name(modelId)
                            .provider(PROVIDER)
                            .description(PROVIDER + ": " + modelId)
                            .build())
                    .translator(translator)
                    .modelSize(size)
                    .config(modelPath == null
                            ? Collections.emptyMap()
                            : Collections.singletonMap("path", modelPath))
                    .build());
        } catch (Exception e) {
            log.warn("注册 OpenCV 模型 [{}] 失败: {}", modelId, e.getMessage());
        }
    }
}
