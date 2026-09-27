package com.chua.jvector.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageDescriptor;
import com.chua.common.support.vector.VectorStorageField;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import com.chua.jvector.support.storage.JVectorVectorStorage;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * j向量 向量存储 SPI 实现。
 *
 * <p>声明存储模式、索引路径与图参数，使前端能动态渲染配置表单；
 * 其中 {@code indexPath} 标记为 {@link VectorStorageField#TYPE_PATH}，
 * 由后端文件系统接口选取，不允许前端自由拼路径。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "jvector", order = 100)
public class JVectorVectorStorageProvider implements VectorStorageProvider {

    /**
     * 配置键：存储模式。
     */
    public static final String KEY_MODE = "mode";

    /**
     * 配置键：磁盘索引文件路径。
     */
    public static final String KEY_INDEX_PATH = "indexPath";

    /**
     * 配置键：图最大度数。
     */
    public static final String KEY_GRAPH_M = "graphM";

    /**
     * 配置键：构建搜索深度。
     */
    public static final String KEY_GRAPH_EF_CONSTRUCTION = "graphEfConstruction";

    /**
     * 配置键：搜索候选集倍数。
     */
    public static final String KEY_SEARCH_OVERQUERY = "searchOverquery";

    /**
     * 配置键：是否启动即预构建。
     */
    public static final String KEY_PREPARE_ON_STARTUP = "prepareOnStartup";

    /**
     * SPI 名称。
     *
     * @return "jvector"
     */
    @Override
    public String name() {
        return "jvector";
    }

    /**
     * 声明 jvector 需要的配置项。
     *
     * @return 配置描述
     */
    @Override
    public VectorStorageDescriptor descriptor() {
        List<String> modes = Arrays.stream(JVectorStorageProperties.Mode.values())
                .map(Enum::name)
                .toList();
        return new VectorStorageDescriptor("jvector", "JVector 本地向量库",
                "进程内 CPU 向量索引，无需外部服务；ON_DISK 模式索引落盘，重启不丢数据",
                List.of(
                        VectorStorageField.select(KEY_MODE, "存储模式",
                                JVectorStorageProperties.Mode.ON_DISK.name(), modes, true,
                                "ON_DISK 磁盘持久化（默认，重启不丢）；MEMORY 纯内存（重启即丢）；"
                                        + "LARGER_THAN_MEMORY 超内存 PQ 压缩"),
                        VectorStorageField.path(KEY_INDEX_PATH, "索引路径", "./jvector-index", false,
                                "仅 ON_DISK 模式使用；由服务器目录选择器指定"),
                        VectorStorageField.number(KEY_GRAPH_M, "图最大度数 M", "32", false, "越大召回越高、内存开销越大"),
                        VectorStorageField.number(KEY_GRAPH_EF_CONSTRUCTION, "构建搜索深度", "100", false,
                                "建图阶段的候选集深度，越大建图越慢、索引越准"),
                        VectorStorageField.number(KEY_SEARCH_OVERQUERY, "搜索候选倍数", "2", false,
                                "检索阶段候选集相对 topK 的倍数"),
                        VectorStorageField.bool(KEY_PREPARE_ON_STARTUP, "启动即预构建", "true", null)),
                false);
    }

    /**
     * 由键值配置构造 {@link JVectorStorageProperties}。
     *
     * <p>缺项一律回落到属性类自带默认值。</p>
     *
     * @param config 键值配置
     * @return jvector 属性对象
     */
    @Override
    public Object toProperties(Map<String, Object> config) {
        JVectorStorageProperties props = new JVectorStorageProperties();
        if (config == null) {
            return props;
        }
        String mode = str(config.get(KEY_MODE));
        if (mode != null) {
            try {
                props.setMode(JVectorStorageProperties.Mode.valueOf(mode.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("jvector 不支持的存储模式: " + mode
                        + "，可选: " + Arrays.toString(JVectorStorageProperties.Mode.values()));
            }
        }
        String indexPath = str(config.get(KEY_INDEX_PATH));
        if (indexPath != null) {
            props.setIndexPath(indexPath);
        }
        Integer graphM = intv(config.get(KEY_GRAPH_M));
        if (graphM != null) {
            props.setGraphM(graphM);
        }
        Integer efConstruction = intv(config.get(KEY_GRAPH_EF_CONSTRUCTION));
        if (efConstruction != null) {
            props.setGraphEfConstruction(efConstruction);
        }
        Float overquery = floatv(config.get(KEY_SEARCH_OVERQUERY));
        if (overquery != null) {
            props.setSearchOverquery(overquery);
        }
        Boolean prepare = boolv(config.get(KEY_PREPARE_ON_STARTUP));
        if (prepare != null) {
            props.setPrepareOnStartup(prepare);
        }
        return props;
    }

    @Override
    /**
     * 创建
     * @param dimension 维度
     * @param algorithm algorithm
     * @param properties 属性
     */
    public VectorStorage create(int dimension,
                                VectorCompareAlgorithm algorithm,
                                Object properties) {
        JVectorStorageProperties props = null;
        if (properties instanceof JVectorStorageProperties p) {
            props = p;
        }
        return new JVectorVectorStorage(dimension, algorithm, props);
    }

    /**
     * 取字符串配置项。
     *
     * @param value 原始值
     * @return 去空白后的字符串；空值返回 null
     */
    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 取整数配置项。
     *
     * @param value 原始值
     * @return 整数；无法解析时返回 null
     */
    private static Integer intv(Object value) {
        String s = str(value);
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 取浮点配置项。
     *
     * @param value 原始值
     * @return 浮点数；无法解析时返回 null
     */
    private static Float floatv(Object value) {
        String s = str(value);
        if (s == null) {
            return null;
        }
        try {
            return Float.valueOf(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 取布尔配置项。
     *
     * @param value 原始值
     * @return 布尔值；无法解析时返回 null
     */
    private static Boolean boolv(Object value) {
        String s = str(value);
        if (s == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s)
                || "yes".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "off".equalsIgnoreCase(s)
                || "no".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
