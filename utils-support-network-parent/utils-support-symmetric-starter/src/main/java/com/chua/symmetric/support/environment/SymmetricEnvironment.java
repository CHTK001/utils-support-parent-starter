package com.chua.symmetric.support.environment;

import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * SymmetricDS 环境配置构造器。
 *
 * <p>提供类型安全的 Builder API，快速创建各数据库的 SymmetricDS 同步环境配置。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * DirectoryPollerEnvironment env = SymmetricEnvironment.mysql("sym-node-1")
 *     .groupId("store").externalId("node-1")
 *     .host("localhost").username("root").password("pass").database("mydb")
 *     .autoSetup(true)
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
public class SymmetricEnvironment {

    public static final String KEY_ENGINE_NAME = "symmetric.engine.name";
    public static final String KEY_DB_TYPE = "symmetric.db.type";
    public static final String KEY_GROUP_ID = "symmetric.group.id";
    public static final String KEY_EXTERNAL_ID = "symmetric.external.id";
    public static final String KEY_REGISTRATION_URL = "symmetric.registration.url";
    public static final String KEY_SYNC_URL = "symmetric.sync.url";
    public static final String KEY_HOST = "db.host";
    public static final String KEY_PORT = "db.port";
    public static final String KEY_USERNAME = "db.username";
    public static final String KEY_PASSWORD = "db.password";
    public static final String KEY_DATABASE = "db.name";
    public static final String KEY_TABLE_INCLUDE_LIST = "symmetric.table.include.list";
    public static final String KEY_TABLE_EXCLUDE_LIST = "symmetric.table.exclude.list";
    public static final String KEY_AUTO_CREATE_TABLES = "symmetric.auto.create.tables";
    public static final String KEY_INITIAL_LOAD = "symmetric.initial.load";
    public static final String KEY_AUTO_REGISTER = "symmetric.auto.register";
    public static final String KEY_AUTO_SETUP = "symmetric.auto.setup";

    private SymmetricEnvironment() {}

    /**
     * 创建通用的 Builder。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建 MySQL 环境的 Builder。
     */
    public static Builder mysql(String engineName) { return builder().engineName(engineName).dbType("mysql"); }
    /**
     * 创建 PostgreSQL 环境的 Builder。
     */
    public static Builder postgres(String engineName) { return builder().engineName(engineName).dbType("postgres"); }
    /**
     * 创建 Oracle 环境的 Builder。
     */
    public static Builder oracle(String engineName) { return builder().engineName(engineName).dbType("oracle"); }
    /**
     * 创建 SQL Server 环境的 Builder。
     */
    public static Builder sqlserver(String engineName) { return builder().engineName(engineName).dbType("sqlserver"); }
    /**
     * 创建 MariaDB 环境的 Builder。
     */
    public static Builder mariadb(String engineName) { return builder().engineName(engineName).dbType("mariadb"); }
    /**
     * 创建 DB2 环境的 Builder。
     */
    public static Builder db2(String engineName) { return builder().engineName(engineName).dbType("db2"); }
    /**
     * 创建 Informix 环境的 Builder。
     */
    public static Builder informix(String engineName) { return builder().engineName(engineName).dbType("informix"); }
    /**
     * 创建 H2 环境的 Builder。
     */
    public static Builder h2(String engineName) { return builder().engineName(engineName).dbType("h2"); }
    /**
     * 创建 Derby 环境的 Builder。
     */
    public static Builder derby(String engineName) { return builder().engineName(engineName).dbType("derby"); }
    /**
     * 创建 Firebird 环境的 Builder。
     */
    public static Builder firebird(String engineName) { return builder().engineName(engineName).dbType("firebird"); }
    /**
     * 创建 Sybase ASE 环境的 Builder。
     */
    public static Builder ase(String engineName) { return builder().engineName(engineName).dbType("ase"); }
    /**
     * 创建 SQL Anywhere 环境的 Builder。
     */
    public static Builder sqlanywhere(String engineName) { return builder().engineName(engineName).dbType("sqlanywhere"); }
    /**
     * 创建 Vertica 环境的 Builder。
     */
    public static Builder vertica(String engineName) { return builder().engineName(engineName).dbType("vertica"); }
    /**
     * 创建 ClickHouse 环境的 Builder。
     */
    public static Builder clickhouse(String engineName) { return builder().engineName(engineName).dbType("clickhouse"); }

    /**
     * SymmetricDS 环境配置 Builder。
     */
    public static class Builder {
        private final Map<String, String> props = new LinkedHashMap<>();
        private long pollingInterval = 10;
        private TimeUnit timeUnit = TimeUnit.SECONDS;
        private Set<WatcherEvent> events = Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY, WatcherEvent.DELETE);

        public Builder engineName(String engineName) { props.put(KEY_ENGINE_NAME, engineName); return this; }
        public Builder dbType(String dbType) { props.put(KEY_DB_TYPE, dbType); return this; }
        public Builder groupId(String groupId) { props.put(KEY_GROUP_ID, groupId); return this; }
        public Builder externalId(String externalId) { props.put(KEY_EXTERNAL_ID, externalId); return this; }
        public Builder registrationUrl(String url) { props.put(KEY_REGISTRATION_URL, url); return this; }
        public Builder syncUrl(String url) { props.put(KEY_SYNC_URL, url); return this; }
        public Builder host(String host) { props.put(KEY_HOST, host); return this; }
        public Builder port(String port) { props.put(KEY_PORT, port); return this; }
        public Builder port(int port) { props.put(KEY_PORT, String.valueOf(port)); return this; }
        public Builder username(String username) { props.put(KEY_USERNAME, username); return this; }
        public Builder password(String password) { props.put(KEY_PASSWORD, password); return this; }
        public Builder database(String database) { props.put(KEY_DATABASE, database); return this; }
        public Builder tableIncludeList(String list) { props.put(KEY_TABLE_INCLUDE_LIST, list); return this; }
        public Builder tableExcludeList(String list) { props.put(KEY_TABLE_EXCLUDE_LIST, list); return this; }
        public Builder autoCreateTables(boolean v) { props.put(KEY_AUTO_CREATE_TABLES, String.valueOf(v)); return this; }
        public Builder initialLoad(boolean v) { props.put(KEY_INITIAL_LOAD, String.valueOf(v)); return this; }
        public Builder autoRegister(boolean v) { props.put(KEY_AUTO_REGISTER, String.valueOf(v)); return this; }
        public Builder autoSetup(boolean v) { props.put(KEY_AUTO_SETUP, String.valueOf(v)); return this; }
        public Builder property(String key, String value) { props.put(key, value); return this; }
        public Builder pollingInterval(long interval, TimeUnit unit) { this.pollingInterval = interval; this.timeUnit = unit; return this; }
        public Builder events(WatcherEvent... events) { this.events = Set.of(events); return this; }

        /**
         * 构建 DirectoryPollerEnvironment。
         *
         * @return 环境配置对象
         */
        public DirectoryPollerEnvironment build() {
            DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(events, pollingInterval, timeUnit);
            props.forEach(env::setProperty);
            return env;
        }
    }
}
