package com.chua.deeplearning.support.onnx.feature;

import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
/** @author CH */

@Slf4j
public class OnnxFeatureClient implements FeatureClient {

    /** 设置 */
    private final FeatureClientSetting setting;
    /** 翻译器 */
    private ITranslator<Object, Object> translator;
    /** 解析后的模型标识 */
    /** Resolved模型ID */
    private String resolvedModelId;

    /**
     * 创建 OnnxFeatureClient 实例
     * @param setting setting
     */
    public OnnxFeatureClient(FeatureClientSetting setting) {
        this.setting = setting;
    }

    @Override
    /** Model */
    public FeatureClient model(String model) {
        setting.setModel(model);
        translator = null;
        return this;
    }

    /** 获取Translator */
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
    /** Extract */
    public float[] extract(String text) {
        throw new UnsupportedOperationException("文本特征提取请使用 EmbeddingClient");
    }

    @Override
    /** ExtractImage */
    public float[] extractImage(byte[] imageData) {
        try {
            BufferedImage img = ImageUtils.toBufferedImage(imageData);
            Image input = ImageFactory.getInstance().fromImage(img);
            ITranslator<Object, Object> t = getTranslator();
            float[] result = (float[]) t.translate(input);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("[OnnxFeatureClient] 图像特征提取失败: " + e.getMessage(), e);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (translator != null) {
            try { ((AutoCloseable) translator).close(); } catch (Exception ignore) {}
        }
    }
}

