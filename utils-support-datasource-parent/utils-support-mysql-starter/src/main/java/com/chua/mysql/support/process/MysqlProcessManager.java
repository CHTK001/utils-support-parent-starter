package com.chua.mysql.support.process;

import com.chua.datasource.support.process.ProcessInfo;
import com.chua.datasource.support.process.ProcessManager;
import com.chua.datasource.support.user.DataSourceAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 会话进程管理器 SPI 实现。
 *
 * <p>{@code SHOW FULL PROCESSLIST} 需要 {@code PROCESS} 权限才能看到别人的会话；
 * 没有该权限时 MySQL 只返回自己的会话（不报错），此时列表「看起来很少」是服务端行为，
 * 不是实现吞了数据。</p>
 *
 * <p>{@code KILL} 需要 {@code CONNECTION_ADMIN}（或 {@code SUPER}）权限，只能杀自己的会话
 * 或自己有权限的会话。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
public class MysqlProcessManager implements ProcessManager, DataSourceAware {

    /**
     * 数据来源。
     */
    private DataSource dataSource;

    /**
     * 返回 SPI 扩展键：{@code mysql}。
     *
     * @return "mysql"
     */
    @Override
    public String type() {
        return "mysql";
    }

    /**
     * 设置 JDBC 数据源。
     *
     * @param dataSource 数据源
     */
    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 列出当前所有会话。
     *
     * @return 会话列表
     */
    @Override
    public List<ProcessInfo> listProcesses() {
        List<ProcessInfo> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SHOW FULL PROCESSLIST")) {
            while (rs.next()) {
                long time = rs.getLong("Time");
                if (rs.wasNull()) {
                    // Sleep 之外的少数命令 Time 为 NULL，用 -1 表示「不适用」而不是假的 0
                    time = -1L;
                }
                list.add(new ProcessInfo(
                        rs.getString("Id"),
                        rs.getString("User"),
                        rs.getString("Host"),
                        rs.getString("db"),
                        rs.getString("Command"),
                        time,
                        rs.getString("State"),
                        rs.getString("Info"),
                        null));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("列出 MySQL 会话失败: " + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 结束指定会话。
     *
     * <p>会话 ID 先按 long 校验再拼语句：{@code KILL} 不支持占位符，只能拼，因此
     * 这里必须挡住非数字输入。</p>
     *
     * @param processId 会话 ID
     * @return 是否执行成功
     */
    @Override
    public boolean killProcess(String processId) {
        long id;
        try {
            id = Long.parseLong(processId == null ? "" : processId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法会话 ID: " + processId);
        }
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute("KILL " + id);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("关闭会话 " + id + " 失败: " + e.getMessage(), e);
        }
    }
}
