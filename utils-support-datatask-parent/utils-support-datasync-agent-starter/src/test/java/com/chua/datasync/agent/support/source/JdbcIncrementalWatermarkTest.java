// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasync.agent.support.source;

import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.datasync.agent.support.offset.FileSyncDataOffsetStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每目标独立水位与复合增量 SQL 的测试。
 *
 * <p><b>为什么必须有这些用例</b>：水位是增量同步的唯一进度凭据。
 * 若多个目标共用一个水位文件，A 目标推进后 B 目标会直接跳过那批数据，
 * 而且是静默丢失——不会报错，事后极难发现。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class JdbcIncrementalWatermarkTest {

    /**
     * 不同映射必须落到不同水位文件。
     */
    @Test
    @DisplayName("多目标水位按映射隔离，互不覆盖")
    void watermarksIsolatedPerMapping(@TempDir Path dir) {
        FileSyncDataOffsetStorage storage = new FileSyncDataOffsetStorage(dir);

        storage.write(new SyncDataOffset("src-1", "100", 1L, "target-A"));
        storage.write(new SyncDataOffset("src-1", "200", 2L, "target-B"));

        SyncDataOffset a = storage.read("src-1", "target-A", null);
        SyncDataOffset b = storage.read("src-1", "target-B", null);

        assertEquals("100", String.valueOf(a.offsetValue()), "A 目标水位应保持 100");
        assertEquals("200", String.valueOf(b.offsetValue()), "B 目标水位应保持 200");
    }

    /**
     * 映射级水位不存在时，应回落到源级旧水位（升级兼容）。
     */
    @Test
    @DisplayName("映射级水位缺失时回落到源级存量水位")
    void fallsBackToSourceLevelWatermark(@TempDir Path dir) {
        FileSyncDataOffsetStorage storage = new FileSyncDataOffsetStorage(dir);
        storage.write(new SyncDataOffset("src-legacy", "42", 1L, null));

        SyncDataOffset offset = storage.read("src-legacy", "target-X", null);

        assertEquals("42", String.valueOf(offset.offsetValue()), "应读到升级前的源级水位");
    }

    /**
     * 没有任何水位时返回 null（按首跑处理）。
     */
    @Test
    @DisplayName("无水位时返回 null")
    void returnsNullWhenAbsent(@TempDir Path dir) {
        FileSyncDataOffsetStorage storage = new FileSyncDataOffsetStorage(dir);
        assertNull(storage.read("never-run", "target-X", null));
    }

    /**
     * 单列增量：包一层子查询 + 正确的排序，不再盲目拼 {@code WHERE id >}。
     */
    @Test
    @DisplayName("单列增量 SQL 包成子查询并按增量列排序")
    void singleColumnIncrementalSql() throws Exception {
        JdbcDataSyncAgentSource source = newSource("SELECT a, b FROM t");
        String sql = invokeBuildSql(source, java.util.List.<String>of("updated_at"), "2026-10-24 10:00:00");
        assertTrue(sql.contains("__sync_inc"), "应包成子查询，避免原 SQL 自带 WHERE 时语法错乱: " + sql);
        assertTrue(sql.contains("updated_at > '2026-10-24 10:00:00'"), "应带增量条件: " + sql);
        assertTrue(sql.contains("ORDER BY updated_at ASC"), "应按增量列排序: " + sql);
    }

    /**
     * 复合增量：字典序展开，前三元组相等再比下一个。
     */
    @Test
    @DisplayName("复合增量 SQL 展开成字典序条件")
    void compositeIncrementalSql() throws Exception {
        JdbcDataSyncAgentSource source = newSource("SELECT a, b FROM t");
        String sql = invokeBuildSql(source, java.util.List.<String>of("created_at", "id"),
                                    "[\"2026-10-24 10:00:00\",\"812\"]");

        assertTrue(sql.contains("created_at > '2026-10-24 10:00:00'"), "首个分支应比第一列: " + sql);
        assertTrue(sql.contains("(created_at = '2026-10-24 10:00:00' AND id > 812)"),
                "第二个分支应先等值再比下一列: " + sql);
        assertTrue(sql.contains("ORDER BY created_at ASC, id ASC"), "应按全部增量列排序: " + sql);
    }

    /**
     * 不是 SELECT 的语句不应被包装。
     */
    @Test
    @DisplayName("非 SELECT 语句不做增量包装")
    void nonSelectLeftAlone() throws Exception {
        JdbcDataSyncAgentSource source = newSource("CALL some_proc()");
        String sql = invokeBuildSql(source, java.util.List.<String>of("updated_at"), "100");
        assertEquals("CALL some_proc()", sql, "非 SELECT 应原样返回");
    }

    /**
     * 没有增量列时是全量读取，不追加任何条件。
     */
    @Test
    @DisplayName("无增量列时保持全量 SQL")
    void noIncColumnsKeepsFullSql() throws Exception {
        JdbcDataSyncAgentSource source = newSource("SELECT a FROM t");
        String sql = invokeBuildSql(source, java.util.List.<String>of(), "100");
        assertEquals("SELECT a FROM t", sql, "全量读取应原样返回");
    }

    /**
     * 构造被测源（不真连库：仅用于验证 SQL 拼接）。
     *
     * @param sql 原始 SQL
     * @return 源实例
     */
    private JdbcDataSyncAgentSource newSource(String sql) {
        return new JdbcDataSyncAgentSource("src-test", "input-test",
                "jdbc:h2:mem:watermark_test", "sa", "", sql,
                java.util.List.<String>of(), null, "a", "b");
    }

    /**
     * 反射调用私有 SQL 拼接方法。
     *
     * @param source     源
     * @param incColumns 增量列
     * @param offset     水位值
     * @return 拼接后的 SQL
     * @throws Exception 反射失败
     */
    private String invokeBuildSql(JdbcDataSyncAgentSource source, java.util.List<String> incColumns, Object offset)
            throws Exception {
        // incColumns 是构造参数；这里用反射覆盖以便逐用例构造不同配置
        java.lang.reflect.Field field = JdbcDataSyncAgentSource.class.getDeclaredField("incColumns");
        field.setAccessible(true);
        field.set(source, incColumns);

        Method method = JdbcDataSyncAgentSource.class
                .getDeclaredMethod("buildSqlWithOffset", Object.class);
        method.setAccessible(true);
        return (String) method.invoke(source, offset);
    }
}