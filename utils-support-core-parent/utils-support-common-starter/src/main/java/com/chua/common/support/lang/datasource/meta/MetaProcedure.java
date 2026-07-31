package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.ProcedureDef;

import java.util.List;

/**
 * 存储过程元数据操作接口。
 * <p>
 * 提供存储过程和函数的查询、创建、删除、调用等操作。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 列出所有存储过程
 * List<ProcedureDef> procs = engine.meta().procedure().list();
 *
 * // 查询存储过程定义
 * ProcedureDef proc = engine.meta().procedure("sp_find_user").get();
 *
 * // 创建存储过程
 * engine.meta().procedure()
 *     .create("sp_find_user")
 *     .in("p_name", "VARCHAR")
 *     .out("p_count", "INT")
 *     .body("select count(*) into p_count from user where name like p_name")
 *     .language("SQL")
 *     .execute();
 *
 * // 调用存储过程
 * List<Map<String, Object>> result = engine.meta().procedure()
 *     .call("张三");
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MetaProcedure {

    /**
     * 列出当前 catalog/schema 下的所有存储过程和函数。
     *
     * @return 存储过程定义列表
     */
    List<ProcedureDef> list();

    /**
     * 获取指定存储过程的定义。
     *
     * @param procedureName 存储过程名
     * @return 存储过程定义
     */
    ProcedureDef get(String procedureName);

    /**
     * 创建存储过程（链式构建器）。
     *
     * @param procedureName 存储过程名
     * @return 创建存储过程构建器
     */
    ProcedureCreateBuilder create(String procedureName);

    /**
     * 删除存储过程。
     *
     * @param procedureName 存储过程名
     * @return true 删除成功
     */
    boolean drop(String procedureName);

    /**
     * 调用存储过程。
     *
     * @param args 参数值列表
     * @return 查询结果（每行为一个 Map）
     */
    List<java.util.Map<String, Object>> call(Object... args);

    /**
     * 调用存储过程（指定名称）。
     *
     * @param procedureName 存储过程名
     * @param args          参数值列表
     * @return 查询结果
     */
    List<java.util.Map<String, Object>> call(String procedureName, Object... args);
}
