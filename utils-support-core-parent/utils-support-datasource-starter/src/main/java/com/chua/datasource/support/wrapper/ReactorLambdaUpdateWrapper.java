package com.chua.datasource.support.wrapper;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.wrapper.AbstractLambdaWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 响应式 Lambda 更新包装器，条件 API 与 {@code LambdaUpdateWrapper} 一致，
* 终端方法 {@link #update()} 返回 {@link Mono}。
*
* <pre>{@code
* Mono<Integer> affected = engine.update(User.class)
*     .set(User::getName, "李四")
*     .eq(User::getId, 1)
*     .update();
* }</pre>, 1)
*     .update();
* }</pre>
*
* @param <T> 实体类型
* @author CH
* @since 4.0.0.42
 */
public class ReactorLambdaUpdateWrapper<T> extends AbstractLambdaWrapper<T, ReactorLambdaUpdateWrapper<T>> {

    /**
    * 底层同步引擎
     */
    private final Engine engine;

    /**
    * 设置 值映射：列名 → 新值
     */
    private final Map<String, Object> setValues = new LinkedHashMap<>();

    /**
    * 创建响应式更新包装器。
    *
    * @param engine      底层引擎
    * @param entityClass 实体类
     */
    public ReactorLambdaUpdateWrapper(Engine engine, Class<T> entityClass) {
        super(entityClass);
        this.engine = engine;
    }

    /**
    * 添加 设置 列和值（Lambda 方式）。
    * @param column column
    * @param value 值
    * @return 设置的结果
     */
    public ReactorLambdaUpdateWrapper<T> set(SFunction<T, ?> column, Object value) {
        setValues.put(resolveColumn(column), value);
        return this;
    }

    /**
    * 添加 设置 列和值（字符串方式）。
    * @param column column
    * @param value 值
    * @return 设置的结果
     */
    public ReactorLambdaUpdateWrapper<T> set(String column, Object value) {
        setValues.put(column, value);
        return this;
    }

    /**
    * 获取 设置 值映射。
    *
    * @return SET 值映射（列名 → 新值）
     */
    public Map<String, Object> getSetValues() {
        return setValues;
    }

    /**
    * 构建更新 SQL 信息。
    * @return 构建sql的结果
     */
    public UpdateSql<T> buildSql() {
        List<Object> params = new ArrayList<>();
        StringBuilder setSb = new StringBuilder();
        for (Map.Entry<String, Object> e : setValues.entrySet()) {
            if (!setSb.isEmpty()) {
                setSb.append(", ");
            }
            setSb.append(e.getKey()).append(" = ?");
            params.add(e.getValue());
        }
        StringBuilder where = new StringBuilder();
        buildWhere(where, params);
        return new UpdateSql<>(entityClass, setSb.toString(), where.toString(), params);
    }

    /**
    * 执行更新操作，返回受影响行数的 Mono。
    *
    * @return 受影响行数 Mono
     */
    public Mono<Integer> update() {
        return Mono.fromCallable(this::doUpdate)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
    * 同步执行更新（内部使用）。
    * @return 执行更新的结果
     */
    private int doUpdate() {
        UpdateSql<T> sql = buildSql();
        String tableName = com.chua.datasource.support.engine.AbstractEngine.resolveTableName(entityClass);
        StringBuilder fullSql = new StringBuilder("UPDATE ")
                .append(tableName)
                .append(" SET ")
                .append(sql.setClause());
        if (sql.hasWhere()) {
            fullSql.append(" WHERE ").append(sql.whereClause());
        }
        return engine.getExecutor().execute(fullSql.toString(), sql.params().toArray());
    }

    @Override
    protected ReactorLambdaUpdateWrapper<T> newInstance() {
        return new ReactorLambdaUpdateWrapper<>(engine, entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        return LambdaUtils.resolveColumn(column);
    }

    /**
    * 构建 WHERE 子句和参数列表。
    * @param sb sb
    * @param params 参数
     */
    protected void buildWhere(StringBuilder sb, List<Object> params) {
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            renderCondition(sb, params, conditions.get(i));
        }
    }

    /**
    * 渲染单个条件为 SQL 片段。
    * @param sb sb
    * @param params 参数
    * @param c c
     */
    protected void renderCondition(StringBuilder sb, List<Object> params, Condition c) {
        if (c.isNested()) {
            sb.append("(");
            for (int i = 0; i < c.getNested().size(); i++) {
                if (i > 0) {
                    sb.append(" ").append(c.getNestedOperator()).append(" ");
                }
                renderCondition(sb, params, c.getNested().get(i));
            }
            sb.append(")");
            return;
        }
        String col = c.getColumnName();
        if (col == null) {
            col = "?";
        }
        sb.append(col).append(" ").append(c.getOperator()).append(" ?");
        params.add(c.getValue());
    }
}