package com.chua.deeplearning.support.arcsoft;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.arcsoft.translator.ArcFaceCropperTranslator;
import com.chua.deeplearning.support.arcsoft.translator.ArcFaceDetectorTranslator;
import com.chua.deeplearning.support.arcsoft.translator.ArcFaceFeatureTranslator;
import com.chua.deeplearning.support.engine.BulkModelProvider;
import com.chua.deeplearning.support.translator.TranslatorModelDefinition;
import com.arcsoft.face.FaceEngine;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
* arcsoft 模型提供者
* <p>
* 实现 bulk模型提供者 接口，提供 arcsoft 翻译器定义。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class ArcFaceModelProvider implements BulkModelProvider {

    /** 人脸引擎 */
    /** Face引擎 */
    private final FaceEngine faceEngine;

    /** 创建 arcface模型提供者 实例 */
    public ArcFaceModelProvider() {
        FaceEngine engine = null;
        try {
            engine = ArcFaceEngineFactory.create();
        } catch (Exception e) {
            log.warn("[ArcFace] 引擎初始化失败: {}", e.getMessage());
        }
        this.faceEngine = engine;
    }

    @Override
    /** 获取全部 */
    public List<TranslatorModelDefinition> getAll() {
        List<TranslatorModelDefinition> definitions = new ArrayList<>();

        // 人脸检测
        definitions.add(TranslatorModelDefinition.builder()
            .modelDefinition(ModelDefinition.builder()
                .id("arcface-detector")
                .name("arcface-detector")
                .provider("arcsoft")
                .description("ArcSoft 人脸检测")
                .build())
            .translator(new ArcFaceDetectorTranslator(faceEngine))
            .build());

        // 特征提取
        definitions.add(TranslatorModelDefinition.builder()
            .modelDefinition(ModelDefinition.builder()
                .id("arcface-feature")
                .name("arcface-feature")
                .provider("arcsoft")
                .description("ArcSoft 人脸特征提取")
                .build())
            .translator(new ArcFaceFeatureTranslator(faceEngine))
            .build());

        // 人脸裁剪
        definitions.add(TranslatorModelDefinition.builder()
            .modelDefinition(ModelDefinition.builder()
                .id("arcface-cropper")
                .name("arcface-cropper")
                .provider("arcsoft")
                .description("ArcSoft 人脸裁剪")
                .build())
            .translator(new ArcFaceCropperTranslator(faceEngine))
            .build());

        log.info("[ArcFace] 注册 {} 个模型", definitions.size());
        return definitions;
    }

    @Override
    /** 获取Definition */
    public TranslatorModelDefinition getDefinition() {
        List<TranslatorModelDefinition> all = getAll();
        return all != null && !all.isEmpty() ? all.get(0) : null;
    }
}
