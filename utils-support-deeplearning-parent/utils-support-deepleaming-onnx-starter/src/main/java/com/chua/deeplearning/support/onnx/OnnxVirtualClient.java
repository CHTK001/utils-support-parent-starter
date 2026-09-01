package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.UnderstandResult;
import com.chua.deeplearning.support.image.UnderstandTask;
import com.chua.deeplearning.support.image.VirtualClient;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX 运行时虚拟视觉语言模型客户端实现。
 *
 * <p>通过 {@link ModelRegistry} 动态加载指定的 ONNX 翻译器，
 * 将图像字节数据与任务 prompt 组合后调用翻译器完成推理。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxVirtualClient implements VirtualClient {

    /** 当前使用的模型名称 */
    private String modelName = "florence2";

    /**
     * 设置模型名称。
     *
     * @param model 模型标识（如 florence2）
     * @return 当前实例
     */
    @Override
    public VirtualClient model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 对图像执行视觉理解推理。
     *
     * @param imageData 图像字节数组
     * @param task      理解任务类型
     * @return 理解结果
     * @throws IllegalStateException 当模型未在 ModelRegistry 中注册时
     * @throws RuntimeException      当推理过程发生异常时
     */
    @Override
    public UnderstandResult understand(byte[] imageData, UnderstandTask task) {
        try {
            var t = (com.chua.deeplearning.support.translator.ITranslator<Object[], String>)
                    com.chua.deeplearning.support.engine.ModelRegistry.getTranslator(
                            modelName,
                            com.chua.deeplearning.support.translator.ITranslator.class);
            if (t == null) {
                throw new IllegalStateException("图像理解模型未注册: " + modelName);
            }
            String result = t.translate(new Object[]{imageData, task.prompt()});
            return new UnderstandResult(task, result);
        } catch (Exception e) {
            log.error("[OnnxVirtualClient] 推理失败: {}", e.getMessage(), e);
            throw new RuntimeException("图像理解失败: " + e.getMessage(), e);
        }
    }
}
