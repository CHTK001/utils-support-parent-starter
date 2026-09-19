package com.chua.trae.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chua.trae.support.model.ModelConfig;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 模型配置加载器，从 类路径 资源或文件路径读取 {@link ModelConfig}。
 * 纯工具类，无状态，线程安全。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ModelConfigLoader {

    /** JSON 解析器，线程安全，可复用 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认 类路径 资源名 */
    private static final String DEFAULT_RESOURCE = "/model-config.json";

    /**
     * 私有构造，防止实例化。
     */
    private ModelConfigLoader() {
        throw new UnsupportedOperationException("ModelConfigLoader is a static utility, do not instantiate");
    }

    /**
     * 从 类路径 资源加载配置。
     *
     * @param resourceName 资源路径，如 /模型-配置.json，不可为 空
     * @return 解析后的配置，资源不存在时返回空 Optional
     */
    public static Optional<ModelConfig> loadFromResource(String resourceName) {
        Objects.requireNonNull(resourceName, "resourceName must not be null");
        try (InputStream is = ModelConfigLoader.class.getResourceAsStream(resourceName)) {
            if (is == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(MAPPER.readValue(is, ModelConfig.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load model config from " + resourceName + ": " + e.getMessage(), e);
        }
    }

    /**
     * 从文件路径加载配置。
     *
     * @param file 配置文件路径，不可为 空，文件必须存在
     * @return 解析后的配置
     * @throws java.io.IOException 当文件读取失败时
     */
    public static ModelConfig loadFromFile(Path file) throws java.io.IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.exists(file)) {
            throw new java.io.FileNotFoundException("Config file not found: " + file);
        }
        try (InputStream is = Files.newInputStream(file)) {
            return MAPPER.readValue(is, ModelConfig.class);
        }
    }

    /**
     * 加载默认配置（/模型-配置.json）。
     *
     * @return 默认配置，资源不存在时返回空 Optional
     */
    public static Optional<ModelConfig> loadDefault() {
        return loadFromResource(DEFAULT_RESOURCE);
    }
}
