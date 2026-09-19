package com.chua.deeplearning.support.arcsoft.translator;

import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.toolkit.ImageFactory;
import com.arcsoft.face.toolkit.ImageInfo;
import com.chua.common.support.converter.Converter;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * arcsoft 人脸检测翻译器
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ArcFaceDetectorTranslator implements ITranslator<Object, List<DetectionInfo>> {

    /** 人脸引擎 */
    /** Face引擎 */
    private final FaceEngine faceEngine;

    /**
     * 创建 arcfacedetectortranslator 实例
     * @param faceEngine faceengine
     */
    public ArcFaceDetectorTranslator(FaceEngine faceEngine) {
        this.faceEngine = faceEngine;
    }

    @Override
    /** 名称 */
    public String name() {
        return "arcface-detector";
    }

    @Override
    /** Translate */
    public List<DetectionInfo> translate(Object input) {
        BufferedImage image = Converter.convertIfNecessary(input, BufferedImage.class);
        if (image == null || faceEngine == null) {
            return new ArrayList<>();
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

            List<DetectionInfo> results = new ArrayList<>(faceInfoList.size());
            for (FaceInfo faceInfo : faceInfoList) {
                var rect = faceInfo.getRect();
                if (rect == null) {
                    continue;
                }
                
                float x = rect.getLeft();
                float y = rect.getTop();
                float width = rect.getRight() - rect.getLeft();
                float height = rect.getBottom() - rect.getTop();
                
                results.add(new DetectionInfo("face", 1.0f, x, y, width, height));
            }
            
            return results;
            
        } catch (Exception e) {
            log.warn("[ArcFaceDetector] 检测失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
