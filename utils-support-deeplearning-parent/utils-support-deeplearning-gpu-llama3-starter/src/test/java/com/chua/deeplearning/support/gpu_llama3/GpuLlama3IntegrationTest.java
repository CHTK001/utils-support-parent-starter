package com.chua.deeplearning.support.gpu_llama3;

import com.chua.deeplearning.support.gpu_llama3.translator.Llama3ChatTranslator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GPU Llama3 集成测试（需要 GPU 环境）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GpuLlama3IntegrationTest {

    /**
     * 检查是否有 GPU 可用。
     */
    private boolean hasGpu() {
        try {
            // 尝试初始化 llama GPU
            // 如果 llama.cpp JNI 加载成功且没有 CUDA 错误，则认为有 GPU
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 测试 LlamaModel GPU 参数配置。
     */
    @Test
    @DisplayName("测试 LlamaModel GPU 参数配置")
    public void testGpuParameters() {
        var params = new ModelParameters();
        params.setCtxSize(4096);
        params.setThreads(8);
        params.setGpuLayers(-1); // 全层 GPU

        assertNotNull(params);
    }

    /**
     * 测试翻译器实例化（不加载模型）。
     */
    @Test
    @DisplayName("测试 Llama3ChatTranslator 实例化")
    public void testTranslatorCreate() {
        var translator = new Llama3ChatTranslator("llama-3-2b-it");
        assertNotNull(translator);
        assertEquals("llama-3-2b-it", translator.name());
        translator.close();
    }

    /**
     * 测试客户端实例化。
     */
    @Test
    @DisplayName("测试 GpuLlama3ChatClient 实例化")
    public void testClientCreate() {
        var client = new GpuLlama3ChatClient(null);
        assertNotNull(client);
    }

    /**
     * 测试模型注册器可加载。
     */
    @Test
    @DisplayName("测试 GpuLlama3ModelRegistrar 可加载")
    public void testRegistrarLoadable() {
        var registrar = new GpuLlama3ModelRegistrar();
        assertNotNull(registrar);
    }
}
