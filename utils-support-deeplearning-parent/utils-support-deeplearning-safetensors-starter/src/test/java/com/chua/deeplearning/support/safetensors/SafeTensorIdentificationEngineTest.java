package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SafeTensorIdentificationEngine 集成测试。
 *
 * <p>测试引擎启动、自动下载（首次推理时）以及 LLM 推理流程。
 * 默认跳过：需手动设置 {@code -Dsafetensor.test=true} 启用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SafeTensorIdentificationEngineTest {

    @Test
    @EnabledIfSystemProperty(named = "safetensor.test", matches = "true")
    void testEngineWithMinimind2Small() throws Exception {
        log.info("=== SafeTensorIdentificationEngine 测试开始 ===");

        // 1) 创建引擎（自动启动 Python 服务）
        IdentificationEngine engine = new SafeTensorIdentificationEngine();
        log.info("引擎创建完成，服务已启动");

        // 2) 获取 MiniMind2-small 翻译器
        String modelId = "minimind2-small";
        ITranslator<Object, Object> translator = engine.get(modelId, ITranslator.class);
        assertNotNull(translator, "translator 不应为 null");
        log.info("translator 获取成功: {}", translator.getClass().getName());

        // 3) 构造输入
        Map<String, Object> input = Map.of("text", "你好，请用一句话介绍自己");

        // 4) 首次推理（可能触发 ModelScope 自动下载）
        log.info("开始推理，模型: {}, 提示词: {}", modelId, input.get("text"));
        long t0 = System.currentTimeMillis();
        Object output = translator.translate(input);
        long elapsed = System.currentTimeMillis() - t0;
        log.info("推理完成，耗时: {} ms", elapsed);

        // 5) 验证结果
        assertNotNull(output, "推理结果不应为 null");
        String resultStr = output.toString();
        log.info("推理结果: {}", resultStr);
        assertFalse(resultStr.isEmpty(), "推理结果不应为空");

        // 6) 再次推理（模型已缓存，无需下载）
        log.info("第二次推理（验证缓存）...");
        t0 = System.currentTimeMillis();
        Object output2 = translator.translate(input);
        long elapsed2 = System.currentTimeMillis() - t0;
        log.info("第二次推理耗时: {} ms", elapsed2);
        assertNotNull(output2);

        log.info("=== 测试通过 ===");
    }

    @Test
    @EnabledIfSystemProperty(named = "safetensor.test", matches = "true")
    void testEngineQwen25_3B() throws Exception {
        log.info("=== Qwen2.5-3B 测试开始（ModelScope）===");

        IdentificationEngine engine = new SafeTensorIdentificationEngine();

        // Qwen2.5-3B 尚未下载时会自动从 ModelScope 下载
        String modelId = "qwen2.5-3b";
        ITranslator<Object, Object> translator = engine.get(modelId, ITranslator.class);
        assertNotNull(translator);

        Map<String, Object> input = Map.of("text", "用一句话介绍 ChatGLM");
        log.info("推理: {}", input.get("text"));
        long t0 = System.currentTimeMillis();
        Object output = translator.translate(input);
        log.info("耗时: {} ms, 结果: {}", System.currentTimeMillis() - t0, output);
        assertNotNull(output);
    }

    @Test
    void testModelRegistry() {
        log.info("=== ModelRegistry 查询测试 ===");
        var entries = SafeTensorModelRegistry.allModels();
        log.info("总模型数: {}", entries.size());

        var llm = SafeTensorModelRegistry.byType("llm");
        log.info("LLM 模型: {}", llm.size());
        llm.forEach(e -> log.info("  {} ({}) — {}", e.id(), e.type(), e.description()));

        var minimind = SafeTensorModelRegistry.byId("minimind2-small");
        minimind.ifPresent(e -> log.info("找到 minimind2-small: {}", e));

        var types = SafeTensorModelRegistry.allTypes();
        log.info("所有类型: {}", types);

        log.info("=== ModelRegistry 测试通过 ===");
    }
}