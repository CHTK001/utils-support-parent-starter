package com.chua.mysql.support.meta;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.mysql.support.engine.MysqlEngine;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MysqlMetaData 生产实现真实容器测试。
 */
class MysqlMetaDataIT {

    @BeforeAll
    static void assumeReachable() {
        try {
            var s = new java.net.Socket();
            s.connect(new InetSocketAddress("172.16.0.40", 3306), 2000);
            s.close();
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达，跳过");
        }
    }

    @Test
    void metaData_tableAndView() {
        MysqlEngine engine = new MysqlEngine();
        engine.addDataSource("mysql", "172.16.0.40", 3306, "report",
                "root", "root@");
        try {
            MetaData meta = engine.meta();
            assertNotNull(meta);

            /* table 元数据查询不抛异常 */
            assertDoesNotThrow(() -> meta.table());

            /* view 元数据查询不抛异常 */
            assertDoesNotThrow(() -> meta.view());
        } finally {
            engine.close();
        }
    }

    @Test
    void metaData_indexAndForeignKey() {
        MysqlEngine engine = new MysqlEngine();
        engine.addDataSource("mysql", "172.16.0.40", 3306, "report",
                "root", "root@");
        try {
            MetaData meta = engine.meta();

            /* index 元数据查询不抛异常 */
            assertDoesNotThrow(() -> meta.index());

            /* 外键元数据查询不抛异常 */
            assertDoesNotThrow(() -> meta.fk());
        } finally {
            engine.close();
        }
    }
}
