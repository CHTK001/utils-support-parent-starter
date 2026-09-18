package com.chua.deeplearning.support.engine;

/**
* 模型注册器 SPI 接口，用于子模块向 {@link ModelRegistry} 批量注册模型。
*
* @author CH
* @since 4.0.0.42
 */
@FunctionalInterface
public interface ModelRegistrar {

    /**
     * 注册。
     *
     * @param registry 方法入参 registry
     */
    void register(ModelRegistry registry);
}
