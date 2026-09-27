package com.chua.mysql.support.vector;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageDescriptor;
import com.chua.common.support.vector.VectorStorageField;
import com.chua.common.support.vector.VectorStorageProvider;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * MySQL 向量存储 SPI 实现。
 *
 * <p>向量直接存入业务库，连接信息来自宿主注入的 {@code DataSource}，
 * 因此 {@link #descriptor()} 标记 {@code requiresDataSource=true}，
 * 表单只暴露表名与列名，不暴露任何连接信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "mysql", order = 50)
public class MysqlVectorStorageProvider implements VectorStorageProvider {

    /**
     * 配置键：向量表名。
     */
    public static final String KEY_TABLE_NAME = "tableName";

    /**
     * 配置键：标识列名。
     */
    public static final String KEY_ID_COLUMN = "idColumn";

    /**
     * 配置键：向量列名。
     */
    public static final String KEY_VECTOR_COLUMN = "vectorColumn";

    @Override
    public String name() {
        return "mysql";
    }

    /**
     * 声明 MySQL 实现需要的配置项。
     *
     * <p>不含任何连接字段：连接由宿主注入 {@code DataSource}。</p>
     *
     * @return 配置描述
     */
    @Override
    public VectorStorageDescriptor descriptor() {
        return new VectorStorageDescriptor("mysql", "MySQL 向量表",
                "向量直接存入业务库（需 MySQL 8.0.31+）；使用宿主数据源，表单不涉及账号密码",
                List.of(
                        VectorStorageField.text(KEY_TABLE_NAME, "向量表名", "存放向量的表"),
                        VectorStorageField.text(KEY_ID_COLUMN, "标识列名", "与向量一一对应的唯一标识列"),
                        VectorStorageField.text(KEY_VECTOR_COLUMN, "向量列名", "存放向量数值的列")),
                true);
    }

    /**
     * 由键值配置构造 {@link MysqlVectorStorageProperties}。
     *
     * <p>此处只产出存储属性，不含数据源；数据源由宿主经
     * {@link #create(int, VectorCompareAlgorithm, Object, DataSource)} 注入。</p>
     *
     * @param config 键值配置
     * @return MySQL 存储属性
     */
    @Override
    public Object toProperties(Map<String, Object> config) {
        MysqlVectorStorageProperties defaults = new MysqlVectorStorageProperties();
        if (config == null) {
            return defaults;
        }
        return new MysqlVectorStorageProperties(
                str(config.get(KEY_TABLE_NAME), defaults.tableName()),
                str(config.get(KEY_ID_COLUMN), defaults.idColumn()),
                str(config.get(KEY_VECTOR_COLUMN), defaults.vectorColumn()));
    }

    @Override
    public VectorStorage create(int dimension, VectorCompareAlgorithm algorithm, Object properties) {
        if (!(properties instanceof MysqlVectorStorageProps wrapped)) {
            throw new IllegalArgumentException(
                    "MySQL 向量存储需要 MysqlVectorStorageProps 类型的 properties，"
                            + "它包装宿主 DataSource 与 MysqlVectorStorageProperties");
        }
        return new MysqlVectorStorage(wrapped.dataSource(), dimension, algorithm, wrapped.properties());
    }

    /**
     * 由宿主注入数据源后创建。
     *
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param properties {@link #toProperties(Map)} 的产物
     * @param dataSource 宿主数据源
     * @return VectorStorage 实例
     */
    @Override
    public VectorStorage create(int dimension,
                                VectorCompareAlgorithm algorithm,
                                Object properties,
                                DataSource dataSource) {
        MysqlVectorStorageProperties props = properties instanceof MysqlVectorStorageProperties p
                ? p
                : new MysqlVectorStorageProperties();
        return new MysqlVectorStorage(dataSource, dimension, algorithm, props);
    }

    /**
     * 取字符串配置项，缺省时回落到默认值。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 去空白后的字符串；空值返回默认值
     */
    private static String str(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? defaultValue : s;
    }

    /**
     * 包装宿主数据源 与 mysql向量存储属性。
     *
     * <p>刻意只保留规范构造器：{@code DataSource} 是活资源句柄，无法证明深不可变，
     * 因此本 record 永远不具备成为 value class 的条件，不再增加便捷构造器。</p>
     *
     * @param dataSource  JDBC 数据源
     * @param properties 向量存储属性
     */
    public record MysqlVectorStorageProps(DataSource dataSource, MysqlVectorStorageProperties properties) {

        /**
         * 规范构造器：两个组件都是语义必填。
         *
         * <p>没有数据源或没有存储属性的实例在真正建索引时才会以 NPE 形式爆掉，
         * 报错位置离成因很远，因此在入口就拦下。</p>
         */
        public MysqlVectorStorageProps {
            java.util.Objects.requireNonNull(dataSource, "dataSource 不能为 null");
            java.util.Objects.requireNonNull(properties, "properties 不能为 null");
        }
    }
}
