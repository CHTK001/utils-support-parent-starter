package com.chua.common.support.vector;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 向量存储提供者 SPI。
 *
 * <p>通过 SPI 注册多个实现（内存、JVector、Milvus 等），可按名称获取。</p>
 *
 * <pre>{@code
 * // 链式风格（推荐）：
 * VectorStorage storage = VectorStorageProvider.of("jvector")
 *         .dimension(128)
 *         .algorithm("cosine")
 *         .properties(props)
 *         .build();
 *
 * // 传统风格：
 * VectorStorage storage = VectorStorageProvider.create("jvector", dim, algo, properties);
 * VectorStorage storage = VectorStorageProvider.create("memory", dim, algo);
 *
 * // 列出所有 SPI 实现：
 * List<String> providers = VectorStorageProvider.providers();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VectorStorageProvider {

    /**
     * SPI 名称（如 "memory", "jvector", "milvus"）。
     * @return 结果字符串
     */
    String name();

    /**
     * 创建向量存储实例。
     *
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param properties 实现特定的配置（可为 null）
     * @return VectorStorage 实例
     */
    VectorStorage create(int dimension, VectorCompareAlgorithm algorithm, Object properties);

    /**
     * 创建向量存储实例，由宿主注入数据源。
     *
     * <p>数据库形态的实现（MySQL / PostgreSQL 等）把向量直接存进业务库，
     * 其连接信息是宿主 Spring 容器里的 {@code DataSource}，既不能也不该让用户在表单里填。
     * 这类实现覆写本方法接收宿主注入的数据源；其余实现沿用默认委托，无需改动。</p>
     *
     * <p>本方法只对 {@link VectorStorageDescriptor#requiresDataSource()} 为 true 的实现有意义。</p>
     *
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param properties 实现特定的配置（可为 null）
     * @param dataSource 宿主注入的数据源，可为 null
     * @return VectorStorage 实例
     */
    default VectorStorage create(int dimension,
                                 VectorCompareAlgorithm algorithm,
                                 Object properties,
                                 DataSource dataSource) {
        return create(dimension, algorithm, properties);
    }

    /**
     * 返回本实现的配置描述，供前端动态渲染配置表单。
     *
     * <p>默认实现「无需配置」，因此既有实现无需改动即可兼容。需要用户填 host / 账号 / 索引路径等
     * 参数的实现必须覆写本方法，并在 {@link #toProperties(Map)} 中把同样的键值映射为
     * {@link #create(int, VectorCompareAlgorithm, Object)} 期望的属性对象。</p>
     *
     * <p>实现约定：{@code descriptor().fields()} 中声明的 {@code key} 集合，
     * 必须与 {@link #toProperties(Map)} 能识别的键集合一致，否则配置会在初始化时被忽略。</p>
     *
     * @return 配置描述，永不为 null
     */
    default VectorStorageDescriptor descriptor() {
        return VectorStorageDescriptor.simple(name(), name(), null);
    }

    /**
     * 由「键值配置」构造 {@link #create(int, VectorCompareAlgorithm, Object)} 所需的属性对象。
     *
     * <p>这是让初始化真正动态的关键：业务侧只需按 {@link #descriptor()} 存下用户填的键值，
     * 无需为每种向量库写分支；实现自己决定这些键值如何变成自己的属性对象。</p>
     *
     * <p>默认实现原样返回入参，适配「直接接受 Map」或「无需配置」的实现。
     * 需要强类型属性的实现应覆写本方法。</p>
     *
     * @param config 键值配置，不可为 null，缺项由实现套用默认值
     * @return 传给 create 的 properties 对象
     */
    default Object toProperties(Map<String, Object> config) {
        return config;
    }

    /**
     * 链式构建入口：通过 SPI 名称获取构建器。
     *
     * <pre>{@code
     * VectorStorage storage = VectorStorageProvider.of("jvector")
     *         .dimension(128)
     *         .algorithm("cosine")
     *         .properties(props)
     *         .build();
     * }</pre>
     *
     * @param providerName SPI 名称（如 "memory", "jvector", "milvus"）
     * @return 链式构建器
     */
    static Builder of(String providerName) {
        var provider = ServiceProvider.of(VectorStorageProvider.class).getExtension(providerName);
        if (provider == null) {
            throw new IllegalArgumentException("未找到向量存储 SPI: " + providerName);
        }
        return new Builder(provider);
    }

    /**
     * 创建指定 SPI 名称的向量存储。
     *
     * @param providerName SPI 名称
     * @param dimension    向量维度
     * @param algorithm    比较算法
     * @return VectorStorage 实例
     */
    static VectorStorage create(String providerName, int dimension, VectorCompareAlgorithm algorithm) {
        return create(providerName, dimension, algorithm, null);
    }

    /**
     * 创建指定 SPI 名称的向量存储。
     *
     * @param providerName SPI 名称
     * @param dimension    向量维度
     * @param algorithm    比较算法
     * @param properties   实现特定的配置（可为 null）
     * @return VectorStorage 实例
     */
    static VectorStorage create(String providerName,
                                int dimension,
                                VectorCompareAlgorithm algorithm,
                                Object properties) {
        try {
            return ServiceProvider.of(VectorStorageProvider.class)
                    .getExtension(providerName)
                    .create(dimension, algorithm, properties);
        } catch (Exception e) {
            throw new RuntimeException(
                    "无法创建向量存储 [" + providerName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 返回所有已注册的 SPI 名称。
     * @return 结果列表，无数据时为空列表
     */
    static List<String> providers() {
        return ServiceProvider.of(VectorStorageProvider.class)
                .getExtensions()
                .stream()
                .toList();
    }

    /**
     * 返回所有已注册实现的配置描述，供前端动态渲染。
     *
     * <p>业务侧据此渲染「向量数据库」下拉与随选项切换的配置表单，无需硬编码任何一种实现。</p>
     *
     * @return 描述列表，无数据时为空列表
     */
    static List<VectorStorageDescriptor> descriptors() {
        return ServiceProvider.of(VectorStorageProvider.class)
                .getExtensions()
                .stream()
                .map(VectorStorageProvider::find)
                .filter(Objects::nonNull)
                .map(VectorStorageProvider::descriptor)
                .toList();
    }

    /**
     * 按名称取 SPI 实例，用于「描述 → 保存 → 动态初始化」闭环。
     *
     * <p>与 {@link #of(String)} 的区别：本方法在未注册时返回 null（便于上层给出可读的业务错误），
     * 而 {@code of(String)} 会直接抛 {@link IllegalArgumentException}。</p>
     *
     * @param providerName SPI 名称
     * @return SPI 实例，未注册时返回 null
     */
    static VectorStorageProvider find(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return null;
        }
        return ServiceProvider.of(VectorStorageProvider.class)
                .getExtension(providerName.trim());
    }

    /**
     * 按名称动态创建向量存储：解析 SPI → 转换配置 → 创建，全程不含按名称的硬编码分支。
     *
     * @param providerName SPI 名称
     * @param dimension    向量维度
     * @param algorithm    比较算法
     * @param config       键值配置（来自业务侧持久化），不可为 null
     * @return VectorStorage 实例
     * @throws IllegalArgumentException SPI 名称未注册时
     */
    static VectorStorage createDynamic(String providerName,
                                       int dimension,
                                       VectorCompareAlgorithm algorithm,
                                       Map<String, Object> config) {
        return createDynamic(providerName, dimension, algorithm, config, null);
    }

    /**
     * 按名称动态创建向量存储，并注入宿主数据源。
     *
     * <p>数据库形态的实现需要宿主注入 {@code DataSource}，走本重载即可。
     * 全程不含按名称的硬编码分支：解析 SPI、转换配置、注入数据源都由 SPI 自身决定怎么做。</p>
     *
     * @param providerName SPI 名称
     * @param dimension    向量维度
     * @param algorithm    比较算法
     * @param config       键值配置（来自业务侧持久化），不可为 null
     * @param dataSource   宿主数据源，不需要时传 null
     * @return VectorStorage 实例
     * @throws IllegalArgumentException SPI 名称未注册，或需要数据源但未注入时
     */
    static VectorStorage createDynamic(String providerName,
                                       int dimension,
                                       VectorCompareAlgorithm algorithm,
                                       Map<String, Object> config,
                                       DataSource dataSource) {
        VectorStorageProvider provider = find(providerName);
        if (provider == null) {
            throw new IllegalArgumentException("未注册的向量存储 SPI: " + providerName
                    + "，已注册: " + providers());
        }
        if (provider.descriptor().requiresDataSource() && dataSource == null) {
            throw new IllegalArgumentException("向量存储 [" + providerName + "] 需要宿主注入数据源，但未提供");
        }
        return provider.create(dimension, algorithm, provider.toProperties(config), dataSource);
    }

    /**
     * 链式构建器，通过 {@link #of(String)} 获取。
     *
     * <pre>{@code
     * VectorStorage storage = VectorStorageProvider.of("jvector")
     *         .dimension(128)
     *         .algorithm("cosine")
     *         .properties(props)
     *         .build();
     * }</pre>
     */
    final class Builder {
        /**
         * 提供者
         */
        private final VectorStorageProvider provider;
        /**
         * Dimension
         */
        private int dimension = 128;
        /**
         * 算法
         */
        private VectorCompareAlgorithm algorithm = VectorCompareAlgorithm.euclidean();
        /**
         * 属性
         */
        private Object properties;

        Builder(VectorStorageProvider provider) {
            this.provider = provider;
        }

        /**
         * 设置向量维度。
         *
         * @param dimension 向量维度
         * @return this
         */
        public Builder dimension(int dimension) {
            this.dimension = dimension;
            return this;
        }

        /**
         * 设置自定义比较算法。
         *
         * @param algorithm 比较算法实例
         * @return this
         */
        public Builder algorithm(VectorCompareAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        /**
         * 通过名称设置内置比较算法。
         *
         * @param name 算法名称（EUCLIDEAN / COSINE / DOT）
         * @return this
         */
        public Builder algorithm(String name) {
            this.algorithm = switch (name.toUpperCase()) {
                case "EUCLIDEAN" -> VectorCompareAlgorithm.euclidean();
                case "COSINE" -> VectorCompareAlgorithm.cosine();
                case "DOT" -> VectorCompareAlgorithm.dotProduct();
                default -> throw new IllegalArgumentException("不支持的算法: " + name);
            };
            return this;
        }

        /**
         * 设置实现特定的配置属性。
         *
         * @param properties 配置对象（可为 null）
         * @return this
         */
        public Builder properties(Object properties) {
            this.properties = properties;
            return this;
        }

        /**
         * 构建向量存储实例。
         *
         * @return VectorStorage 实例
         */
        public VectorStorage build() {
            return provider.create(dimension, algorithm, properties);
        }
    }

    /**
     * 默认的内存实现（最低优先级，作为兜底）。
     */
    @Spi(value = "memory", order = -100)
    class MemoryProvider implements VectorStorageProvider {
        /**
         * Name
         */
        @Override
        public String name() {
            return "memory";
        }

        /**
         * 内存实现无需任何配置。
         *
         * @return 配置描述
         */
        @Override
        public VectorStorageDescriptor descriptor() {
            return VectorStorageDescriptor.simple("memory", "内存向量库",
                    "进程内存储，重启即丢失；无需任何配置，仅用于开发与临时验证");
        }

        /**
         * 创建
         * @param dimension dimension
         * @param algorithm algorithm
         * @param properties properties
         */
        @Override
        public VectorStorage create(int dimension,
                                    VectorCompareAlgorithm algorithm,
                                    Object properties) {
            return new MemoryVectorStorage(dimension, algorithm);
        }
    }
}
