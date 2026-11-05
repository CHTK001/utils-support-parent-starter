// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.pytorch.layout;

import com.chua.deeplearning.support.image.ImageDetector;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WevisdocTranslator SPI 注册集成测试。
 * <p>
 * 验证 pytorch starter 的 PytorchModelRegistrar 已将 wevisdoc-layout
 * 注册到 ImageDetector 能力清单，可通过模型名加载。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
class WevisdocLayoutSpiTest {

    /**
     * 测试 wevisdoc-layout 已注册到 ImageDetector 能力清单。
     */
    @Test
    void testWevisdocLayoutRegistered() {
        com.chua.deeplearning.support.engine.ModelRegistry.discoverAll();
        List<String> models = ImageDetector.listModels();
        log.info("ImageDetector 可用模型: {}", models);
        assertTrue(models.contains("wevisdoc-layout"),
                "wevisdoc-layout 应出现在 ImageDetector.listModels() 中");
    }

    /**
     * 测试 WevisdocTranslator 构造器可用。
     */
    @Test
    void testTranslatorConstructible() {
        WevisdocTranslator translator = new WevisdocTranslator();
        log.info("WevisdocTranslator 构造成功");
        assertTrue(WevisdocTranslator.getLayoutLabels().size() == 11);
    }

    /**
     * 测试 wevisdoc-layout-remote 已注册到 ImageDetector 能力清单。
     */
    @Test
    void testWevisdocLayoutRemoteRegistered() {
        com.chua.deeplearning.support.engine.ModelRegistry.discoverAll();
        List<String> models = ImageDetector.listModels();
        log.info("ImageDetector 可用模型: {}", models);
        assertTrue(models.contains("wevisdoc-layout-remote"),
                "wevisdoc-layout-remote 应出现在 ImageDetector.listModels() 中");
    }

    /**
     * 测试 WevisdocRemoteTranslator 构造器可用。
     */
    @Test
    void testRemoteTranslatorConstructible() {
        WevisdocRemoteTranslator translator = new WevisdocRemoteTranslator();
        log.info("WevisdocRemoteTranslator 构造成功");
        assertNotNull(translator);
    }
}