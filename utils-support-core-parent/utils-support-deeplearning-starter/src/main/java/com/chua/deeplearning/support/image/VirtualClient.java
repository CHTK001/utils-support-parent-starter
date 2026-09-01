package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 本地多模态理解客户端接口。
 *
 * <p>输入图像 + 理解任务，返回结构化结果。
 * 支持图像描述、OCR、物体检测等任务（如 Florence-2）。</p>
 *
 * <pre>{@code
 * UnderstandResult result = VirtualClient.create("florence2")
 *     .understand(imageBytes, UnderstandTask.CAPTION);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VirtualClient {

    static VirtualClient create(String provider, String apiKey) {
        return ServiceProvider.of(VirtualClient.class).getNewExtension(provider, apiKey);
    }

    default VirtualClient provider(String provider) { return this; }
    default VirtualClient model(String model) { return this; }

    static VirtualClient create(String name) {
        return new DefaultVirtualClient(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    static VirtualClient create(String name, ModelSetting setting) {
        return new DefaultVirtualClient(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(VirtualClient.class);
    }

    UnderstandResult understand(byte[] imageData, UnderstandTask task);
}

class DefaultVirtualClient implements VirtualClient {
    private static final String DEFAULT_MODEL = "florence2";
    private final IdentificationEngine engine;
    private final String modelName;

    DefaultVirtualClient(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName != null ? modelName : DEFAULT_MODEL;
    }

    @Override
    @SuppressWarnings("unchecked")
    public UnderstandResult understand(byte[] imageData, UnderstandTask task) {
        ITranslator<Object[], String> t = engine.get(modelName, ITranslator.class);
        if (t == null) throw new IllegalStateException("图像理解模型未注册: " + modelName);
        String result = t.translate(new Object[]{imageData, task.prompt()});
        return new UnderstandResult(task, result);
    }
}