package com.chua.deeplearning.support.spring.config;

import com.chua.deeplearning.support.core.api.Model3DConfig;
import com.chua.deeplearning.support.core.api.Model3DGenerator;
import com.chua.deeplearning.support.core.api.TextTo3DGenerator;
import com.chua.deeplearning.support.core.api.ImageTo3DGenerator;
import com.chua.deeplearning.support.core.api.SketchTo3DGenerator;
import com.chua.deeplearning.support.core.api.Model3DStylizer;
import com.chua.deeplearning.support.core.provider.ForgeStyleApiModel3DGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 3D 生成自动配置
 *
 * @author CH
 * @since 4.0.0.42
 */
@Configuration
@ConditionalOnClass({Model3DGenerator.class, ForgeStyleApiModel3DGenerator.class})
@ConditionalOnProperty(prefix = "chua.deeplearning.core", name = "enable", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(Model3DConfig.class)
public class GenerationApiModel3DGenerator {

    /**
     * 文生 3D 生成器
     *
     * @param config 3D 生成配置
     * @return 文生 3D 生成器
     */
    @Bean
    @ConditionalOnProperty(prefix = "chua.deeplearning.core", name = "text-to-3d-enable", havingValue = "true", matchIfMissing = true)
    public TextTo3DGenerator textTo3DGenerator(Model3DConfig config) {
        return new ForgeStyleApiModel3DGenerator(config);
    }

    /**
     * 图生 3D 生成器
     *
     * @param config 3D 生成配置
     * @return 图生 3D 生成器
     */
    @Bean
    @ConditionalOnProperty(prefix = "chua.deeplearning.core", name = "image-to-3d-enable", havingValue = "true", matchIfMissing = true)
    public ImageTo3DGenerator imageTo3DGenerator(Model3DConfig config) {
        return new ForgeStyleApiModel3DGenerator(config);
    }

    /**
     * 草图生 3D 生成器
     *
     * @param config 3D 生成配置
     * @return 草图生 3D 生成器
     */
    @Bean
    @ConditionalOnProperty(prefix = "chua.deeplearning.core", name = "sketch-to-3d-enable", havingValue = "true", matchIfMissing = true)
    public SketchTo3DGenerator sketchTo3DGenerator(Model3DConfig config) {
        return new ForgeStyleApiModel3DGenerator(config);
    }

    /**
     * 通用 3D 生成器
     *
     * @param config 3D 生成配置
     * @return 通用 3D 生成器
     */
    @Bean
    @ConditionalOnProperty(prefix = "chua.deeplearning.core", name = "generator-enable", havingValue = "true", matchIfMissing = true)
    public Model3DGenerator model3DGenerator(Model3DConfig config) {
        return new ForgeStyleApiModel3DGenerator(config);
    }

    /**
     * 3D 模型风格化器
     *
     * @param config 3D 生成配置
     * @return 3D 模型风格化器
     */
    @Bean
    @ConditionalOnProperty(prefix = "chua.deeplearning.core", name = "stylizer-enable", havingValue = "true", matchIfMissing = true)
    public Model3DStylizer model3DStylizer(Model3DConfig config) {
        return new ForgeStyleApiModel3DGenerator(config);
    }
}
