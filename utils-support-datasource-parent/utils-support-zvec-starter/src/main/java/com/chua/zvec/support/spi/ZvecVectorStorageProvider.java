package com.chua.zvec.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageDescriptor;
import com.chua.common.support.vector.VectorStorageField;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.zvec.support.configuration.ZvecStorageProperties;
import com.chua.zvec.support.storage.ZvecVectorStorage;

import java.util.List;
import java.util.Map;

/**
 * Zvec 向量存储 SPI 实现。
 *
 * <p>声明集合目录、HNSW 参数与落盘策略，使前端能动态渲染配置表单，
 * 后端按保存的键值动态初始化。集合目录标记为 {@link VectorStorageField#TYPE_PATH}，
 * 由服务器目录选择器指定，不允许前端自由拼路径。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi(value = "zvec", order = 100)
public class ZvecVectorStorageProvider implements VectorStorageProvider {

    /**
     * 配置键：集合目录。
     */
    public static final String KEY_PATH = "path";

    /**
     * 配置键：HNSW 图最大度数。
     */
    public static final String KEY_HNSW_M = "hnswM";

    /**
     * 配置键：HNSW 构建搜索深度。
     */
    public static final String KEY_EF_CONSTRUCTION = "efConstruction";

    /**
     * 配置键：HNSW 查询搜索深度。
     */
    public static final String KEY_QUERY_EF = "queryEf";

    /**
     * 配置键：是否启用 mmap。
     */
    public static final String KEY_ENABLE_MMAP = "enableMmap";

    /**
     * 配置键：是否每次写入即落盘。
     */
    public static final String KEY_FLUSH_ON_WRITE = "flushOnWrite";

    /**
     * SPI 名称。
     *
     * @return "zvec"
     */
    @Override
    public String name() {
        return "zvec";
    }

    /**
     * 声明 zvec 需要的配置项。
     *
     * @return 配置描述
     */
    @Override
    public VectorStorageDescriptor descriptor() {
        return new VectorStorageDescriptor("zvec", "Zvec 本地向量库",
                "阿里巴巴开源的嵌入式向量数据库（C++ 内核 + JNI 绑定），一个目录一个集合，"
                        + "无需外部服务；索引与数据落盘，重启不丢",
                List.of(
                        VectorStorageField.path(KEY_PATH, "集合目录", "./zvec-index", true,
                                "一个目录对应一个集合；目录为空时会按当前维度新建，"
                                        + "已有集合要求维度一致，否则初始化报错"),
                        VectorStorageField.number(KEY_HNSW_M, "图最大度数 M", "16", false,
                                "HNSW 每个节点的最大连接数，越大召回越高、构建越慢"),
                        VectorStorageField.number(KEY_EF_CONSTRUCTION, "构建搜索深度", "200", false,
                                "HNSW 建图阶段的候选集深度"),
                        VectorStorageField.number(KEY_QUERY_EF, "查询搜索深度", "64", false,
                                "检索候选集深度，实际取值不小于 topK"),
                        VectorStorageField.bool(KEY_ENABLE_MMAP, "启用 mmap", "false",
                                "开启后集合读取走内存映射，适合大于内存的数据集"),
                        VectorStorageField.bool(KEY_FLUSH_ON_WRITE, "每次写入即落盘", "false",
                                "开启后每次 add/update/remove 立即 flush（约 20ms/次），"
                                        + "批量灌库建议关闭，由 close 统一落盘")),
                false);
    }

    /**
     * 由键值配置构造 {@link ZvecStorageProperties}，缺项回落到属性类默认值。
     *
     * @param config 键值配置
     * @return zvec 属性对象
     */
    @Override
    public Object toProperties(Map<String, Object> config) {
        ZvecStorageProperties props = new ZvecStorageProperties();
        if (config == null) {
            return props;
        }
        String path = str(config.get(KEY_PATH));
        if (path != null) {
            props.setPath(path);
        }
        Integer hnswM = intv(config.get(KEY_HNSW_M));
        if (hnswM != null) {
            props.setHnswM(hnswM);
        }
        Integer efConstruction = intv(config.get(KEY_EF_CONSTRUCTION));
        if (efConstruction != null) {
            props.setEfConstruction(efConstruction);
        }
        Integer queryEf = intv(config.get(KEY_QUERY_EF));
        if (queryEf != null) {
            props.setQueryEf(queryEf);
        }
        Boolean enableMmap = boolv(config.get(KEY_ENABLE_MMAP));
        if (enableMmap != null) {
            props.setEnableMmap(enableMmap);
        }
        Boolean flushOnWrite = boolv(config.get(KEY_FLUSH_ON_WRITE));
        if (flushOnWrite != null) {
            props.setFlushOnWrite(flushOnWrite);
        }
        return props;
    }

    /**
     * 创建向量存储。
     *
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param properties 属性对象 {@link ZvecStorageProperties}，可为 null
     * @return Zvec 向量存储
     */
    @Override
    public VectorStorage create(int dimension,
                                VectorCompareAlgorithm algorithm,
                                Object properties) {
        ZvecStorageProperties props = properties instanceof ZvecStorageProperties p ? p : null;
        return new ZvecVectorStorage(dimension, algorithm, props);
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
