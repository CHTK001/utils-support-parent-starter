package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.TriggerDef;

import java.util.List;

/**
* 触发器元数据操作接口。
* <p>
* 提供触发器的查询、创建、删除等链式操作。
* 通过 {@link #onTable(String)} 指定所属表名后再执行操作。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 列出表的所有触发器
* List<TriggerDef> triggers = engine.meta().trigger().onTable("user").list();
*
* // 创建触发器
* engine.meta().trigger().onTable("user")
*     .create("trg_user_ins")
*     .after("INSERT")
*     .forEachRow()
*     .body("insert into user_log(id) values(new.id)")
*     .execute();
*
* // 便捷方法：创建自增触发器（Oracle 等无 AUTO_INCREMENT 的数据库）
* engine.meta().trigger()
*     .createAutoIncrement("trg_user_id", "user", "id")
*     .execute();
*
* // 删除触发器
* boolean dropped = engine.meta().trigger().drop("trg_user_ins");
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface MetaTrigger {

    /**
    * 指定所属表名。
    *
    * @param tableName 表名
    * @return this
    */
    MetaTrigger onTable(String tableName);

    /**
    * 列出当前表的所有触发器。
    *
    * @return 触发器定义列表
    */
    List<TriggerDef> list();

    /**
    * 获取指定触发器的定义。
    *
    * @param triggerName 触发器名
    * @return 触发器定义
    */
    TriggerDef get(String triggerName);

    /**
    * 创建触发器（链式构建器）。
    *
    * @param triggerName 触发器名
    * @return 创建触发器构建器
    */
    TriggerCreateBuilder create(String triggerName);

    /**
    * 便捷方法：创建自增触发器。
    * <p>适用于 Oracle 等没有原生 AUTO_INCREMENT 的数据库。</p>
    * <p>默认生成 BEFORE INSERT 触发器，配合序列实现自增。</p>
    *
    * @param triggerName 触发器名
    * @param tableName   表名
    * @param columnName  自增列名
    * @return 创建触发器构建器（body 已由方言填充）
    */
    default TriggerCreateBuilder createAutoIncrement(String triggerName, String tableName, String columnName) {
        String seqName = "seq_" + tableName;
        return create(triggerName)
                .before("INSERT")
                .onTable(tableName)
                .body(resolveDialect().getAutoIncrementTriggerBody(triggerName, tableName, columnName, seqName));
    }

    /**
    * 便捷方法：创建自增触发器（指定序列名）。
    *
    * @param triggerName  触发器名
    * @param tableName    表名
    * @param columnName   自增列名
    * @param sequenceName 序列名
    * @return 创建触发器构建器（body 已由方言填充）
    */
    default TriggerCreateBuilder createAutoIncrement(String triggerName, String tableName, String columnName, String sequenceName) {
        return create(triggerName)
                .before("INSERT")
                .onTable(tableName)
                .body(resolveDialect().getAutoIncrementTriggerBody(triggerName, tableName, columnName, sequenceName));
    }

    /**
    * 删除触发器。
    *
    * @param triggerName 触发器名
    * @return true 删除成功
    */
    boolean drop(String triggerName);

    /**
    * 启用触发器。
    *
    * @param triggerName 触发器名
    * @return true 操作成功
    */
    boolean enable(String triggerName);

    /**
    * 禁用触发器。
    *
    * @param triggerName 触发器名
    * @return true 操作成功
    */
    boolean disable(String triggerName);

    /**
    * 获取当前数据库方言（用于生成自增触发器体）。
    *
    * @return 方言实例
    */
    com.chua.common.support.lang.datasource.dialect.Dialect resolveDialect();
}
