package com.chua.datasource.support.process;

/**
 * 数据库会话进程信息。
 *
 * <p>字段取自各内核的会话视图（MySQL 的 {@code SHOW FULL PROCESSLIST}、PostgreSQL 的
 * {@code pg_stat_activity} 等），因此只保留各内核都有的最小公共集；内核特有的列
 * 由实现自行塞进 {@code extra}，接口不承诺语义。</p>
 *
 * @param id       进程 / 会话 ID（字符串，因为不同内核的 ID 语义不同）
 * @param user     登录用户
 * @param host     客户端地址
 * @param database 当前库
 * @param command  命令 / 状态（如 Query / Sleep / active）
 * @param time     已持续秒数；内核不提供时为 -1
 * @param state    详细状态（可为 null）
 * @param info     正在执行的语句（可为 null）
 * @param extra    内核特有列（可为 null）
 * @author CH
 * @since 4.0.0.44
 */
public record ProcessInfo(
        String id,
        String user,
        String host,
        String database,
        String command,
        long time,
        String state,
        String info,
        Object extra
) {
}
