package com.chua.common.support.lang.datasource.engine.ddl;

import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
* DDL 语句生成 SPI 扩展点，按数据库方言生成建库、删库、建表语句。
*
* <p>仿照 {@code Flyway} 扩展点模式：各数据库 starter（或业务方）实现本接口并通过
* {@code META-INF/extensions/} 注册后，{@code JdbcEngine} 在执行 DDL 操作时按当前
* 方言协议（如 {@code mysql}、{@code postgresql}）查找首个
* {@link #supports(String)} 返回 true 的实现生成语句；
* 无匹配实现时由引擎使用内置语法兜底。</p>
*
* <p>使用示例：
* <pre>{@code
* @Spi(DdlProvider.SPI_NAME)
* public class MysqlDdlProvider implements DdlProvider {
*     {@literal @}Override
*     public boolean supports(String protocol) {
*         return "mysql".equals(protocol);
*     }
*
*     {@literal @}Override
*     public String createDatabase(String dbName) {
*         return "create database `" + dbName + "` character set utf8mb4";
*     }
* }
* }</pre>
* </p>
*
* <p>注册文件：{@code META-INF/extensions/com.chua.common.support.lang.datasource.engine.ddl.DdlProvider}，
* 内容格式：{@code 别名=实现类全限定名}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi(DdlProvider.SPI_NAME)
public interface DdlProvider {

    /**
    * DDL 生成器 SPI 扩展名。
     */
    String SPI_NAME = "ddl-provider";

    /**
    * 判断是否支持给定数据库方言协议。
    *
    * @param protocol 方言协议名（如 "mysql"、"postgresql"），来自 {@code Dialect} 的协议标识
    * @return true 表示支持该方言
     */
    boolean supports(String protocol);

    /**
    * 生成建库语句。
    *
    * @param dbName 数据库名称
    * @return 完整的建库 DDL 语句
     */
    String createDatabase(String dbName);

    /**
    * 生成删库语句。
    *
    * @param dbName 数据库名称
    * @return 完整的删库 DDL 语句
     */
    String dropDatabase(String dbName);

    /**
    * 生成建表语句。
    *
    * @param tableName 表名
    * @param columns   列定义列表
    * @return 完整的建表 DDL 语句
     */
    String createTable(String tableName, List<ColumnDef> columns);
}
