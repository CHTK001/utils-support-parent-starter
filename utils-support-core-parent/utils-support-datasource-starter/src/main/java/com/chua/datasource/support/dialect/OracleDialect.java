package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
* Oracle 19c+ 方言（兼容 12c/11g）。
* <p>配置从 {@code META-INF/dialect-env/oracle.env} 加载。</p>
* @author CH
* @since 4.0.0
 */
public class OracleDialect extends SqlDialect {
    public static final String VERSION = "Oracle 19c+ (兼容 12c/11g)"; // 版本

    /**
    * oracledialect。
    */
    public OracleDialect() { super("oracle"); }
    /**
    * oracledialect。
    * @param properties 属性
    */
    public OracleDialect(Properties properties) { super("oracle", properties); }
}
