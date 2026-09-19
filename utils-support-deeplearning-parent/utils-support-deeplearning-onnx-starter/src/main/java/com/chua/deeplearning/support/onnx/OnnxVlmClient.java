package com.chua.deeplearning.support.onnx;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.UnderstandResult;
import com.chua.deeplearning.support.image.UnderstandTask;
import com.chua.deeplearning.support.image.VlmClient;
import com.chua.deeplearning.support.translator.ITranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ONNX 运行时视觉语言模型客户端实现。
 *
 * <p>基于 {@link ModelRegistry} 加载指定的 ONNX 翻译器，
 * 将图像字节数据与任务 提示符 组合后调用翻译器完成推理。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OnnxVlmClient implements VlmClient {

    private static final Logger log = LoggerFactory.getLogger(OnnxVlmClient.class); // 日志

    /**
     * 当前使用的模型名称
    */
    private String modelName = "florence2";

    /**
     * 设置模型名称。
     *
     * @param model 模型标识（如 florence2）
     * @return 当前实例
     */
    @Override
    public VlmClient model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 对图像执行视觉理解推理。
     *
     * @param imageData 图像字节数组
     * @param task      理解任务类型
     * @return 理解结果，包含任务和文本
     * @throws IllegalStateException 当模型未在 模型registry 中注册时
     * @throws RuntimeException      当推理过程发生异常时
     */
    @Override
    public UnderstandResult understand(byte[] imageData, UnderstandTask task) {
        try {
            ModelRegistry.Entry entry = ModelRegistry.get(modelName);
            if (entry == null) {
                throw new IllegalStateException("Model not registered: " + modelName);
            }
            Object translator = ReflectUtils.instantiate(entry.translatorClassName());
            if (translator == null) {
                throw new IllegalStateException("Translator 实例化失败: " + entry.translatorClassName());
            }
            ITranslator<Object[], String> t = (ITranslator<Object[], String>) translator;
            String result = t.translate(new Object[]{imageData, task.prompt()});
            return new UnderstandResult(task, result);
        } catch (Exception e) {
            log.error("[OnnxVlmClient] Failed: {}", e.getMessage(), e);
            throw new RuntimeException("Image understanding failed: " + e.getMessage(), e);
        }
    }
}
