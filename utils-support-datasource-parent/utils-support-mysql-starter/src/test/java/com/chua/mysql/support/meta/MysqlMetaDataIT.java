package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.mysql.support.engine.EnvLoader;
import com.chua.mysql.support.engine.MysqlEngine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MysqlMetaData 生产实现真实容器测试。
 * 连接配置来自 .env.mysql（REPORT_* 段，端口 3306）。
 */
class MysqlMetaDataIT {

    private static final String HOST   = EnvLoader.get("REPORT_HOST",  "172.16.0.40");
    private static final int    PORT   = EnvLoader.getInt("REPORT_PORT", 3306);
    private static final String DB     = EnvLoader.get("REPORT_DB",    "report");
    private static final String USER   = EnvLoader.get("REPORT_USER",  "root");
    private static final String PASS   = EnvLoader.get("REPORT_PASS",  "root@");

    @BeforeAll
    static void assumeReachable() {
        try {
            var s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), 2000);
            s.close();
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达 (" + HOST + ":" + PORT + ")，跳过");
        }
    }

    @Test
    void metaData_tableAndView() {
        MysqlEngine engine = new MysqlEngine();
        engine.addDataSource("mysql", HOST, PORT, DB, USER, PASS);
        try {
            MetaData meta = engine.meta();
            assertNotNull(meta);
            assertDoesNotThrow(() -> meta.table());
            assertDoesNotThrow(() -> meta.view());
        } finally {
            engine.close();
        }
    }

    @Test
    void metaData_indexAndForeignKey() {
        MysqlEngine engine = new MysqlEngine();
        engine.addDataSource("mysql", HOST, PORT, DB, USER, PASS);
        try {
            MetaData meta = engine.meta();
            assertDoesNotThrow(() -> meta.index());
            assertDoesNotThrow(() -> meta.fk());
        } finally {
            engine.close();
        }
    }
}
