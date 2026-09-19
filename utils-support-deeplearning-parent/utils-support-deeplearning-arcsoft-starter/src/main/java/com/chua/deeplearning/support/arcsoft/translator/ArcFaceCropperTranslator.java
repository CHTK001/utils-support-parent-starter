package com.chua.deeplearning.support.arcsoft.translator;

import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.toolkit.ImageFactory;
import com.arcsoft.face.toolkit.ImageInfo;
import com.chua.common.support.converter.Converter;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * arcsoft 人脸裁剪翻译器
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ArcFaceCropperTranslator implements ITranslator<Object, BufferedImage> {

    /**
     * 人脸引擎
    */
    private final FaceEngine faceEngine;

    /**
     * 创建 arcfacecroppertranslator 实例
     * @param faceEngine faceengine
     */
    public ArcFaceCropperTranslator(FaceEngine faceEngine) {
        this.faceEngine = faceEngine;
    }

    @Override
    /**
     * 名称
    */
    public String name() {
        return "arcface-cropper";
    }

    @Override
    /**
     * Translate
    */
    public BufferedImage translate(Object input) {
        BufferedImage image = Converter.convertIfNecessary(input, BufferedImage.class);
        if (image == null || faceEngine == null) {
            return image;
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
                log.debug("[ArcFaceCropper] 未检测到人脸，返回原图");
                return image;
            }

            FaceInfo faceInfo = faceInfoList.getFirst();
            var rect = faceInfo.getRect();
            
            if (rect == null) {
                return image;
            }
            
            int x = Math.max(0, rect.getLeft());
            int y = Math.max(0, rect.getTop());
            int width = Math.min(rect.getRight() - rect.getLeft(), image.getWidth() - x);
            int height = Math.min(rect.getBottom() - rect.getTop(), image.getHeight() - y);
            
            if (width <= 0 || height <= 0) {
                return image;
            }
            
            return ImageUtils.toBufferedImage(ImageUtils.crop(ImageUtils.toMat(image), x, y, width, height));
            
        } catch (Exception e) {
            log.warn("[ArcFaceCropper] 裁剪失败: {}", e.getMessage());
            return image;
        }
    }
}
