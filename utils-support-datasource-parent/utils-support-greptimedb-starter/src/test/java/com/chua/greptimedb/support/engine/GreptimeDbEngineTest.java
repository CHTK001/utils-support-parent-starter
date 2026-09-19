package com.chua.greptimedb.support.engine;

import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.JoinClause;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.datasource.support.annotation.TableName;
import com.chua.greptimedb.support.datasource.GreptimeDbEngineDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GreptimeDbEngine} 单元测试：覆盖 SQL 下推渲染、标识符白名单与生命周期守卫，
 * 全部为纯逻辑断言，不依赖外部 GreptimeDB 服务。
 *
 * @author CH
 * @since 4.0.0.42
 */
class GreptimeDbEngineTest {

    /**
     * 指标实体：字段名需经 snake_case 归一化为列名
     */
    static class Metric {

        /**
         * 主机标签
         */
        private String host;
        /**
         * CPU 使用率
         */
        private Double cpuUtil;
        /**
         * 时间戳
         */
        private Long ts;

        /**
         * @return 主机标签
         */
        public String getHost() {
            return host;
        }

        /**
         * @return CPU 使用率
         */
        public Double getCpuUtil() {
            return cpuUtil;
        }

        /**
         * @return 时间戳
         */
        public Long getTs() {
            return ts;
        }
    }

    /**
     * 表名含非法字符的实体，用于校验标识符白名单。
     */
    @TableName("metric; DROP TABLE t")
    static class BadNamed {

        /**
         * 主键列
         */
        private Long id;
    }

    /**
     * 校验 LIMIT/OFFSET 被下推到服务端，而不是查询后内存截断。
     */
    @Test
    void shouldPushDownLimitAndOffset() {
        String sql = GreptimeDbEngine.buildSelectSql(Metric.class, List.of(), List.of(),
                "host = ?", null, null, List.of("ts DESC"), 5, 10);
        assertEquals("SELECT * FROM metric WHERE host = ? ORDER BY ts DESC LIMIT 5 OFFSET 10", sql);
    }

    /**
     * 校验未设置 limit 时不产生分页子句，仅设置 offset 时 offset 依然生效。
     */
    @Test
    void shouldKeepOffsetEffectiveWithoutLimit() {
        String plain = GreptimeDbEngine.buildSelectSql(Metric.class, List.of(), List.of(),
                null, null, null, List.of(), 0, 0);
        assertEquals("SELECT * FROM metric", plain);
        String offsetOnly = GreptimeDbEngine.buildSelectSql(Metric.class, List.of(), List.of(),
                "", null, "", List.of(), 0, 20);
        assertTrue(offsetOnly.contains("OFFSET 20"), offsetOnly);
    }

    /**
     * 校验投影列、分组列与 HAVING 均按声明渲染，且列名完成 snake_case 归一化。
     * <p>归一化只替换实体声明列名的变体；{@code AS avgValue} 是用户自定义别名而非实体列，
     * 必须原样保留，读取阶段由字段宽松匹配负责还原。</p>
     */
    @Test
    void shouldRenderProjectionGroupByHaving() {
        String sql = GreptimeDbEngine.buildSelectSql(Metric.class,
                List.of("host", "AVG(cpuutil) AS avgValue"), List.of(),
                "cpuutil > ?", "host", "AVG(cpuutil) > ?", List.of(), 0, 0);
        assertEquals("SELECT host, AVG(cpu_util) AS avgValue FROM metric"
                        + " WHERE cpu_util > ? GROUP BY host HAVING AVG(cpu_util) > ?", sql);
    }

    /**
     * 校验别名与实体列同名时同样被归一化为真实列名，保持与列标签的一致性。
     */
    @Test
    void shouldNormalizeAliasThatCollidesWithEntityColumn() {
        String sql = GreptimeDbEngine.buildSelectSql(Metric.class,
                List.of("AVG(cpuutil) AS cpuUtil"), List.of(),
                null, null, null, List.of(), 0, 0);
        assertEquals("SELECT AVG(cpu_util) AS cpu_util FROM metric", sql);
    }

    /**
     * 校验仅有分组列而无显式投影时，投影收敛为分组列，避免服务端报非分组列错误。
     */
    @Test
    void shouldProjectGroupColumnsWhenNoExplicitSelect() {
        String sql = GreptimeDbEngine.buildSelectSql(Metric.class, List.of(), List.of(),
                null, "host", null, List.of(), 0, 0);
        assertEquals("SELECT host FROM metric GROUP BY host", sql);
    }

    /**
     * 校验 JOIN 子句随查询一并下推。
     */
    @Test
    void shouldRenderJoins() {
        String sql = GreptimeDbEngine.buildSelectSql(Metric.class, List.of(),
                List.of(new JoinClause("INNER", "host_meta", null, "metric.host = host_meta.host")),
                "ts > ?", null, null, List.of(), 0, 0);
        assertEquals("SELECT * FROM metric INNER JOIN host_meta ON metric.host = host_meta.host"
                + " WHERE ts > ?", sql);
    }

    /**
     * 校验表名走白名单：非法 {@code @TableName} 直接拒绝，不进入 SQL 拼接。
     */
    @Test
    void shouldRejectIllegalTableNameIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> GreptimeDbEngine.buildSelectSql(
                BadNamed.class, List.of(), List.of(), null, null, null, List.of(), 0, 0));
    }

    /**
     * 校验未配置数据源时各入口显式抛错，不回退内存假实现。
     */
    @Test
    void shouldFailFastWithoutDataSource() {
        GreptimeDbEngine engine = new GreptimeDbEngine();
        assertThrows(IllegalStateException.class, engine::client);
        assertNull(engine.getExecutor());
        assertFalse(engine.supportsSql());
        assertFalse(engine.supportsNativePaging(Metric.class));
        assertThrows(UnsupportedOperationException.class, () -> engine.store("t", List.of(1)));
        assertThrows(UnsupportedOperationException.class,
                () -> engine.executeUpdate(new UpdateSql<>(Metric.class, "host = ?", "ts = ?", List.of("a", 1L))));
    }

    /**
     * 校验 DELETE 必须携带 WHERE，且条件缺失时不触达连接层。
     */
    @Test
    void shouldRequireWhereClauseForDelete() {
        GreptimeDbEngine engine = new GreptimeDbEngine();
        assertThrows(IllegalStateException.class,
                () -> engine.executeDelete(new DeleteSql<>(Metric.class, null, List.of())));
        assertThrows(IllegalStateException.class,
                () -> engine.executeDelete(new DeleteSql<>(Metric.class, "   ", List.of())));
    }

    /**
     * 校验数据源名称与 JDBC 覆盖地址的参数守卫。
     */
    @Test
    void shouldValidateRegistrationArguments() {
        GreptimeDbEngine engine = new GreptimeDbEngine();
        assertThrows(IllegalArgumentException.class,
                () -> engine.addDataSource(null, "127.0.0.1:4001", "public", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> engine.addDataSource("  ", "127.0.0.1:4001", "public", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> engine.addDataSource("default", (com.chua.common.support.lang.datasource.engine.EngineDataSource<Object>) null));
        assertThrows(IllegalArgumentException.class, () -> engine.setJdbcUrl("127.0.0.1:4002"));
        assertDoesNotThrow(() -> engine.setJdbcUrl(""));
    }

    /**
     * 校验 close() 之后引擎拒绝取用客户端，重新注册数据源才恢复。
     */
    @Test
    void shouldGuardUsageAfterClose() {
        GreptimeDbEngine engine = new GreptimeDbEngine();
        engine.close();
        IllegalStateException ex = assertThrows(IllegalStateException.class, engine::client);
        assertTrue(ex.getMessage().contains("已关闭"), ex.getMessage());
    }

    /**
     * 校验数据源封装：关闭后客户端引用被清空且禁止再注入，方言始终非空。
     */
    @Test
    void shouldEnforceDataSourceLifecycle() {
        GreptimeDbEngineDataSource ds = new GreptimeDbEngineDataSource(
                "default", "127.0.0.1:4001", null, null, "public", null);
        assertEquals("default", ds.name());
        assertEquals("127.0.0.1:4001", ds.url());
        assertNotNull(ds.getDialect());
        assertThrows(IllegalArgumentException.class, () -> ds.setSource("not-a-client"));
        assertThrows(IllegalArgumentException.class, () -> ds.setDialect(null));
        ds.close();
        ds.close();
        assertNull(ds.getSource());
        assertTrue(ds.isClosed());
        assertThrows(IllegalStateException.class, () -> ds.setSource(new Object()));
        assertThrows(IllegalArgumentException.class, () -> new GreptimeDbEngineDataSource(
                " ", "127.0.0.1:4001", null, null, "public", null));
    }
}
