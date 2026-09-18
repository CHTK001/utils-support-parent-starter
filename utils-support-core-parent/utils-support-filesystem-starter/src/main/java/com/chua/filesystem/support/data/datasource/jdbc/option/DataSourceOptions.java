package com.chua.filesystem.support.data.datasource.jdbc.option;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;


import lombok.Builder;
import lombok.Data;

/**
* 数据源配置选项。
*
* <p>用于封装数据库连接参数，支持 Builder 模式构建。</p>
*
* @author CH
* @since 4.0.0.42
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class DataSourceOptions {

    /**
    * JDBC URL
    */
    private String url;

    /**
    * 用户名
    */
    private String username;

    /**
    * 密码
    */
    private String password;

    /**
    * 驱动类名
    */
    private String driverClassName;

    /**
    * 连接池最大连接数
    */
    private int maximumPoolSize;

    /**
    * 连接池最小空闲连接数
    */
    private int minimumIdle;

    /**
    * 连接超时时间（毫秒）
    */
    private long connectionTimeout;

    /**
    * 空闲超时时间（毫秒）
    */
    private long idleTimeout;

    /**
    * 最大生命周期（毫秒）
    */
    private long maxLifetime;
}
