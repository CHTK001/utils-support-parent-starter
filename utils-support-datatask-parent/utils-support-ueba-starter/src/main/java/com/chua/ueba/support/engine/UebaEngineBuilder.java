package com.chua.ueba.support.engine;

import com.chua.deeplearning.support.onnx.ueba.AutoEncoderIpTranslator;
import com.chua.ueba.support.config.UebaConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 分析引擎链式构建器。
 * <p>
 * 通过统一门面 {@code Ueba.engine()} 获取，支持链式配置配置来源、模型目录与
   * minimind 开关，最后 {@link #build()} 产出 {@link UebaEngine}。示例：</p>
 * <pre>
 * UebaEngine engine = Ueba.engine()
 *         .configResource("ueba-config.yaml")
 *         .modelDir("D:/models/ueba")
 *         .enableLlm()
 *         .build();
 * </pre>
 * <p>未显式设置配置时，默认加载 classpath 上的 {@code ueba-config.yaml}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class UebaEngineBuilder {

    /** 类路径 默认配置文件 */
    private static final String DEFAULT_CONFIG_RESOURCE = "ueba-config.yaml";

    /** 已解析的配置 */
    private UebaConfig config;

    /** 是否启用 minimind，空 表示默认启用 */
    private Boolean enableLlm;

    /** 模型目录（写入 ueba.模型.dir 系统属性） */
    private String modelDir;

    /**
     * 以内存配置对象设置配置。
     *
     * @param config UEBA 配置，不能为 空
     * @return 当前构建器
     * @throws IllegalArgumentException 当 配置 为 空 时
     */
    public UebaEngineBuilder config(UebaConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.config = config;
        return this;
    }

    /**
     * 以文件路径设置配置。
     *
     * @param path 配置文件路径，不能为 空
     * @return 当前构建器
     * @throws IllegalArgumentException 当 路径 为 空 时
     * @throws UncheckedIOException     当配置文件读取失败时
     */
    public UebaEngineBuilder config(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        this.config = UebaConfig.load(path);
        return this;
    }

    /**
     * 以文件路径字符串设置配置。
     *
     * @param file 配置文件路径，不能为 空 或空白
     * @return 当前构建器
     * @throws IllegalArgumentException 当 文件 为 空 或空白时
     */
    public UebaEngineBuilder configFile(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file 不能为 null 或空白");
        }
        this.config = UebaConfig.load(Paths.get(file));
        return this;
    }

    /**
      * 以 类路径 资源设置配置。
     *
     * @param resource 类路径 资源路径，不能为 空 或空白
     * @return 当前构建器
     * @throws IllegalArgumentException 当 resource 为 空/空白或资源不存在时
     */
    public UebaEngineBuilder configResource(String resource) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource 不能为 null 或空白");
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath 资源不存在: " + resource);
            }
            this.config = UebaConfig.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("加载配置资源失败: " + resource, e);
        }
        return this;
    }

    /**
      * 设置模型目录，写入系统属性 {@code ueba.model.dir}，auto编码器 与 LSTM 模型
     * 均从该目录加载。
     *
     * @param modelDir 模型目录，允许为 空（保持默认加载策略）
     * @return 当前构建器
     */
    public UebaEngineBuilder modelDir(String modelDir) {
        this.modelDir = modelDir;
        return this;
    }

    /**
      * 启用 minimind 语义解释（默认行为）。
     *
     * @return 当前构建器
     */
    public UebaEngineBuilder enableLlm() {
        this.enableLlm = Boolean.TRUE;
        return this;
    }

    /**
      * 禁用 minimind 语义解释，使用模板解释。
     *
     * @return 当前构建器
     */
    public UebaEngineBuilder disableLlm() {
        this.enableLlm = Boolean.FALSE;
        return this;
    }

    /**
     * 构建分析引擎。
     * <p>未显式设置配置时加载 classpath 的默认 {@code ueba-config.yaml}；
     * 调用方负责在使用完毕后关闭返回的引擎。</p>
     *
     * @return 就绪的分析引擎，绝不为 空
     * @throws IllegalStateException 当默认配置加载失败时
     */
    public UebaEngine build() {
        if (config == null) {
            configResource(DEFAULT_CONFIG_RESOURCE);
        }
        if (modelDir != null && !modelDir.isBlank()) {
            System.setProperty(AutoEncoderIpTranslator.MODEL_DIR_PROPERTY, modelDir);
            log.debug("[UEBA-Builder] ueba.model.dir={}", modelDir);
        }
        boolean llm = enableLlm == null || enableLlm;
        return new UebaEngine(config, llm);
    }
}