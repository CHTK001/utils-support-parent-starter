package com.chua.mysql.support.meta;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.chua.common.support.lang.datasource.meta.MetaData;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MysqlMetaData 生产实现真实容器测试。
 * 验证 meta() 的 table/view/index 元数据查询。
 */
class MysqlMetaDataIT {

    private static final String HOST = "172.16.0.40";

    @BeforeAll
    static void assumeReachable() {
        Assumptions.assumeTrue(reachable(HOST, 3306), "MySQL 不可达，跳过");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void metaData_tableAndViewQueries() {
        var engine = new com.chua.mysql.support.engine.MysqlEngine();
        engine.addDataSource("mysql",
                "jdbc:mysql://" + HOST + ":3306/report?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "root@");
        try {
            MetaData meta = engine.meta();
            assertNotNull(meta);

            /* table 元数据（不抛异常即通过） */
            assertDoesNotThrow(() -> meta.table());

            /* view 元数据 */
            assertDoesNotThrow(() -> meta.view());
        } finally {
            engine.close();
        }
    }
}
