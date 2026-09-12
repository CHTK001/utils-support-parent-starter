package com.chua.datasource.support.user;

import javax.sql.DataSource;

/**
* 数据源感知接口，用于向实现类注入 JDBC 数据源。
*
* @author CH
* @since 4.0.0.42
 */
public interface DataSourceAware {

    /**
    * 设置数据源。
    *
    * @param dataSource JDBC 数据源
     */
    void setDataSource(DataSource dataSource);
}
