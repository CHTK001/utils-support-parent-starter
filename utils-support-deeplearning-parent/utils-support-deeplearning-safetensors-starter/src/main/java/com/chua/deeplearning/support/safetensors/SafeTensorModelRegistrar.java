package com.chua.deeplearning.support.safetensors;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.layout.LayoutDetector;

/**
 * SafeTensor Python 服务侧模型集中注册器。
 *
 * <p>用于把 Java 侧新增的 Python 服务模型注册到 {@link ModelRegistry}，
 * 使模型出现在能力清单（如 {@link LayoutDetector#listModels()}）并可被门面按模型名加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SafeTensorModelRegistrar implements ModelRegistrar {

    static {
        registerAll();
    }

    @Override
    public void register(ModelRegistry registry) {
        registerAll();
    }

    /**
     * 注册全部
     */
    private static void registerAll() {
        if (ModelRegistry.get("indra-layout") != null) {
            return;
        }
        ModelRegistry.register(
                "indra-layout",
                IndraLayoutTranslator.class,
                byte[].class,
                String.class,
                LayoutDetector.class,
                "python/indra"
        );
    }
}