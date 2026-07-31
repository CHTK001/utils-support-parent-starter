package com.chua.datasource.support.user;

import javax.sql.DataSource;
/**
 * @author CH
 */

public interface DataSourceAware {

    void setDataSource(DataSource dataSource);
}
