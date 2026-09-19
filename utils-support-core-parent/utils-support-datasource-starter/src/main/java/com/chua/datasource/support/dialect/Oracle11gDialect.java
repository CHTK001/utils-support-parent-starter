package com.chua.datasource.support.dialect;
import java.util.Properties;
/**
 * Oracle 11g 方言（ROWNUM 分页，无 偏移量...获取）。
*/
public class Oracle11gDialect extends SqlDialect {
    public static final String VERSION = "Oracle 11g"; // 版本
    /**
     * Oracle11gdialect。
     */
    public Oracle11gDialect() { super("oracle11g"); }
    /**
     * Oracle11gdialect。
     * @param properties 属性
     */
    public Oracle11gDialect(Properties properties) { super("oracle11g", properties); }
}
