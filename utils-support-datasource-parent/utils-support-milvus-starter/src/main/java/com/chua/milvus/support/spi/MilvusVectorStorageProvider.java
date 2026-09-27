package com.chua.milvus.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageDescriptor;
import com.chua.common.support.vector.VectorStorageField;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.milvus.support.configuration.MilvusStorageProperties;
import com.chua.milvus.support.storage.MilvusVectorStorage;

import java.util.List;
import java.util.Map;

/**
 * Milvus 向量存储 SPI 实现。
 *
 * <p>声明自身需要的服务地址、端口、集合名与认证令牌，使前端能动态渲染配置表单、
 * 后端能按保存的键值动态初始化，无需在本类之外硬编码任何分支。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "milvus", order = 100)
public class MilvusVectorStorageProvider implements VectorStorageProvider {

    /**
     * 配置键：服务地址。
     */
    public static final String KEY_HOST = "host";

    /**
     * 配置键：服务端口。
     */
    public static final String KEY_PORT = "port";

    /**
     * 配置键：集合名称。
     */
    public static final String KEY_COLLECTION = "collection";

    /**
     * 配置键：认证令牌。
     */
    public static final String KEY_TOKEN = "token";

    /**
     * SPI 名称。
     *
     * @return "milvus"
     */
    @Override
    public String name() {
        return "milvus";
    }

    /**
     * 声明 Milvus 需要的配置项。
     *
     * <p>{@code token} 标记为 {@link VectorStorageField#secret()}，前端不回显、后端不写日志。</p>
     *
     * @return 配置描述
     */
    @Override
    public VectorStorageDescriptor descriptor() {
        return new VectorStorageDescriptor("milvus", "Milvus 向量库",
                "独立的向量数据库服务，支持 Zilliz Cloud；需要服务地址、集合名与认证令牌",
                List.of(
                        VectorStorageField.text(KEY_HOST, "服务地址", "Milvus 服务主机名或 IP"),
                        VectorStorageField.number(KEY_PORT, "服务端口", "19530", false,
                                "仅当服务地址不含协议时生效；完整 URI 形如 https://xxx.zillizcloud.com"),
                        VectorStorageField.text(KEY_COLLECTION, "集合名称", "向量集合名"),
                        VectorStorageField.password(KEY_TOKEN, "认证令牌", false,
                                "Zilliz Cloud 必须填写；自建实例留空")),
                false);
    }

    /**
     * 由键值配置构造 {@link MilvusStorageProperties}。
     *
     * <p>缺项一律回落到 {@link MilvusStorageProperties} 自带默认值，
     * 因此前端只提交改动过的字段也能正常工作。</p>
     *
     * @param config 键值配置
     * @return Milvus 属性对象
     */
    @Override
    public Object toProperties(Map<String, Object> config) {
        MilvusStorageProperties props = new MilvusStorageProperties();
        if (config == null) {
            return props;
        }
        String host = str(config.get(KEY_HOST));
        if (host != null) {
            props.setHost(host);
        }
        Integer port = intv(config.get(KEY_PORT));
        if (port != null) {
            props.setPort(port);
        }
        String collection = str(config.get(KEY_COLLECTION));
        if (collection != null) {
            props.setCollection(collection);
        }
        String token = str(config.get(KEY_TOKEN));
        if (token != null) {
            props.setToken(token);
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
        if (!(properties instanceof MilvusStorageProperties props)) {
            throw new IllegalArgumentException(
                    "Milvus SPI 需要 MilvusStorageProperties，当前类型: "
                            + (properties == null ? "null" : properties.getClass().getName()));
        }
        return new MilvusVectorStorage(dimension, algorithm,
                props.getHost(), props.getPort(),
                props.getCollection(), props.getToken());
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
}
