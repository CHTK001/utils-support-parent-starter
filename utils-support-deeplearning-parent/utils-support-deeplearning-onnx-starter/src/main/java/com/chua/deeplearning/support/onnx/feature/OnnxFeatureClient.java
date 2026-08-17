package com.chua.deeplearning.support.onnx.feature;

import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class OnnxFeatureClient implements FeatureClient {

    private final FeatureClientSetting setting;
    private ITranslator<Object, Object> translator;
    private String resolvedModelId;

    public OnnxFeatureClient(FeatureClientSetting setting) {
        this.setting = setting;
    }

    @Override
    public FeatureClient model(String model) {
        setting.setModel(model);
        translator = null;
        return this;
    }

    private synchronized ITranslator<Object, Object> getTranslator() throws Exception {
        if (translator == null) {
            String modelId = setting.getModel();
            if (modelId == null || modelId.isBlank()) modelId = "resnet50-feature";
            resolvedModelId = modelId;
            ModelRegistry.discoverAll();
            Path modelPath = ModelRegistry.resolveModelPath(modelId);
            translator = ModelRegistry.createTranslator(modelId, modelPath);
            log.info("[OnnxFeatureClient] 模型加载完成: {} -> {}", modelId, modelPath);
        }
        return translator;
    }

    @Override
    public float[] extract(String text) {
        throw new UnsupportedOperationException("文本特征提取请使用 EmbeddingClient");
    }

    @Override
    public float[] extractImage(byte[] imageData) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
            Image input = ImageFactory.getInstance().fromImage(img);
            ITranslator<Object, Object> t = getTranslator();
            float[] result = (float[]) t.translate(input);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("[OnnxFeatureClient] 图像特征提取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (translator != null) {
            try { ((AutoCloseable) translator).close(); } catch (Exception ignore) {}
        }
    }
}