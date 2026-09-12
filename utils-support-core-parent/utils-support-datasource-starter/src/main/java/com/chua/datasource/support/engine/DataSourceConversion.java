package com.chua.datasource.support.engine;

import javax.sql.DataSource;
import java.util.List;

/**
* 数据源转换器 SPI 接口。
* <p>
* 将多个物理数据源转换为统一的逻辑数据源（如 Calcite 联邦查询、分库分表sphere 分片等）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface DataSourceConversion {

    /**
    * 将多个数据源转换为统一逻辑数据源。
    *
    * @param dataSources 物理数据源列表
    * @param environment 环境配置
    * @return 逻辑数据源
     */
    DataSource convert(List<DataSource> dataSources, DataSourceEnvironment environment);

    /**
    * 转换器类型标识。
    *
    * @return 类型名称（如 CALCITE、SHARDINGV5）
     */
    String type();
}
