package com.chua.common.support.lang.datasource.engine.interceptor;

import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
* 引擎拦截器 SPI 扩展点，提供查询、更新、删除、原生语句执行的生命周期拦截能力。
*
* <p>仿照 {@code Flyway} 扩展点模式：业务方实现本接口并通过
* {@code META-INF/extensions/} 注册后，引擎在执行相关操作的前后
* 自动回调所有拦截器（按 {@code @Spi order} 降序依次触发）。</p>
*
* <p>使用示例：
* <pre>{@code
* @Spi(order = 10)
* public class SlowQueryInterceptor implements EngineInterceptor {
*     {@literal @}Override
*     public void afterQuery(String ql, Object[] params, List<?> result) {
*         log.info("查询完成: {}，耗时 {} 条", ql, result.size());
*     }
* }
* }</pre>
* </p>
*
* <p>注册文件：{@code META-INF/extensions/com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor}，
* 内容格式：{@code 别名=实现类全限定名}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi(EngineInterceptor.SPI_NAME)
public interface EngineInterceptor {

    /**
    * 拦截器 SPI 扩展名。
    */
    String SPI_NAME = "engine-interceptor";

    /**
    * 查询执行前回调。
    *
    * @param ql     查询语句或 WHERE 条件子句
    * @param params 参数列表
    */
    default void beforeQuery(String ql, Object[] params) {
    }

    /**
    * 查询执行后回调。
    *
    * @param ql     查询语句或 WHERE 条件子句
    * @param params 参数列表
    * @param result 查询结果列表
    */
    default void afterQuery(String ql, Object[] params, List<?> result) {
    }

    /**
    * 更新（含删除与原生数据操作语句）执行前回调。
    *
    * @param ql     更新语句或 WHERE 条件子句
    * @param params 参数列表
    */
    default void beforeUpdate(String ql, Object[] params) {
    }

    /**
    * 更新（含删除与原生数据操作语句）执行后回调。
    *
    * @param ql       更新语句或 WHERE 条件子句
    * @param params   参数列表
    * @param affected 受影响行数
    */
    default void afterUpdate(String ql, Object[] params, int affected) {
    }

    /**
    * 执行异常回调。
    *
    * @param ql 语句或 WHERE 条件子句
    * @param params 参数列表
    * @param e 抛出的运行时异常
    */
    default void onError(String ql, Object[] params, RuntimeException e) {
    }
}
