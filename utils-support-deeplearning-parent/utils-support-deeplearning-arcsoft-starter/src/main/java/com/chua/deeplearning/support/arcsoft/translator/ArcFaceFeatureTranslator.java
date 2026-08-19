package com.chua.deeplearning.support.arcsoft.translator;

import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.toolkit.ImageFactory;
import com.arcsoft.face.toolkit.ImageInfo;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.ArrayUtils;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * ArcSoft 人脸特征提取翻译器
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ArcFaceFeatureTranslator implements ITranslator<Object, float[]> {

    /** 人脸引擎 */
    /** Face引擎 */
    private final FaceEngine faceEngine;

    /**
     * 创建 ArcFaceFeatureTranslator 实例
     * @param faceEngine faceEngine
     */
    public ArcFaceFeatureTranslator(FaceEngine faceEngine) {
        this.faceEngine = faceEngine;
    }

    @Override
    /** Name */
    public String name() {
        return "arcface-feature";
    }

    @Override
    /** Translate */
    public float[] translate(Object input) {
        BufferedImage image = Converter.convertIfNecessary(input, BufferedImage.class);
        if (image == null || faceEngine == null) {
            return new float[0];
        }

        try {
            ImageInfo imageInfo = ImageFactory.bufferedImage2ImageInfo(image);
            List<FaceInfo> faceInfoList = new ArrayList<>();
            faceEngine.detectFaces(
                imageInfo.getImageData(),
                imageInfo.getWidth(),
                imageInfo.getHeight(),
                imageInfo.getImageFormat(),
                faceInfoList
            );

            if (faceInfoList.isEmpty()) {
                log.debug("[ArcFaceFeature] 未检测到人脸");
                return new float[0];
            }

            FaceInfo faceInfo = faceInfoList.get(0);
            com.arcsoft.face.FaceFeature faceFeature = new com.arcsoft.face.FaceFeature();
            faceEngine.extractFaceFeature(
                imageInfo.getImageData(),
                imageInfo.getWidth(),
                imageInfo.getHeight(),
                imageInfo.getImageFormat(),
                faceInfo,
                faceFeature
            );

            return ArrayUtils.transToFloatArray(faceFeature.getFeatureData());
            
        } catch (Exception e) {
            log.warn("[ArcFaceFeature] 特征提取失败: {}", e.getMessage());
            return new float[0];
        }
    }
}
