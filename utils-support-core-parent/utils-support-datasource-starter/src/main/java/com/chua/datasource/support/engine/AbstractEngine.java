package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.datasource.support.meta.DefaultMetaData;
import com.chua.datasource.support.ddl.DslManager;
import com.chua.datasource.support.user.UserManager;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.wrapper.EngineDeleteWrapper;
import com.chua.datasource.support.wrapper.EngineQueryWrapper;
import com.chua.datasource.support.wrapper.EngineUpdateWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽象引擎基类，提供默认的 Engine 接口实现。
 * <p>
 * 子类只需实现 {@link #executeNewQuery} 方法即可获得完整的 ORM 能力。
 * UPDATE/DELETE 操作默认基于内存 dataStores 执行，子类可重写
 * {@link #executeUpdate} 和 {@link #executeDelete} 实现真实数据库操作。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractEngine implements Engine {

    /**
     * 内存数据存储映射表，键为表名，值为数据列表。
     */
    protected final Map<String, List<?>> dataStores = new ConcurrentHashMap<>();

    /**
     * 数据源映射表，存储所有注册的数据源。
     */
    protected final Map<String, EngineDataSource<Object>> dataSources = new ConcurrentHashMap<>();

    /**
     * 默认数据源名称。
     */
    protected String defaultDataSourceName;

    @Override
    /** 查询 */
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new EngineQueryWrapper<>(this, entityClass);
    }

    @Override
    /** 更新 */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new EngineUpdateWrapper<>(this, entityClass);
    }

    @Override
    /** 删除 */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new EngineDeleteWrapper<>(this, entityClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 添加DataSource */
    public <T> Engine addDataSource(String name, EngineDataSource<T> ds) {
        dataSources.put(name, (EngineDataSource<Object>) ds);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    @Override
    /** 设置DefaultDataSourceName */
    public Engine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    @Override
    /** Store */
    public <T> Engine store(String name, List<T> data) {
        dataStores.put(name, new ArrayList<>(data));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    @Override
    /** 获取Executor */
    public SqlExecutor getExecutor(String n) {
        return null;
    }

    @Override
    /** 获取Executor */
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 获取DataSource */
    public <T> EngineDataSource<T> getDataSource(String n) {
        return (EngineDataSource<T>) dataSources.get(n);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 获取DataSource */
    public <T> EngineDataSource<T> getDataSource() {
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
    }

    @Override
    /** 获取Dialect */
    public Dialect getDialect(String n) {
        return null;
    }

    @Override
    /** 获取DefaultDataSourceName */
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    @Override
    /** Meta */
    public com.chua.common.support.lang.datasource.meta.MetaData meta() {
        return new DefaultMetaData(this);
    }

    @Override
    /**
     * 关闭引擎，释放所有已注册数据源的底层资源。
     *
     * <p>遍历所有 EngineDataSource 逐一关闭，再清理内存数据与数据源映射。</p>
     */
    public void close() {
        for (EngineDataSource<?> ds : dataSources.values()) {
            try {
                ds.close();
            } catch (Exception ignored) {
                // 忽略单个数据源关闭异常，继续关闭其余
            }
        }
        dataStores.clear();
        dataSources.clear();
    }

    @Override
    /** 设置Tunnel */
    public Engine setTunnel(String dataSourceName, com.chua.common.support.network.tunnel.Tunnel tunnel) {
        EngineDataSource<?> ds = dataSources.get(dataSourceName);
        if (ds != null) {
            ((EngineDataSource<Object>) ds).setTunnelPort(tunnel.open());
        }
        return this;
    }

    @Override
    /** 打开Tunnel */
    public int openTunnel(String dataSourceName, com.chua.common.support.network.tunnel.Tunnel tunnel) {
        EngineDataSource<?> ds = dataSources.get(dataSourceName);
        if (ds != null) {
            int port = tunnel.open();
            ((EngineDataSource<Object>) ds).setTunnelPort(port);
            return port;
        }
        return -1;
    }

    @Override
    /** 关闭Tunnel */
    public Engine closeTunnel(String dataSourceName) {
        EngineDataSource<?> ds = dataSources.get(dataSourceName);
        if (ds != null && ds.tunnelPort() > 0) {
            // 注意：此处仅重置端口标记，实际隧道关闭由持有者负责
            ((EngineDataSource<Object>) ds).setTunnelPort(0);
        }
        return this;
    }

    /**
     * 创建新版查询包装器。
     *
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 查询包装器
     */
    public <T> EngineQueryWrapper<T> queryNew(Class<T> entityClass) {
        return new EngineQueryWrapper<>(this, entityClass);
    }

    /**
     * 执行新版查询。
     *
     * @param wrapper 查询包装器
     * @param <T>     实体类型
     * @return 查询结果
     */
    public <T> List<T> execute(EngineQueryWrapper<T> wrapper) {
        return executeQuery(wrapper, wrapper.getEntityClass());
    }

    /**
     * 分页执行新版查询。
     *
     * @param wrapper 查询包装器
     * @param pn      页码
     * @param ps      每页大小
     * @param <T>     实体类型
     * @return 分页结果
     */
    public <T> Page<T> executePage(EngineQueryWrapper<T> wrapper, int pn, int ps) {
        return executePage(wrapper, wrapper.getEntityClass(), pn, ps);
    }

    /**
     * 执行旧版查询。
     *
     * @param wrapper     查询包装器
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 查询结果
     */
    public <T> List<T> executeQuery(LambdaQueryWrapper<T> wrapper, Class<T> entityClass) {
        var sql = wrapper.buildSql();
        List<T> result = executeNewQuery(sql.whereClause(), sql.params().toArray(), entityClass);
        if (result == null || result.isEmpty()) {
            return result;
        }
        // 过滤 null 元素，避免排序引发 NPE
        List<T> valid = result.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        if (sql.orderBys() != null && !sql.orderBys().isEmpty() && !valid.isEmpty()) {
            List<T> sorted = new ArrayList<>(valid);
            sorted.sort((a, b) -> compareOrdered(a, b, sql.orderBys()));
            return sorted;
        }
        return valid;
    }

    /**
     * 按 ORDER BY 列表比较两个对象。
     *
     * @param a        对象 A
     * @param b        对象 B
     * @param orderBys 排序字段列表，格式为 "fieldName ASC" 或 "fieldName DESC"
     * @param <T>      对象类型
     * @return 比较结果
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> int compareOrdered(T a, T b, List<String> orderBys) {
        for (String ob : orderBys) {
            String[] parts = ob.trim().split("\\s+");
            String field = parts[0];
            boolean desc = parts.length > 1 && "DESC".equalsIgnoreCase(parts[1]);
            Object va = getPropertyValue(a, field);
            Object vb = getPropertyValue(b, field);
            int cmp;
            if (va == null && vb == null) {
                cmp = 0;
            } else if (va == null) {
                cmp = -1;
            } else if (vb == null) {
                cmp = 1;
            } else if (va instanceof Comparable && vb instanceof Comparable) {
                cmp = ((Comparable) va).compareTo(vb);
            } else {
                cmp = va.toString().compareTo(vb.toString());
            }
            if (cmp != 0) {
                return desc ? -cmp : cmp;
            }
        }
        return 0;
    }

    /**
     * 通过反射获取对象属性值。
     *
     * @param bean  对象实例
     * @param field 字段名
     * @return 属性值，获取失败返回 null
     */
    private static Object getPropertyValue(Object bean, String field) {
        return MethodCache.getValue(bean, field);
    }

    /**
     * 分页执行旧版查询。
     *
     * @param wrapper 查询包装器
     * @param ec      实体类类型
     * @param pn      页码
     * @param ps      每页大小
     * @param <T>     实体类型
     * @return 分页结果
     */
    public <T> Page<T> executePage(LambdaQueryWrapper<T> wrapper, Class<T> ec, int pn, int ps) {
        List<T> all = executeQuery(wrapper, ec);
        int from = (pn - 1) * ps;
        int to = Math.min(from + ps, all.size());
        if (from >= all.size()) {
            return new Page<>(pn, ps, all.size(), Collections.emptyList());
        }
        return new Page<>(pn, ps, all.size(), all.subList(from, to));
    }

    /**
     * 执行基于 WHERE 条件的查询。
     *
     * @param where       WHERE 子句
     * @param params      参数值数组
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 查询结果
     */
    protected abstract <T> List<T> executeNewQuery(
            String where, Object[] params, Class<T> entityClass);

    /**
     * 执行更新操作。
     * <p>默认调用内存实现，子类可重写。</p>
     *
     * @param sql  更新 SQL 信息
     * @param <T>  实体类型
     * @return 影响行数
     */
    public <T> int executeUpdate(UpdateSql<T> sql) {
        return executeUpdateInMemory(sql);
    }

    /**
     * 执行删除操作。
     * <p>默认调用内存实现，子类可重写。</p>
     *
     * @param sql  删除 SQL 信息
     * @param <T>  实体类型
     * @return 影响行数
     */
    public <T> int executeDelete(DeleteSql<T> sql) {
        return executeDeleteInMemory(sql);
    }

    @SuppressWarnings("unchecked")
    /** 执行更新InMemory */
    private <T> int executeUpdateInMemory(UpdateSql<T> sql) {
        List<T> data = getData(sql.entityClass());
        if (data.isEmpty()) {
            return 0;
        }
        String where = sql.whereClause();
        String setClause = sql.setClause();
        if (setClause == null || setClause.isEmpty()) {
            return 0;
        }

        // 解析 SET 子句，分离参数
        List<Object> params = sql.params();
        Map<String, Object> setValues = new LinkedHashMap<>();
        String[] setParts = setClause.split(", ");
        int setCount = setParts.length;
        for (int i = 0; i < setCount; i++) {
            int eqIdx = setParts[i].indexOf(" = ");
            if (eqIdx > 0) {
                setValues.put(setParts[i].substring(0, eqIdx), params.get(i));
            }
        }

        // WHERE 参数在 SET 参数之后
        List<Object> whereParams;
        int totalParams = params.size();
        if (totalParams > setCount) {
            whereParams = params.subList(setCount, totalParams);
        } else {
            whereParams = Collections.emptyList();
        }

        // 过滤并更新
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> whereParamList = new ArrayList<>(whereParams);
        var predicate = parser.parse(where, whereParamList);
        List<T> updated = data.stream().filter(predicate).toList();
        for (T item : updated) {
            for (Map.Entry<String, Object> entry : setValues.entrySet()) {
                setFieldValue(item, entry.getKey(), entry.getValue());
            }
        }
        return updated.size();
    }

    @SuppressWarnings("unchecked")
    /** 执行删除InMemory */
    private <T> int executeDeleteInMemory(DeleteSql<T> sql) {
        List<T> data = getData(sql.entityClass());
        if (data.isEmpty()) {
            return 0;
        }
        String where = sql.whereClause();
        if (where == null || where.isEmpty()) {
            return 0;
        }

        List<Object> params = sql.params();
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> paramList = (params != null) ? new ArrayList<>(params) : new ArrayList<>();
        var predicate = parser.parse(where, paramList);
        int before = data.size();
        List<T> remaining = data.stream().filter(predicate.negate()).toList();
        int removed = before - remaining.size();
        if (removed > 0) {
            List<T> next = new ArrayList<>(remaining);
            for (Map.Entry<String, List<?>> entry : dataStores.entrySet()) {
                if (entry.getValue() == data) {
                    dataStores.put(entry.getKey(), next);
                }
            }
        }
        return removed;
    }

    /**
     * 为对象的字段设置值（基于反射）。
     *
     * @param obj   目标对象
     * @param field 字段名
     * @param value 字段值
     */
    private void setFieldValue(Object obj, String field, Object value) {
        MethodCache.setValue(obj, field, value);
    }

    /**
     * 获取指定实体类对应的数据列表。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 数据列表
     */
    @SuppressWarnings("unchecked")
    protected <T> List<T> getData(Class<T> entityClass) {
        String tableName = getTableName(entityClass);
        List<?> data = dataStores.get(tableName);
        if (data == null) {
            data = dataStores.get("default");
        }
        if (data == null) {
            Optional<List<?>> first = dataStores.values().stream().findFirst();
            data = first.orElse(Collections.emptyList());
        }
        return (List<T>) data;
    }

    /**
     * 将实体类名称转为表名（驼峰转下划线）。
     * <p>实体类标注 {@link TableName} 时优先使用注解值。</p>
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 表名
     */
    protected <T> String getTableName(Class<T> entityClass) {
        return resolveTableName(entityClass);
    }

    /**
     * 解析实体类对应的表名。
     * <p>优先读取 {@link TableName} 注解；未标注时将驼峰命名
     * 转换为下划线命名（如 MyUser → my_user）。</p>
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 表名
     */
    public static <T> String resolveTableName(Class<T> entityClass) {
        TableName annotation = entityClass.getAnnotation(TableName.class);
        if (annotation != null && !annotation.value().isEmpty()) {
            return annotation.value();
        }
        String simpleName = entityClass.getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (char c : simpleName.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /* ==================== 能力入口（与 meta() 同模式） ==================== */

    /**
     * 获取 DDL 管理器入口（与 meta() 同模式）。
     * <p>默认实现抛出 UnsupportedOperationException，由具备
     * DDL 管理能力的引擎子类或 SPI 环境覆盖。</p>
     *
     * @return DdlManager 实例
     */
    public DslManager ddl() {
        throw new UnsupportedOperationException("当前引擎不支持 DDL 管理");
    }

    /**
     * 获取用户管理器入口（与 meta() 同模式）。
     * <p>默认实现抛出 UnsupportedOperationException，由具备
     * 用户管理能力的引擎子类或 SPI 环境覆盖。</p>
     *
     * @return UserManager 实例
     */
    public UserManager user() {
        throw new UnsupportedOperationException("当前引擎不支持用户管理");
    }
}
