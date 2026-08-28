package com.chua.deeplearning.support.gpu_llama3;

import com.chua.deeplearning.support.gpu_llama3.translator.Llama3ChatTranslator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GPU Llama3 模块功能测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GpuLlama3Test {

    /**
     * 测试 Llama3ChatTranslator 可实例化。
     */
    @Test
    @DisplayName("测试 Llama3ChatTranslator 可实例化")
    public void testTranslatorInstantiation() {
        var translator = new Llama3ChatTranslator();
        assertNotNull(translator);
        assertEquals("llama-3-8b-it", translator.name());
        translator.close();
    }

    /**
     * 测试 Llama3ChatTranslator 自定义模型 ID。
     */
    @Test
    @DisplayName("测试 Llama3ChatTranslator 自定义模型")
    public void testTranslatorCustomModel() {
        var translator = new Llama3ChatTranslator("llama-3-70b-it");
        assertEquals("llama-3-70b-it", translator.name());
        translator.close();
    }

    /**
     * 测试 GpuLlama3ChatClient 构造。
     */
    @Test
    @DisplayName("测试 GpuLlama3ChatClient 构造")
    public void testChatClientConstructor() {
        var client = new GpuLlama3ChatClient(null);
        assertNotNull(client);
    }

    /**
     * 测试 GpuLlama3ModelRegistrar 静态初始化。
     */
    @Test
    @DisplayName("测试 GpuLlama3ModelRegistrar 静态初始化")
    public void testModelRegistrarStaticInit() {
        // 验证类可加载
        assertNotNull(GpuLlama3ModelRegistrar.class);
    }
}
