package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageSegmenter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxImageSegmenter implements ImageSegmenter {

    private String modelName;

    public OnnxImageSegmenter(String apiKey) {
    }

    @Override
    public ImageSegmenter model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "mask-rcnn";
    }

    @Override
    public ImageSegmenter modelPath(String path) {
        return ImageSegmenter.create(resolveModel()).modelPath(path);
    }

    @Override
    public ImageSegmenter device(String device) {
        return ImageSegmenter.create(resolveModel()).device(device);
    }

    @Override
    public byte[] segment(byte[] imageData) {
        return ImageSegmenter.create(resolveModel()).segment(imageData);
    }

    @Override
    public byte[] segment(byte[] imageData, int targetClass) {
        return ImageSegmenter.create(resolveModel()).segment(imageData, targetClass);
    }

}
