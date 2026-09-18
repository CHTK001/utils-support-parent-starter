package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.datasource.support.dialect.SqlDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方言分页 SQL 渲染与 Pagination 计算单元测试。
 *
 * @author CH
 */
@DisplayName("方言分页 SQL 测试")
class DialectPagingSqlTest {

    /** 基础查询 SQL */
    private static final String BASE_SQL = "SELECT * FROM fixture_user";

    /**
     * 测试：PaginationCalculation。
     */
    @Test
    @DisplayName("Pagination:offset = (pageNum-1) * pageSize")
    void testPaginationCalculation() {
        Pagination first = new Pagination().setPageNum(1).setPageSize(10);
        assertEquals(0, first.getOffset());
        assertEquals(10, first.getLimit());

        Pagination third = new Pagination().setPageNum(3).setPageSize(20);
        assertEquals(40, third.getOffset());
        assertEquals(20, third.getLimit());
    }

    /**
     * 测试：PaginationSafeFallback。
     */
    @Test
    @DisplayName("Pagination:非法页码/页长兜底为第 1 页与最小 1 条")
    void testPaginationSafeFallback() {
        Pagination invalid = new Pagination().setPageNum(0).setPageSize(0);
        assertEquals(0, invalid.getOffset());
        assertEquals(1, invalid.getLimit());

        Pagination negative = new Pagination().setPageNum(-5).setPageSize(-5);
        assertEquals(0, negative.getOffset());
        assertEquals(1, negative.getLimit());
    }

    /**
     * 测试：Default上限偏移量。
     */
    @Test
    @DisplayName("默认方言:追加 LIMIT ? OFFSET ?")
    void testDefaultLimitOffset() {
        Dialect dialect = new SqlDialect("unknown_db");
        Pagination page = new Pagination().setPageNum(3).setPageSize(10);
        String sql = dialect.processSql(BASE_SQL, page);
        assertEquals(BASE_SQL + " LIMIT 10 OFFSET 20", sql);
    }

    /**
     * 测试：CustomPagination模板。
     */
    @Test
    @DisplayName("自定义 pagination-sql 模板:{sql}/{offset}/{limit} 占位符替换")
    void testCustomPaginationTemplate() {
        // 模拟 SQL Server / Oracle 12c 风格模板
        Properties props = new Properties();
        props.setProperty("pagination-sql",
                "{sql} OFFSET {offset} ROWS FETCH NEXT {limit} ROWS ONLY");
        Dialect dialect = new SqlDialect("custom", props);

        Pagination page = new Pagination().setPageNum(2).setPageSize(15);
        String sql = dialect.processSql(BASE_SQL, page);
        assertEquals(BASE_SQL + " OFFSET 15 ROWS FETCH NEXT 15 ROWS ONLY", sql);
    }

    /**
     * 测试：SQL服务端模板。
     */
    @Test
    @DisplayName("SQL Server 2012 方言:分页渲染为 OFFSET ... ROWS FETCH NEXT")
    void testSqlServerTemplate() {
        Properties props = new Properties();
        props.setProperty("pagination-sql",
                "{sql} OFFSET {offset} ROWS FETCH NEXT {limit} ROWS ONLY");
        Dialect dialect = new SqlDialect("sqlserver", props);

        String sql = dialect.processSql(BASE_SQL,
                new Pagination().setPageNum(1).setPageSize(20));
        assertTrue(sql.endsWith("OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY"));
    }
}
