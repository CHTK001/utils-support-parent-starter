package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * SQLite 数据库 MCP 提供者。
 *
 * <p>通过 {@code npx -y @modelcontextprotocol/server-sqlite} 安装，为 AI 客户端
 * 提供 SQLite 数据库查询与写入能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("sqlite")
public class SqliteMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    protected String npmPackage() {
        return "@modelcontextprotocol/server-sqlite";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("sqlite_query", "执行 SQL 查询",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "SQL 查询语句")),
                                "required", List.of("query"))),
                new McpToolDescriptor("sqlite_execute", "执行 SQL 写操作",
                        Map.of("type", "object", "properties", Map.of(
                                "sql", Map.of("type", "string", "description", "SQL 语句")),
                                "required", List.of("sql"))));
    }
}