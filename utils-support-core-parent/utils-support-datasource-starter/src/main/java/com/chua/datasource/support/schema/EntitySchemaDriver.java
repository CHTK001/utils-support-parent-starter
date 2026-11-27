package com.chua.datasource.support.schema;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.ddl.ColumnDef;
import com.chua.common.support.lang.datasource.engine.ddl.DdlProvider;
import com.chua.common.support.lang.datasource.schema.SchemaDriver;
import com.chua.common.support.lang.datasource.schema.SchemaDriverContext;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import com.chua.common.support.utils.CollectionUtils;

/**
 * 实体驱动的结构初始化实现，作为 {@link SchemaDriver} SPI 的 {@code entity} 扩展。
 *
 * <p>按实体类上的表名注解与字段定义生成 {@code CREATE TABLE IF NOT EXISTS} 并执行，
 * 用于替代（或补充）SQL 脚本驱动的建表方式。幂等：表已存在时跳过。</p>
 *
 * <p><b>复用现成能力，不重复造轮子</b>：</p>
 * <ul>
 *   <li>表名解析：复用 {@link AbstractEngine#resolveTableName(Class)}
 *       （utils 自有 {@code @TableName} 注解 + 驼峰转下划线兜底），
 *       并在其之前优先读取 MyBatis-Plus 的 {@code @TableName}（可选依赖，反射读取）；</li>
 *   <li>列类型映射：Java 类型 → JDBC 类型 → {@link Dialect#getTypeName(int, long, int, int)}
 *       方言渲染（如 MySQL 的 {@code VARCHAR(255)} / {@code DATETIME}）；
 *       方言不可用时退回内置通用类型表；</li>
 *   <li>DDL 生成：优先复用 {@link DdlProvider} SPI 的方言实现
 *       （如 {@code MysqlDdlProvider}，自带 IF NOT EXISTS / 反引号 / utf8mb4）；
 *       无匹配方言时使用内置标准 SQL 兜底。</li>
 * </ul>
 *
 * <p>注册文件：
 * {@code META-INF/extensions/com.chua.common.support.lang.datasource.schema.SchemaDriver}，
 * 内容为 {@code entity=com.chua.datasource.support.schema.EntitySchemaDriver}。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@Spi(SchemaDriver.SPI_NAME)
public class EntitySchemaDriver implements SchemaDriver {

    /**
     * MyBatis-Plus 表名注解的类名（可选依赖，运行期按存在性加载）。
     */
    private static final String MP_TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName";

    /**
     * MyBatis-Plus 主键注解的类名。
     */
    private static final String MP_TABLE_ID = "com.baomidou.mybatisplus.annotation.TableId";

    /**
     * MyBatis-Plus 字段注解的类名。
     */
    private static final String MP_TABLE_FIELD = "com.baomidou.mybatisplus.annotation.TableField";

    /**
     * 已加载的注解类缓存；加载失败以 {@code Void.class} 占位，避免重复尝试。
     */
    private static volatile Class<? extends Annotation> mpTableNameClass;
    /**
     * 已加载的注解类缓存；加载失败以 {@code Void.class} 占位，避免重复尝试。
     */
    private static volatile Class<? extends Annotation> mpTableIdClass;
    /**
     * 已加载的注解类缓存；加载失败以 {@code Void.class} 占位，避免重复尝试。
     */
    private static volatile Class<? extends Annotation> mpTableFieldClass;

    /**
     * 驱动模式标识。
     *
     * @return 实体驱动模式名
     */
    @Override
    public String name() {
        return SchemaDriver.ENTITY;
    }

    /**
     * 执行实体驱动的结构初始化：逐个实体建表，表已存在则跳过。
     *
     * @param context 驱动上下文
     * @return 本次实际建表数量
     */
    @Override
    public int migrate(SchemaDriverContext context) {
        DataSource dataSource = context.getDataSource();
        if (dataSource == null) {
            throw new IllegalArgumentException("SchemaDriverContext.dataSource 不能为空");
        }
        List<Class<?>> entityClasses = context.getEntityClasses();
        if (CollectionUtils.isEmpty(entityClasses)) {
            if (context.isVerbose()) {
                log.info("[EntitySchemaDriver] 未配置实体类，跳过结构初始化");
            }
            return 0;
        }
        String protocol = context.getProtocol();
        Dialect dialect = resolveDialect(protocol);
        DdlProvider ddlProvider = resolveDdlProvider(protocol, dataSource);
        int created = 0;
        for (Class<?> entityClass : entityClasses) {
            String tableName = resolveTableName(entityClass);
            if (tableName == null || tableName.isBlank()) {
                log.warn("[EntitySchemaDriver] 实体 {} 无法解析表名，已跳过", entityClass.getName());
                continue;
            }
            List<ColumnDef> columns = resolveColumns(entityClass, dialect);
            if (columns.isEmpty()) {
                log.warn("[EntitySchemaDriver] 实体 {} 未解析到任何持久化字段，已跳过建表 {}",
                        entityClass.getName(), tableName);
                continue;
            }
            if (tableExists(dataSource, tableName)) {
                if (context.isVerbose()) {
                    log.info("[EntitySchemaDriver] 表 {} 已存在，跳过建表", tableName);
                }
                continue;
            }
            executeDdl(dataSource, tableName, renderCreateTable(tableName, columns, ddlProvider));
            created++;
        }
        if (context.isVerbose()) {
            log.info("[EntitySchemaDriver] 实体驱动结构初始化完成，共处理 {} 个实体，新建 {} 张表",
                    entityClasses.size(), created);
        }
        return created;
    }

    /**
     * 解析实体类对应的表名。
     *
     * <p>优先级：MyBatis-Plus {@code @TableName}（反射，可选）→
     * {@link AbstractEngine#resolveTableName(Class)}（utils 自有 {@code @TableName}
     * + 驼峰转下划线兜底）。</p>
     *
     * @param entityClass 实体类
     * @return 表名；两者均未命中且无法推导时可能为 null
     */
    private String resolveTableName(Class<?> entityClass) {
        String mpName = readMpTableName(entityClass);
        if (mpName != null && !mpName.isBlank()) {
            return mpName;
        }
        return AbstractEngine.resolveTableName(entityClass);
    }

    /**
     * 解析实体类的全部持久化列定义。
     *
     * <p>沿父类链收集声明字段，跳过静态、transient、合成字段以及
     * MyBatis-Plus {@code @TableField(exist = false)} 的非持久化字段。</p>
     *
     * @param entityClass 实体类
     * @param dialect     数据库方言（可为 null，此时用内置类型映射）
     * @return 列定义列表
     */
    private List<ColumnDef> resolveColumns(Class<?> entityClass, Dialect dialect) {
        List<ColumnDef> columns = new ArrayList<>();
        for (Class<?> current = entityClass; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())
                        || field.isSynthetic() || isMpIgnoredField(field)) {
                    continue;
                }
                ColumnDef column = toColumnDef(field, dialect);
                if (column != null) {
                    columns.add(column);
                }
            }
        }
        return columns;
    }

    /**
     * 把实体字段转换为列定义。
     *
     * @param field   实体字段
     * @param dialect 数据库方言（可为 null）
     * @return 列定义
     */
    private ColumnDef toColumnDef(Field field, Dialect dialect) {
        Class<? extends Annotation> tableIdClass = mpAnnotationClass(MP_TABLE_ID);
        Annotation tableId = tableIdClass == null ? null : field.getAnnotation(tableIdClass);
        boolean primaryKey = tableId != null;
        return new ColumnDef()
                .setName(resolveColumnName(field, tableId))
                .setType(resolveSqlType(field, dialect))
                .setPrimaryKey(primaryKey)
                .setAutoIncrement(primaryKey && isMpAutoIncrement(tableId))
                .setNullable(!primaryKey);
    }

    /**
     * 解析字段对应的列名。
     *
     * <p>优先级：MyBatis-Plus {@code @TableId#value} → {@code @TableField#value} →
     * 驼峰转下划线（与表名推导规则一致）。</p>
     *
     * @param field   实体字段
     * @param tableId 已解析的 MyBatis-Plus 主键注解实例（可为 null）
     * @return 列名
     */
    private String resolveColumnName(Field field, Annotation tableId) {
        if (tableId != null) {
            String value = readAnnotationString(tableId, "value");
            if (value != null && !value.isBlank()) {
                return value;
            }
            return camelToSnake(field.getName());
        }
        Class<? extends Annotation> tableFieldClass = mpAnnotationClass(MP_TABLE_FIELD);
        if (tableFieldClass != null) {
            Annotation tableField = field.getAnnotation(tableFieldClass);
            if (tableField != null) {
                String value = readAnnotationString(tableField, "value");
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return camelToSnake(field.getName());
    }

    /**
     * 解析字段对应的 SQL 类型名。
     *
     * <p>有方言时按 {@link Dialect#getTypeName(int, long, int, int)} 渲染
     * （如 MySQL 的 {@code VARCHAR(255)}、{@code DATETIME}），否则用内置通用类型表。</p>
     *
     * @param field   实体字段
     * @param dialect 数据库方言（可为 null）
     * @return SQL 类型名
     */
    private String resolveSqlType(Field field, Dialect dialect) {
        int jdbcType = jdbcTypeOf(field.getType());
        if (dialect != null) {
            return dialect.getTypeName(jdbcType, 0, 0, 0);
        }
        return builtinSqlType(jdbcType);
    }

    /**
     * Java 字段类型 → JDBC 类型码。
     *
     * @param type 字段类型
     * @return {@link Types} 常量
     */
    private int jdbcTypeOf(Class<?> type) {
        if (type == Long.class || type == long.class) {
            return Types.BIGINT;
        }
        if (type == Integer.class || type == int.class) {
            return Types.INTEGER;
        }
        if (type == Short.class || type == short.class) {
            return Types.SMALLINT;
        }
        if (type == Byte.class || type == byte.class) {
            return Types.TINYINT;
        }
        if (type == Boolean.class || type == boolean.class) {
            return Types.BOOLEAN;
        }
        if (type == Double.class || type == double.class) {
            return Types.DOUBLE;
        }
        if (type == Float.class || type == float.class) {
            return Types.FLOAT;
        }
        if (type == BigDecimal.class) {
            return Types.DECIMAL;
        }
        if (type == LocalDate.class) {
            return Types.DATE;
        }
        if (type == LocalTime.class) {
            return Types.TIME;
        }
        if (type == LocalDateTime.class || type == Date.class) {
            return Types.TIMESTAMP;
        }
        if (type == byte[].class) {
            return Types.BLOB;
        }
        // String / 枚举 / Character / 其它可序列化对象统一按字符串列处理
        return Types.VARCHAR;
    }

    /**
     * 内置通用 SQL 类型表，仅在方言不可用时兜底。
     *
     * @param jdbcType JDBC 类型码
     * @return 通用 SQL 类型名
     */
    private String builtinSqlType(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIGINT -> "BIGINT";
            case Types.INTEGER -> "INT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.TINYINT -> "TINYINT";
            case Types.BOOLEAN, Types.BIT -> "BIT";
            case Types.DOUBLE -> "DOUBLE";
            case Types.FLOAT, Types.REAL -> "FLOAT";
            case Types.DECIMAL, Types.NUMERIC -> "DECIMAL(38, 10)";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIMESTAMP -> "DATETIME";
            case Types.BLOB, Types.VARBINARY, Types.BINARY, Types.LONGVARBINARY -> "BLOB";
            case Types.CLOB, Types.LONGVARCHAR -> "TEXT";
            default -> "VARCHAR(255)";
        };
    }

    /**
     * 生成建表 DDL。
     *
     * <p>优先交给方言 {@link DdlProvider}（如 MySQL 实现自带 IF NOT EXISTS、
     * 反引号转义与 utf8mb4 表选项）；无匹配方言时使用内置标准 SQL 兜底
     * （兜底以 MySQL/H2 语法为准，主键输出 {@code AUTO_INCREMENT}）。</p>
     *
     * @param tableName   表名
     * @param columns     列定义列表
     * @param ddlProvider 方言 DDL 生成器（可为 null）
     * @return 建表 SQL
     */
    private String renderCreateTable(String tableName, List<ColumnDef> columns, DdlProvider ddlProvider) {
        if (ddlProvider != null) {
            return ddlProvider.createTable(tableName, columns);
        }
        return builtinCreateTable(tableName, columns);
    }

    /**
     * 内置标准 SQL 建表兜底。
     *
     * @param tableName 表名
     * @param columns   列定义列表
     * @return 建表 SQL
     */
    private String builtinCreateTable(String tableName, List<ColumnDef> columns) {
        List<String> primaryKeys = new ArrayList<>();
        List<String> columnDefs = new ArrayList<>();
        for (ColumnDef column : columns) {
            StringBuilder def = new StringBuilder("  ").append(column.getName()).append(' ')
                    .append(column.getType() == null || column.getType().isBlank()
                            ? "VARCHAR(255)" : column.getType());
            if (column.isPrimaryKey()) {
                def.append(" NOT NULL");
                primaryKeys.add(column.getName());
            } else {
                def.append(column.isNullable() ? " NULL" : " NOT NULL");
            }
            if (column.isAutoIncrement()) {
                def.append(" AUTO_INCREMENT");
            }
            columnDefs.add(def.toString());
        }
        // 主键子句与列定义一样参与逗号分隔，避免最后一列与 PRIMARY KEY 之间缺逗号
        if (!primaryKeys.isEmpty()) {
            columnDefs.add("  PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
        }
        return new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(tableName)
                .append(" (\n").append(String.join(",\n", columnDefs)).append("\n)").toString();
    }

    /**
     * 判断表是否已存在。
     *
     * <p>按数据库标识符大小写存储习惯调整候选名后查询元数据；
     * 元数据查询失败时按「不存在」处理，由 {@code IF NOT EXISTS} 语句级兜底。</p>
     *
     * @param dataSource 数据源
     * @param tableName  表名
     * @return true 表示表已存在
     */
    private boolean tableExists(DataSource dataSource, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String candidate = metaData.storesUpperCaseIdentifiers()
                    ? tableName.toUpperCase(Locale.ROOT) : tableName;
            String[] types = {"TABLE"};
            try (ResultSet tables = metaData.getTables(connection.getCatalog(), connection.getSchema(),
                    candidate, types)) {
                if (tables.next()) {
                    return true;
                }
            }
            try (ResultSet tables = metaData.getTables(connection.getCatalog(), connection.getSchema(),
                    tableName, types)) {
                return tables.next();
            }
        } catch (SQLException e) {
            log.warn("[EntitySchemaDriver] 查询表 {} 元数据失败，按不存在处理: {}", tableName, e.getMessage());
            return false;
        }
    }

    /**
     * 执行建表 DDL。
     *
     * @param dataSource 数据源
     * @param tableName  表名（用于日志与异常信息）
     * @param ddl        建表 SQL
     */
    private void executeDdl(DataSource dataSource, String tableName, String ddl) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            log.info("[EntitySchemaDriver] 建表完成: {}", tableName);
            if (log.isDebugEnabled()) {
                log.debug("[EntitySchemaDriver] DDL: {}", ddl);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("实体驱动建表失败: " + tableName + "，DDL: " + ddl, e);
        }
    }

    /**
     * 按方言协议解析 {@link Dialect} 扩展。
     *
     * @param protocol 方言协议名（可为 null）
     * @return 方言实例；未注册或解析失败时为 null
     */
    private Dialect resolveDialect(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return null;
        }
        try {
            // Dialect.getExtension 为包私有，这里直接经 ServiceProvider 按协议取方言扩展
            return ServiceProvider.of(Dialect.class).getExtension(protocol);
        } catch (Exception e) {
            log.debug("[EntitySchemaDriver] 解析方言 {} 失败，使用内置类型映射: {}",
                    protocol, e.getMessage());
            return null;
        }
    }

    /**
     * 按方言协议解析支持该协议的 {@link DdlProvider} 扩展。
     *
     * @param protocol   方言协议名（可为 null）
     * @param dataSource 数据源（作为 SPI 上下文参数）
     * @return 匹配的 DDL 生成器；无匹配时为 null
     */
    private DdlProvider resolveDdlProvider(String protocol, DataSource dataSource) {
        try {
            for (DdlProvider provider
                    : ServiceProvider.of(DdlProvider.class).getNewExtensions(DdlProvider.SPI_NAME, dataSource)) {
                if (provider != null && provider.supports(protocol)) {
                    return provider;
                }
            }
        } catch (Exception e) {
            log.debug("[EntitySchemaDriver] 解析 DdlProvider 扩展失败，使用内置建表兜底: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 读取 MyBatis-Plus {@code @TableName} 的表名。
     *
     * @param entityClass 实体类
     * @return 表名；注解不存在或 classpath 无 MyBatis-Plus 时为 null
     */
    private String readMpTableName(Class<?> entityClass) {
        Class<? extends Annotation> annotationClass = mpAnnotationClass(MP_TABLE_NAME);
        if (annotationClass == null) {
            return null;
        }
        Annotation annotation = entityClass.getAnnotation(annotationClass);
        return annotation == null ? null : readAnnotationString(annotation, "value");
    }

    /**
     * 判断字段是否为 MyBatis-Plus {@code @TableField(exist = false)} 的非持久化字段。
     *
     * @param field 实体字段
     * @return true 表示应跳过该字段
     */
    private boolean isMpIgnoredField(Field field) {
        Class<? extends Annotation> tableFieldClass = mpAnnotationClass(MP_TABLE_FIELD);
        if (tableFieldClass == null) {
            return false;
        }
        Annotation tableField = field.getAnnotation(tableFieldClass);
        if (tableField == null) {
            return false;
        }
        try {
            Object exist = tableFieldClass.getMethod("exist").invoke(tableField);
            return exist instanceof Boolean present && !present;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断 MyBatis-Plus 主键注解是否声明 {@code IdType.AUTO} 自增。
     *
     * @param tableId 主键注解实例
     * @return true 表示自增主键
     */
    private boolean isMpAutoIncrement(Annotation tableId) {
        try {
            Object idType = tableId.annotationType().getMethod("type").invoke(tableId);
            return idType != null && "AUTO".equals(idType.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取注解的字符串属性。
     *
     * @param annotation 注解实例
     * @param attribute  属性名
     * @return 属性值；读取失败时为 null
     */
    private String readAnnotationString(Annotation annotation, String attribute) {
        try {
            Object value = annotation.annotationType().getMethod(attribute).invoke(annotation);
            return value instanceof String text ? text : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按类名加载注解类（可选依赖）。
     *
     * <p>加载结果缓存；classpath 上不存在（如未引入 MyBatis-Plus）时以
     * {@code Void.class} 占位，避免每次解析都重复触发类加载。</p>
     *
     * @param className 注解类全限定名
     * @return 注解类；不可用时为 null
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> mpAnnotationClass(String className) {
        Class<? extends Annotation> cached = switch (className) {
            case MP_TABLE_NAME -> mpTableNameClass;
            case MP_TABLE_ID -> mpTableIdClass;
            case MP_TABLE_FIELD -> mpTableFieldClass;
            default -> null;
        };
        if (cached != null) {
            return (Class<?>) cached == Void.class ? null : cached;
        }
        synchronized (EntitySchemaDriver.class) {
            cached = switch (className) {
                case MP_TABLE_NAME -> mpTableNameClass;
                case MP_TABLE_ID -> mpTableIdClass;
                case MP_TABLE_FIELD -> mpTableFieldClass;
                default -> null;
            };
            if (cached == null) {
                try {
                    cached = Class.forName(className).asSubclass(Annotation.class);
                } catch (Throwable e) {
                    // classpath 未提供 MyBatis-Plus 时按无注解处理
                    cached = (Class<? extends Annotation>) (Class<?>) Void.class;
                }
            }
            switch (className) {
                case MP_TABLE_NAME -> mpTableNameClass = cached;
                case MP_TABLE_ID -> mpTableIdClass = cached;
                case MP_TABLE_FIELD -> mpTableFieldClass = cached;
                default -> {
                }
            }
            return (Class<?>) cached == Void.class ? null : cached;
        }
    }

    /**
     * 驼峰命名转下划线命名（与 {@code AbstractEngine#resolveTableName} 同规则）。
     *
     * @param name 驼峰命名（如 {@code userName}）
     * @return 下划线命名（如 {@code user_name}）
     */
    private static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
